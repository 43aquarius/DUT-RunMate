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
import com.dut.runmate.net.Http
import com.dut.runmate.run.DetectEngine
import com.dut.runmate.run.RunBus
import kotlinx.coroutines.*
import kotlin.math.roundToInt

/**
 * 前台定位服务：GPS 跟踪 + 检测状态机 + 接口轮询 + 语音/震动/提示音提醒。
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

    private val locListener = LocationListener { loc -> onFix(loc) }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_STOP -> { stopRun(); return START_NOT_STICKY }
            else -> if (!RunBus.state.value.running) startRun()
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
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        engine = DetectEngine(prefs, ::onEvent).also {
            it.apiMode = prefs.apiEnabled
            it.reset(store.all())
        }

        startAt = SystemClock.elapsedRealtime()
        sessionMeters = 0.0; fixCount = 0; lastFix = null

        // 前台服务 + 常驻通知
        val n = buildNotification("定位中…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NID, n)
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

    private fun startPoller() {
        scope?.launch(Dispatchers.Main) {
            var lastAnchor = 0L
            while (isActive) {
                delay(prefs.pollInterval * 1000L)
                if (!prefs.apiEnabled) continue
                val eng = engine ?: break
                val prof = apiStore.distanceProfile() ?: continue
                if (prof.url.isBlank()) continue
                val need = eng.needsPoll()
                val anchorDue = SystemClock.elapsedRealtime() - lastAnchor > 15_000
                if (!need && !anchorDue) continue
                val resp = Http.call(prof, prefs.apiToken)
                if (resp.error != null) {
                    RunBus.update { it.copy(apiErr = "网络错误: ${resp.error}") }
                    continue
                }
                if (resp.code !in 200..299) {
                    RunBus.update { it.copy(apiErr = "HTTP ${resp.code}") }
                    continue
                }
                lastAnchor = SystemClock.elapsedRealtime()
                val v = Http.extractDouble(resp.body, prof.distPath)
                if (v != null) {
                    RunBus.update { it.copy(apiDistance = v, apiErr = null) }
                    eng.onApiDistance(v)
                } else {
                    RunBus.update { it.copy(apiErr = "未能提取: ${prof.distPath}") }
                }
            }
        }
    }

    private fun onFix(loc: Location) {
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
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(pi)
            .addAction(0, getString(R.string.notif_stop), stopPi)
            .build()
    }

    private fun updateNotificationText(nearest: RunBus.CpSnap?, snaps: List<RunBus.CpSnap>) {
        val ok = snaps.count { it.state == RunBus.CpState.CONFIRMED }
        val text = when {
            snaps.any { it.state == RunBus.CpState.MISSED } -> "⚠ 有点位未确认，请留意"
            snaps.any { it.state == RunBus.CpState.IN_ZONE } -> "检测区内，等待打卡确认…"
            nearest != null -> "最近：${nearest.cp.name} ${nearest.distM} m · 已确认 $ok/${snaps.size}"
            else -> "定位中…"
        }
        notifMgr?.notify(NID, buildNotification(text))
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
