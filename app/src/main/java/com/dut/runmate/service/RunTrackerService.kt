package com.dut.runmate.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dut.runmate.MainActivity
import com.dut.runmate.R
import com.dut.runmate.alert.AlertManager
import com.dut.runmate.data.CheckpointStore
import com.dut.runmate.data.Prefs
import com.dut.runmate.data.api.ApiStore
import com.dut.runmate.geo.GeoKit
import com.dut.runmate.geo.LocGate
import com.dut.runmate.net.Http
import com.dut.runmate.run.DetectEngine
import com.dut.runmate.run.RunBus
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.roundToInt

/**
 * 前台定位服务：GPS 跟踪 + 检测状态机 + 接口轮询 + 语音/震动/提示音提醒。
 *
 * v1.6.0 防闪退加固：开始跑步后的全部异步回调（定位/轮询/通知/语音）逐段
 * try/catch 隔离——任何一段抛异常只丢弃该次回调并落盘 crash_log，绝不冒泡到
 * 主线程杀死进程（v1.5.1 用户反馈「开始跑步 1~2 秒后闪退」的防御性修复：
 * 闪退窗口恰好是首个定位回调/TTS 初始化/首次轮询三者的触发时机）。
 */
class RunTrackerService : Service() {

    companion object {
        const val ACT_START = "com.dut.runmate.START"
        const val ACT_STOP = "com.dut.runmate.STOP"
        const val CH_ID = "runmate_run"
        const val NID = 1001
    }

    private val prefs by lazy { Prefs.get(this) }
    private val store by lazy { CheckpointStore.get(filesDir) }
    private val apiStore by lazy { ApiStore.get(filesDir) }

    private var engine: DetectEngine? = null
    private var alert: AlertManager? = null
    private var scope: CoroutineScope? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var notifMgr: NotificationManager? = null

    private var startAt = 0L
    private var sessionMeters = 0.0
    private var fixCount = 0
    private var lastFix: Location? = null
    private var lastNotif = 0L
    // 定位源缓存（坐标已归一 WGS-84）：GPS 优先，网络仅兑底
    private var lastGpsFix: Location? = null

    // v1.6.1：轮询计时状态（从协程局部变量提为字段，供抽出的 pollOnce 使用）
    private var lastZonePoll = 0L
    private var lastAnchor = 0L
    private var pollReqSeen = 0L

    private val locListener = LocationListener { loc ->
        // v1.6.0：定位回调全隔离——部分 ROM 的网络定位带异常元数据时仅跳过该次，不闪退
        try {
            onFix(loc)
        } catch (e: Throwable) {
            logSoft("onFix", e)
        }
    }

    /** v1.6.0：非致命异常落盘（与 App 崩溃日志同文件），便于远程定位闪退根因 */
    private fun logSoft(where: String, e: Throwable) {
        com.dut.runmate.util.SoftLog.write(filesDir, "svc=$where", e)
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_STOP -> { stopRun(); return START_NOT_STICKY }
            else -> if (!RunBus.state.value.running) {
                // v1.6.0：启动全隔离——任何异常（含部分 ROM 的 startForeground 限制）
                // 都降级为「停止服务 + Toast 提示」，绝不让进程闪退
                try {
                    startRun()
                } catch (e: Throwable) {
                    logSoft("startRun", e)
                    RunBus.update { it.copy(running = false, apiErr = "启动失败：${e.javaClass.simpleName}") }
                    try {
                        android.widget.Toast.makeText(
                            applicationContext,
                            "跑步服务启动异常：${e.javaClass.simpleName}，已记录到崩溃日志",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    } catch (_: Throwable) {
                    }
                    try { stopSelf() } catch (_: Throwable) {}
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startRun() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return
        }

        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        notifMgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            notifMgr?.createNotificationChannel(
                NotificationChannel(CH_ID, getString(R.string.ch_name), NotificationManager.IMPORTANCE_LOW)
            )
        }

        alert = AlertManager(this, prefs).also { it.initTts() }
        // v1.6.1：挂全局协程异常处理器——服务协程内任何未捕获异常只落盘，
        // 不再经由默认处理器杀死进程（旧版轮询循环外层无保护，任何一段抛
        // 异常都会闪退，与「开始跑步/查询距离后 1~2 秒闪退」现象一致）
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> logSoft("coroutine", e) })

