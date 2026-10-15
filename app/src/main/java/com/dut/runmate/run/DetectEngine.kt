package com.dut.runmate.run

import com.dut.runmate.data.Checkpoint
import com.dut.runmate.data.Prefs

/**
 * 打卡检测状态机（每个点位独立一个实例状态）。
 *
 * API 模式（打卡核对）：
 *   进入检测圈 → 以「进区前最后一次轮询距离」为基线（30s 内新鲜，否则首个区内轮询值作基线）；
 *   之后轮询 findExtExercise，服务端距离增量 ≥ 阈值（RFID 命中一次 +100m）⇒ 判定打卡成功；
 *   出圈后宽限期内仍未增量 ⇒ 漏卡，强提醒。
 * 纯 GPS 模式：
 *   进圈/出圈语音提示，无法判定漏卡。
 */
class DetectEngine(
    private val prefs: Prefs,
    private val onEvent: (Ev) -> Unit
) {

    sealed class Ev {
        data class Approach(val r: Snap, val distM: Int) : Ev()
        data class EnterZone(val r: Snap) : Ev()
        data class Confirmed(val r: Snap) : Ev()
        data class Missed(val r: Snap) : Ev()
        data class PassedGps(val r: Snap) : Ev()
    }

    inner class Snap(val cp: Checkpoint) {
        var state = RunBus.CpState.PENDING
        var nearSaid = false
        var baseline: Double? = null
        var graceLeftAt = 0L
        var missedCount = 0
        var lastDistM = -1
        /** v1.8.3：进区时刻（毫秒）——区内长时间未确认打卡的周期性强提醒用 */
        var enteredAtMs = 0L
        /** v1.8.3：上次「打卡未确认」提醒时刻（限频） */
        var lastNagAt = 0L
        /** v1.8.13：累计确认次数（多圈跑每圈各计一次）——用于结束汇总/通知稳定显示 */
        var confirmedCount = 0
    }

    private val snaps = mutableListOf<Snap>()
    var apiMode = false

    /** 引擎级「最近一次服务端距离」及其时间：进区时用作基线（v1.3.0） */
    var lastApiV: Double? = null
        private set
    var lastApiAtMs = 0L
        private set

    val all: List<Snap> get() = snaps

    fun reset(checkpoints: List<Checkpoint>) {
        snaps.clear()
        checkpoints.forEach { snaps.add(Snap(it)) }
    }

    fun needsPoll(): Boolean = snaps.any { it.state == RunBus.CpState.IN_ZONE || it.state == RunBus.CpState.GRACE }

    fun anyActive(): Boolean = snaps.isNotEmpty()

    /** 每次 GPS 更新调用 */
    fun onLocation(lat: Double, lon: Double, nowMs: Long) {
        for (s in snaps) {
            val d = com.dut.runmate.geo.GeoKit.dist(lat, lon, s.cp.lat, s.cp.lon)
            s.lastDistM = Math.round(d).toInt()
            val zone = s.cp.radius.toDouble()
            when (s.state) {
                RunBus.CpState.PENDING -> {
                    if (d <= zone) enterZone(s, nowMs)
                    else if (d <= prefs.approachDist && !s.nearSaid) {
                        s.nearSaid = true
                        s.state = RunBus.CpState.NEAR
                        onEvent(Ev.Approach(s, s.lastDistM))
                    }
                }
                RunBus.CpState.NEAR -> {
                    if (d <= zone) enterZone(s, nowMs)
                    else if (d > prefs.approachDist + 60) { s.state = RunBus.CpState.PENDING; s.nearSaid = false }
                }
                RunBus.CpState.IN_ZONE -> {
                    if (d > zone + 8) exitZone(s, nowMs)   // +8m 滞回，防边界抖动
                }
                RunBus.CpState.GRACE -> {
                    if (d <= zone + 8) s.state = RunBus.CpState.IN_ZONE
                    else if (nowMs >= s.graceLeftAt) {
                        s.state = RunBus.CpState.MISSED
                        s.missedCount++
                        onEvent(Ev.Missed(s))
                    }
                }
                RunBus.CpState.MISSED -> {
                    if (d <= zone) { s.state = RunBus.CpState.IN_ZONE; s.baseline = null } // 补打卡重试
                    else if (d > prefs.approachDist + 60) { s.state = RunBus.CpState.PENDING; s.nearSaid = false }
                }
                // v1.8.13 根因修复（用户实报「跑到一半多语音震动全没了，距离还能刷新」）：
                // 健康长跑是多圈跑（每圈经过全部 8 个打卡点），旧版对 CONFIRMED 点位
                // 直接 continue 跳过且永不重新武装——第一圈跑完全部点位确认后，引擎
                // 彻底沉默：无预告/无进区/无确认/无漏卡，needsPoll() 也变 false 连
                // 核对失败强提醒都停（距离轮询独立所以还在刷新——症状完全吻合）。
                // 修法：离开预告圈（+60m 滞回，与 NEAR 回退一致）后重新武装为 PENDING，
                // 下一圈照常预告→进区→基线→确认（confirmedCount 累计计次）。
                RunBus.CpState.CONFIRMED -> {
                    if (d > prefs.approachDist + 60) {
                        s.state = RunBus.CpState.PENDING
                        s.nearSaid = false
                        s.baseline = null
                        s.enteredAtMs = 0L
                        s.lastNagAt = 0L
                    }
                }
                else -> {}
            }
        }
    }

    /** 每次接口距离返回时调用（v/nowMs 由服务轮询循环传入） */
    fun onApiDistance(v: Double, nowMs: Long) {
        lastApiV = v
        lastApiAtMs = nowMs
        for (s in snaps) {
            when (s.state) {
                RunBus.CpState.IN_ZONE, RunBus.CpState.GRACE -> {
                    val b = s.baseline
                    if (b == null) s.baseline = v
                    else if (v >= b + prefs.confirmDelta) {
                        s.state = RunBus.CpState.CONFIRMED
                        s.confirmedCount++      // v1.8.13：多圈累计（重武装后每圈各计一次）
                        onEvent(Ev.Confirmed(s))
                    }
                }
                else -> {}
            }
        }
    }

    /**
     * v1.8.13：时间驱动心跳（服务 500ms 轮询循环调用，不依赖 GPS 定位回调）。
     * 旧版 GRACE→MISSED（漏卡判定）只在 onLocation 里推进——GPS 精度变差
     * （>60m 被过滤）/楼宇遮挡导致定位回调停顿时，漏卡提醒永远不触发。
     * 现在宽限期到期由心跳独立判定，定位停顿也能照常漏卡告警。
     */
    fun tick(nowMs: Long) {
        for (s in snaps) {
            if (s.state != RunBus.CpState.GRACE) continue
            if (nowMs < s.graceLeftAt) continue
            s.state = RunBus.CpState.MISSED
            s.missedCount++
            onEvent(Ev.Missed(s))
        }
    }

    private fun enterZone(s: Snap, nowMs: Long) {
        s.state = RunBus.CpState.IN_ZONE
        s.enteredAtMs = nowMs      // v1.8.3：记录进区时刻
        s.lastNagAt = 0L
        // v1.3.0 基线优化：RFID 注册往往先于区内首次轮询，若等到首次区内轮询才取基线，
        // 基线可能已包含 +100m 阶跃而永远检测不到增量。改用「进区前最后一次轮询距离」
        // （30s 内新鲜）作基线，进区后的首个轮询即可捕捉到阶跃。
        val last = lastApiV
        s.baseline = if (last != null && nowMs - lastApiAtMs < 30_000L) last else null
        onEvent(Ev.EnterZone(s))
    }

    private fun exitZone(s: Snap, nowMs: Long) {
        if (!apiMode) {
            onEvent(Ev.PassedGps(s))
            s.state = RunBus.CpState.PENDING
            s.nearSaid = false
            return
        }
        s.state = RunBus.CpState.GRACE
        s.graceLeftAt = nowMs + prefs.graceSec * 1000L
    }

    fun snapshot(): List<RunBus.CpSnap> = snaps.map {
        RunBus.CpSnap(it.cp, it.state, it.lastDistM, it.missedCount, it.confirmedCount)
    }
}
