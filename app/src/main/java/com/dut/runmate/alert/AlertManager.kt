package com.dut.runmate.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.dut.runmate.R
import com.dut.runmate.data.Prefs

/**
 * 提醒通道：预录语音（MediaPlayer）+ 提示音 + 震动。
 *
 * v1.8.8（用户拍板弃用 TTS 引擎）：
 *  - 【语音】彻底移除 TextToSpeech 依赖。旧方案在 v1.8.6/1.8.7 连续两轮修
 *    「TTS 引擎不可用」：默认引擎缺失、无中文语音数据、ROM 差异……本质上是
 *    把命运交给系统里有没有装引擎。改为 9 条预录语音（res/raw/voice_*.ogg，
 *    沙箱 z-ai TTS 生成 + loudnorm -16LUFS 归一 + Ogg Vorbis q3），随 APK
 *    打包、App 自包含——任何 ROM 上行为完全一致，「引擎」类问题从根上消失。
 *    播放走 MediaPlayer + USAGE_MEDIA（跟随媒体音量；导航通道默认极小是
 *    v1.8.6 已确认的旧病根，预录语音沿用该属性）。播放失败（解码异常等，
 *    极罕见）如实记 voiceState 并降级提示音，自检对话框如实展示。
 *
 * v1.8.7 保留（震动通道）：
 *  - 震动调用挂 USAGE_NOTIFICATION 属性（30+ VibrationAttributes / 26~29、
 *    24~25 AudioAttributes），归入系统「通知震动」通道；旧 UNKNOWN 用法在
 *    部分 ROM 的静音/勿扰/触感策略下被静默吞掉（「日志显示触发、手上无感」
 *    的根因）。vibState 暴露真实结果，不再写死「已触发」。
 *  - 测试演示用独立强波形（vibrateDemo），三档波形再加长。
 *
 * v1.8.6 保留：
 *  - 提示音按实际音量选 STREAM_MUSIC / STREAM_NOTIFICATION 兜底。
 */
class AlertManager(private val ctx: Context, private val prefs: Prefs) {

    enum class Kind { INFO, OK, WARN }

    /**
     * v1.8.8：预录语音片段。res/raw 打包、随装随用，无任何系统依赖。
     * 文案即 gen_voice_clips.sh 的 CLIPS 表（改动语音 = 重新生成 ogg）。
     * v1.8.13：scn = 分场景开关的偏好键（设置页「分场景开关」矩阵行）。
     * v1.8.15：新增 DONE（打卡结束播报——官方打卡距离 ≥3km 达标时播报）。
     */
    enum class Voice(val res: Int, val scn: String) {
        START(R.raw.voice_start, "start"),               // 跑步已开始
        APPROACH(R.raw.voice_approach, "approach"),     // 前方打卡点，请准备
        ENTER(R.raw.voice_enter, "enter"),               // 已进入打卡检测区
        OK(R.raw.voice_ok, "ok"),                         // 打卡成功
        UNCONFIRMED(R.raw.voice_unconfirmed, "unconfirmed"), // 提醒，打卡尚未确认
        MISSED(R.raw.voice_missed, "missed"),             // 注意，未确认打卡，请减速返回或留意手机
        PASS_GPS(R.raw.voice_pass_gps, "pass_gps"),     // 已通过打卡点，请自行确认打卡
        API_ERR(R.raw.voice_api_err, "api_err"),         // 注意，暂时无法核对打卡
        END(R.raw.voice_end, "end"),                       // 本次跑步结束
        DONE(R.raw.voice_done, "done")                     // 打卡距离已达三公里，打卡完成
    }

    /**
     * v1.8.8：预录语音播放结果（空串 = 最近一次播放成功；非空 = 失败原因：
     * 开关未开启 / 播放异常：X）。设置页自检对话框如实展示。
     */
    @Volatile
    var voiceState: String = "未播放"
        private set

    /**
     * v1.8.7：震动真实结果（空串 = 最近一次下发成功；非空 = 失败原因：
     * 开关未开启 / 未获取震动服务 / 设备无震动模块 / 震动调用异常：X）。
     */
    @Volatile
    var vibState: String = "未触发"
        private set

    private var tone: ToneGenerator? = null
    private var toneStream = -1
    private var player: MediaPlayer? = null
    private var vib: Vibrator? = null