        engine = DetectEngine(prefs, ::onEvent).also {
            it.apiMode = prefs.apiEnabled
            it.reset(store.all())
        }

        startAt = SystemClock.elapsedRealtime()
        sessionMeters = 0.0; fixCount = 0; lastFix = null; lastGpsFix = null
        lastZonePoll = 0L; lastAnchor = 0L; pollReqSeen = RunBus.state.value.pollReq

        // 前台服务 + 常驻通知
        // v1.6.0：部分 ROM 对 startForeground(带类型) 有私有限制，失败时逐级降级
        val n = buildNotification("定位中…")
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NID, n)
            }
        } catch (e: Throwable) {
            logSoft("startForeground(typed)", e)
            try {
                startForeground(NID, n)     // 降级：不带类型重试
            } catch (e2: Throwable) {
                logSoft("startForeground", e2)
                throw e2                    // 仍失败交给外层 onStartCommand 兜底
            }
        }

        // WakeLock：息屏后 GPS 与逻辑仍运行
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "runmate:track").also {
                it.acquire(3 * 60 * 60 * 1000L)
            }

        // GPS：1s/0m 高频；网络定位兜底
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 4000L, 0f, locListener, Looper.getMainLooper())
            }
        } catch (_: Exception) { }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, Looper.getMainLooper())
        } catch (_: Exception) { }

        alert?.speak(getString(R.string.tts_started, store.count))
        RunBus.update {
            it.copy(running = true, fix = null, snaps = engine?.snapshot() ?: emptyList(),
                stats = RunBus.Stats(), summary = null, apiErr = null, apiDistance = null)
        }
        startPoller()
    }

    /**
     * v1.3.0 轮询器重构（打卡核对）：
     *  - 500ms 心跳检查三类触发条件；
     *  - 区内/宽限期：按 pollInterval 高频轮询（RFID 命中一次 +100m，进区即核对）；
     *  - 锚点轮询：无论是否在区内，每 15s 拉一次，维持「进区前基线」新鲜（DetectEngine 用）；
     *  - 手动触发：UI 递增 RunBus.pollReq（「立即核对」），下个心跳立即执行。
     */
    private fun startPoller() {
        scope?.launch(Dispatchers.Main) {
            while (isActive) {
                delay(500)
                // v1.6.1：单轮全部隔离——任何一段异常只跳过本轮，循环继续
                try {
                    pollOnce()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Throwable) {
                    logSoft("pollLoop", e)
                }
            }
        }
    }

    /** v1.6.1：单次轮询（从循环体抽出便于整段隔离） */
    private suspend fun pollOnce() {
        if (!prefs.apiEnabled) return
        val eng = engine ?: return
        val prof = apiStore.distanceProfile() ?: return
        if (prof.url.isBlank()) return
        val now = SystemClock.elapsedRealtime()
        val manual = RunBus.state.value.pollReq != pollReqSeen
        if (manual) pollReqSeen = RunBus.state.value.pollReq
        val need = eng.needsPoll()
        val zoneDue = need && now - lastZonePoll >= prefs.pollInterval * 1000L
        val anchorDue = now - lastAnchor > 15_000L
        if (!zoneDue && !anchorDue && !manual) return

        val resp = try {
            Http.call(prof, prefs.apiToken, prefs.apiCookie)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logSoft("poll", e)
            return
        }
        val doneAt = SystemClock.elapsedRealtime()
        if (resp.error != null) {
            RunBus.update { it.copy(apiErr = "网络错误: ${resp.error}") }
            return
        }
        if (resp.code !in 200..299) {
            RunBus.update { it.copy(apiErr = "HTTP ${resp.code}") }
            return
        }
        if (need) lastZonePoll = doneAt
        lastAnchor = doneAt
        val v = Http.extractDouble(resp.body, prof.distPath)
        if (v != null) {
            RunBus.update { it.copy(apiDistance = v, apiErr = null) }
            try {
                eng.onApiDistance(v, doneAt)
            } catch (e: Throwable) {
                logSoft("onApiDistance", e)
            }
            try {
                refreshNotification()      // v1.5.0：打卡距离阶跃立即上锁屏通知
            } catch (e: Throwable) {
                logSoft("refreshNotification", e)
            }
        } else {
            RunBus.update { it.copy(apiErr = "未能提取: ${prof.distPath}") }
        }
    }

    private fun onFix(raw: Location) {
        // v1.2.0：坐标归一（网络定位 GCJ-02 → WGS-84），GPS 优先策略：
        // GPS 新鲜（20s 内）时忽略网络定位，避免低精度坐标使检测圈抖动。
        val loc = LocGate.normalize(raw)
        if (LocGate.isNetwork(raw)) {
            val gpsFresh = lastGpsFix != null &&
                (raw.elapsedRealtimeNanos - lastGpsFix!!.elapsedRealtimeNanos) < 20_000_000_000L
            if (gpsFresh) return
        } else {
            lastGpsFix = loc
        }
        if (loc.accuracy > 60f) return
        val eng = engine ?: return
        val now = SystemClock.elapsedRealtime()

        // 自测里程（过滤跳点）
        val prev = lastFix
        if (prev != null && prev.accuracy < 40f) {
            val d = GeoKit.dist(prev.latitude, prev.longitude, loc.latitude, loc.longitude)
            if (d in 0.5..80.0) sessionMeters += d
        }
        lastFix = loc
        fixCount++

        eng.onLocation(loc.latitude, loc.longitude, now)

        val stats = RunBus.Stats(
            elapsedMs = now - startAt,
            meters = sessionMeters,
            fixes = fixCount
        )
        val snaps = eng.snapshot()
        val nearest = snaps
            .filter { it.state != RunBus.CpState.CONFIRMED && it.distM >= 0 }
            .minByOrNull { it.distM }
        RunBus.update {
            it.copy(
                fix = loc, gpsAcc = loc.accuracy, snaps = snaps, stats = stats,
                nearest = nearest, inZoneName = snaps.firstOrNull { s -> s.state == RunBus.CpState.IN_ZONE }?.cp?.name
            )
        }
        if (now - lastNotif > 1000) {
            lastNotif = now
            updateNotificationText(nearest, snaps)
        }
    }

    private fun onEvent(ev: DetectEngine.Ev) {
        val a = alert ?: return
        try {
            when (ev) {
                is DetectEngine.Ev.Approach -> {
                    a.speak(getString(R.string.tts_approach, "${ev.distM}", ev.r.cp.name))
                    a.chime(AlertManager.Kind.INFO)
                }
                is DetectEngine.Ev.EnterZone -> a.speak(getString(R.string.tts_enter, ev.r.cp.name))
                is DetectEngine.Ev.Confirmed -> {
                    a.speak(getString(R.string.tts_confirmed, ev.r.cp.name))
                    a.chime(AlertManager.Kind.OK); a.vibrate(AlertManager.Kind.OK)
                }
                is DetectEngine.Ev.Missed -> {
                    a.speak(getString(R.string.tts_missed, ev.r.cp.name))
                    a.chime(AlertManager.Kind.WARN); a.vibrate(AlertManager.Kind.WARN)
                }
                is DetectEngine.Ev.PassedGps -> a.speak(getString(R.string.tts_pass_gps, ev.r.cp.name))
            }
        } catch (e: Throwable) {
            logSoft("onEvent", e)   // v1.6.0：语音/提醒失败不影响跟踪
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val stopPi = PendingIntent.getService(this, 1,
            Intent(this, RunTrackerService::class.java).setAction(ACT_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.drawable.ic_run)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            // v1.5.0 锁屏显示：VISIBILITY_PUBLIC 在锁屏完整展示；
            // CATEGORY_WORKOUT 在 Android 12+ 归入「锻炼」分组（锁屏顶部胶囊入口）。
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setContentIntent(pi)
            .addAction(R.drawable.ic_stop, getString(R.string.notif_stop), stopPi)
            .build()
    }

    /** v1.5.0：通知文本 = 官方打卡距离 + 自测 GPS 里程 + 点位状态（锁屏可见） */
    private fun updateNotificationText(nearest: RunBus.CpSnap?, snaps: List<RunBus.CpSnap>) {
        val ok = snaps.count { it.state == RunBus.CpState.CONFIRMED }
        val st = RunBus.state.value
        val apiTxt = st.apiDistance
            ?.let { "官方打卡 %d m".format(it.roundToInt()) }
            ?: "官方打卡：待接口"
        val gpsTxt = if (st.stats.meters >= 10)
            "GPS %.2f km".format(st.stats.meters / 1000.0)
        else "GPS 累积中"
        val zoneTxt = when {
            snaps.any { it.state == RunBus.CpState.MISSED } -> "⚠ 有点位未确认，请留意"
            snaps.any { it.state == RunBus.CpState.IN_ZONE } -> "检测区内，等待打卡确认…"
            nearest != null -> "最近：${nearest.cp.name} ${nearest.distM} m · 已确认 $ok/${snaps.size}"
            else -> "定位中…"
        }
        notifMgr?.let { nm ->
            try {
                nm.notify(NID, buildNotification("$apiTxt · $gpsTxt\n$zoneTxt"))
            } catch (e: Throwable) {
                logSoft("notify", e)
            }
        }
    }

    /** v1.5.0：官方距离变化（RFID 命中）时立即刷新通知，息屏/锁屏也能看到最新打卡距离 */
    private fun refreshNotification() {
        val eng = engine ?: return
        val snaps = eng.snapshot()
        val nearest = snaps
            .filter { it.state != RunBus.CpState.CONFIRMED && it.distM >= 0 }
            .minByOrNull { it.distM }
        updateNotificationText(nearest, snaps)
    }

    private fun stopRun() {
        val eng = engine
        val now = SystemClock.elapsedRealtime()
        val stats = RunBus.Stats(now - startAt, sessionMeters, fixCount)
        val snaps = eng?.snapshot() ?: emptyList()
        val confirmed = snaps.filter { it.state == RunBus.CpState.CONFIRMED }.map { it.cp.name }
        val missed = snaps.filter { it.state == RunBus.CpState.MISSED }.map { it.cp.name }
        val passedGps = snaps.filter { it.state == RunBus.CpState.PENDING && it.missedCount == 0 && it.distM >= 0 }
            .map { it.cp.name }

        alert?.speak(getString(R.string.tts_summary, confirmed.size, missed.size, stats.timeStr))
        RunBus.update {
            it.copy(
                running = false, snaps = snaps, stats = stats,
                summary = RunBus.Summary(confirmed, missed, passedGps, stats)
            )
        }

        teardown()
    }

    private fun teardown() {
        try {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(locListener)
        } catch (_: Exception) { }
        scope?.cancel(); scope = null
        engine = null
        alert?.shutdown(); alert = null
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
        wakeLock = null
        stopForeground(true)
        notifMgr?.cancel(NID)
        stopSelf()
    }

    override fun onDestroy() {
        if (RunBus.state.value.running) {
            RunBus.update { it.copy(running = false) }
        }
        teardown()
    }
}
