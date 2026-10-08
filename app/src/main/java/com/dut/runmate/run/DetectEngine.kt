package com.dut.runmate.run

import com.dut.runmate.data.Checkpoint
import com.dut.runmate.data.Prefs

/**
 * 打卡检测状态机（每个点位独立一个实例状态）。
 *
 * API 模式（用户方案）：
 *   进入检测圈 → 锁定基线距离 → 轮询接口；
 *   距离增量 ≥ 阈值 ⇒ 判定打卡成功；
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
    }

    private val snaps = mutableListOf<Snap>()
    var apiMode = false

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
            if (s.state == RunBus.CpState.CONFIRMED) { s.lastDistM = -1; continue }
            val d = com.dut.runmate.geo.GeoKit.dist(lat, lon, s.cp.lat, s.cp.lon)
            s.lastDistM = Math.round(d).toInt()
            val zone = s.cp.radius.toDouble()
            when (s.state) {
                RunBus.CpState.PENDING -> {
                    if (d <= zone) enterZone(s)
                    else if (d <= prefs.approachDist && !s.nearSaid) {
                        s.nearSaid = true
                        s.state = RunBus.CpState.NEAR
                        onEvent(Ev.Approach(s, s.lastDistM))
                    }
                }
                RunBus.CpState.NEAR -> {
                    if (d <= zone) enterZone(s)
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
                else -> {}
            }
        }
    }

    /** 每次接口距离返回时调用 */
    fun onApiDistance(v: Double) {
        for (s in snaps) {
            when (s.state) {
                RunBus.CpState.IN_ZONE, RunBus.CpState.GRACE -> {
                    val b = s.baseline
                    if (b == null) s.baseline = v
                    else if (v >= b + prefs.confirmDelta) {
                        s.state = RunBus.CpState.CONFIRMED
                        onEvent(Ev.Confirmed(s))
                    }
                }
                else -> {}
            }
        }
    }

    private fun enterZone(s: Snap) {
        s.state = RunBus.CpState.IN_ZONE
        s.baseline = null
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
        RunBus.CpSnap(it.cp, it.state, it.lastDistM, it.missedCount)
    }
}