    init {
        val appCtx = ctx.applicationContext
        val vm = if (Build.VERSION.SDK_INT >= 31) {
            (appCtx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appCtx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vib = vm
    }

    /**
     * 播放一条预录语音（USAGE_MEDIA 属性，跟媒体音量）。
     * v1.8.13：双层开关——总开关（tts）× 分场景开关（scnv_<scn>，默认全开）；
     * 两层都开才播。资源以 Ogg Vorbis 打包（aapt 对 .ogg 默认 STORED，
     * openRawResourceFd 可直接拿 fd）；万一资源被压缩打包拿不到 fd，退资源
     * Uri 再试。失败如实记 voiceState 并降级提示音——跑步场景宁可多响一声别漏提醒。
     */
    fun voice(v: Voice) {
        if (!prefs.ttsEnabled) { voiceState = "总开关未开启"; return }
        if (!prefs.scnVoiceOn(v.scn)) { voiceState = "该场景语音已关闭"; return }
        playRaw(v)
    }

    /** v1.8.13：直通播放（跳过总开关/场景开关）——仅自检 testAlert 用，
     * 保证「测试提醒」不受用户关掉某场景开关影响，三通道诊断结果始终可信 */
    private fun playRaw(v: Voice) {
        try {
            player?.release()
            player = null
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
            val afd = try {
                ctx.resources.openRawResourceFd(v.res)
            } catch (_: Throwable) {
                null
            }
            if (afd != null) {
                try {
                    p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                } finally {
                    try { afd.close() } catch (_: Throwable) { }
                }
            } else {
                p.setDataSource(
                    ctx.applicationContext,
                    android.net.Uri.parse("android.resource://${ctx.packageName}/${v.res}"))
            }
            p.setOnCompletionListener { mp ->
                try { mp.release() } catch (_: Throwable) { }
                if (player === mp) player = null
            }
            p.setOnErrorListener { mp, _, _ ->
                try { mp.release() } catch (_: Throwable) { }
                if (player === mp) player = null
                true
            }
            p.prepare()
            p.start()
            player = p
            voiceState = ""
        } catch (e: Throwable) {
            voiceState = "播放异常（${e.javaClass.simpleName}）"
            chime(Kind.INFO)
        }
    }

    fun chime(kind: Kind) {
        if (!prefs.chimeEnabled) return
        try {
            // v1.8.6：按实际音量选通道——媒体音量 0 时切 STREAM_NOTIFICATION
            val want = currentChimeStream()
            if (tone == null || toneStream != want) {
                try { tone?.release() } catch (_: Exception) { }
                toneStream = want
                tone = ToneGenerator(toneStream, 100)
            }
            val t = tone ?: return
            when (kind) {
                Kind.INFO -> t.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
                Kind.OK -> t.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
                Kind.WARN -> t.startTone(ToneGenerator.TONE_PROP_NACK, 600)
            }
        } catch (_: Exception) {
            try { tone?.release() } catch (_: Exception) { }
            tone = null; toneStream = -1
        }
    }

    private fun currentChimeStream(): Int {
        return try {
            val am = ctx.applicationContext
                .getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) AudioManager.STREAM_MUSIC
            else AudioManager.STREAM_NOTIFICATION
        } catch (_: Throwable) {
            AudioManager.STREAM_MUSIC
        }
    }

    /**
     * v1.8.13：vibrate 增加场景开关（scn = Voice.scn；null = 不分场景的通用
     * 调用，如演示波形）。双层开关：总开关（vibrate）× 分场景开关（scnvi_<scn>）。
     */
    fun vibrate(kind: Kind, scn: String? = null): Boolean {
        if (!prefs.vibrateEnabled) { vibState = "总开关未开启"; return false }
        if (scn != null && !prefs.scnVibOn(scn)) { vibState = "该场景震动已关闭"; return false }
        val v = vib ?: run { vibState = "未获取震动服务"; return false }
        val has = try { v.hasVibrator() } catch (_: Throwable) { true }
        if (!has) { vibState = "设备无震动模块"; return false }
        // v1.8.7：三档波形再加长（握持/臂包/口袋场景 100ms 级无感）
        val timings = when (kind) {
            Kind.INFO -> longArrayOf(0, 150)
            Kind.OK -> longArrayOf(0, 250, 150, 250)
            Kind.WARN -> longArrayOf(0, 600, 180, 600, 180, 900)
        }
        return doVibrate(timings)
    }

    /** v1.8.7：测试演示专用强波形——双长震+收尾，握在手里/放桌上都明显 */
    fun vibrateDemo(): Boolean {
        if (!prefs.vibrateEnabled) { vibState = "开关未开启"; return false }
        val v = vib ?: run { vibState = "未获取震动服务"; return false }
        val has = try { v.hasVibrator() } catch (_: Throwable) { true }
        if (!has) { vibState = "设备无震动模块"; return false }
        return doVibrate(longArrayOf(0, 350, 160, 350, 160, 700))
    }

    /**
     * v1.8.7 统一震动入口：按 API 分级挂 USAGE_NOTIFICATION 属性。
     * UNKNOWN 用法的震动在部分 ROM 的静音/勿扰/触感策略下被静默吞掉
     * （调用成功、手上无感）；归入「通知震动」通道后由系统按用户的通知
     * 震动设置处理，行为确定可依赖。成功置 vibState=""，失败记录原因。
     */
    private fun doVibrate(timings: LongArray): Boolean {
        val v = vib ?: run { vibState = "未获取震动服务"; return false }
        return try {
            when {
                Build.VERSION.SDK_INT >= 30 -> v.vibrate(
                    VibrationEffect.createWaveform(timings, -1),
                    VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_NOTIFICATION)
                        .build())

                Build.VERSION.SDK_INT >= 26 -> v.vibrate(
                    VibrationEffect.createWaveform(timings, -1),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())

                else -> @Suppress("DEPRECATION") v.vibrate(
                    timings, -1,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
            }
            vibState = ""
            true
        } catch (t: Throwable) {
            vibState = "震动调用异常：${t.javaClass.simpleName}"
            false
        }
    }

    /** v1.8.8：三通道演示——预录语音（打卡成功）+ 提示音 + 强震动。
     *  v1.8.13：语音改走 playRaw 直通（不检查场景开关，只看真实通道健康度），
     *  避免用户关掉某场景语音后自检误报「开关未开启」误导排查。 */
    fun testAlert() {
        playRaw(Voice.OK)
        chime(Kind.OK)
        vibrateDemo()
    }

    fun shutdown() {
        try { player?.release() } catch (_: Throwable) { }
        player = null
        voiceState = "未播放"
        try { tone?.release() } catch (_: Exception) { }
        tone = null; toneStream = -1
        vibState = "未触发"
    }
}
