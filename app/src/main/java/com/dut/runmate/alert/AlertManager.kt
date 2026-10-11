package com.dut.runmate.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import com.dut.runmate.data.Prefs
import java.util.Locale

/**
 * 提醒通道：TTS 语音（耳机/扬声器）+ 提示音 + 震动。
 *
 * v1.8.6 彻底重修（用户实报「语音播报/提示音/震动全部无效」）：
 *  - 【语音】TTS 音频属性 USAGE_ASSISTANCE_NAVIGATION_GUIDANCE → USAGE_MEDIA：
 *    旧版走系统「导航」音量通道，绝大多数设备该通道默认极小甚至为 0，且用户
 *    根本不会去调——表现为「永远听不到语音」。改为跟随媒体音量（跑步听歌的
 *    用户媒体音量必然开着）。中文语言包缺失（LANG_MISSING_DATA）不再谎报
 *    就绪（旧版无条件 ttsReady=true，结果 speak 入队无声），改判「未就绪」
 *    并自动降级提示音+震动；ttsState 暴露诊断给设置页「测试提醒」。
 *  - 【提示音】ToneGenerator 固定 STREAM_MUSIC → 按实际音量两级兜底：
 *    媒体音量>0 用 STREAM_MUSIC，为 0（多数静音用户）切 STREAM_NOTIFICATION
 *    （通知通道几乎总开着且不受媒体音量影响）；相对音量 80→100，时长加长。
 *  - 【震动】三档波形全部加长（旧版 INFO 35ms/OK 60ms 在口袋/臂包里几乎
 *    无感）；API 24/25 走旧版 vibrate(long[],int)——旧代码在 24/25 上调
 *    VibrationEffect 重载是 NoSuchMethodError（Error），catch(Exception)
 *    捕不住，整次提醒直接丢失。
 *  - 新增公共 testAlert()（设置页当场验证三通道用）。
 */
class AlertManager(private val ctx: Context, private val prefs: Prefs) {

    enum class Kind { INFO, OK, WARN }

    /**
     * TTS 引擎诊断（空串 = 就绪）。设置页「测试提醒」显示；
     * 常见值：未初始化 / 初始化中… / 无中文语音数据… / 无 TTS 引擎…
     */
    @Volatile
    var ttsState: String = "未初始化"
        private set

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var tone: ToneGenerator? = null
    private var toneStream = -1
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

    fun initTts() {
        if (tts != null) return
        ttsState = "初始化中…"
        // v1.6.0 保留：TTS 引擎初始化整体隔离——无 TTS 引擎/引擎损坏的 ROM 上仅静默降级
        tts = try {
            TextToSpeech(ctx.applicationContext) { status ->
                try {
                    if (status != TextToSpeech.SUCCESS) {
                        ttsReady = false
                        ttsState = "TTS 引擎不可用（status=$status），语音降级为提示音"
                        return@TextToSpeech
                    }
                    val t = tts ?: return@TextToSpeech
                    // v1.8.6：检查语言包——setLanguage 返回 <0（缺数据/不支持）
                    // 时旧版仍置 ttsReady=true，speak() 全部无声；现改为如实判定
                    var lang = t.setLanguage(Locale.SIMPLIFIED_CHINESE)
                    if (lang < 0) lang = t.setLanguage(Locale.CHINA)
                    if (lang < 0) {
                        ttsReady = false
                        ttsState = "无中文语音数据（系统 TTS 缺语言包），语音降级为提示音"
                        return@TextToSpeech
                    }
                    applyTtsAudio(t)
                    ttsReady = true
                    ttsState = ""
                } catch (e: Throwable) {
                    ttsReady = false
                    ttsState = "TTS 初始化异常（${e.javaClass.simpleName}），语音降级为提示音"
                }
            }
        } catch (e: Throwable) {
            ttsState = "无 TTS 引擎（${e.javaClass.simpleName}），语音降级为提示音"
            null
        }
    }

    /** v1.8.6：USAGE_MEDIA——语音跟随媒体音量（导航通道默认音量极小是「无声」主因之一） */
    private fun applyTtsAudio(t: TextToSpeech) {
        try {
            t.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        } catch (_: Throwable) { }
    }

    fun shutdown() {
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) { }
        tts = null; ttsReady = false; ttsState = "未初始化"
        try { tone?.release() } catch (_: Exception) { }
        tone = null; toneStream = -1
    }

    fun speak(text: String) {
        if (!prefs.ttsEnabled) return
        val t = tts
        if (t != null && ttsReady) {
            // v1.6.0 保留：部分 ROM 的 TTS 引擎中途死亡时 speak() 抛异常，降级提示音
            try {
                t.speak(text, TextToSpeech.QUEUE_ADD, null, "runmate_${System.nanoTime()}")
            } catch (_: Throwable) {
                ttsReady = false
                ttsState = "TTS 引擎异常退出，语音降级为提示音"
                chime(Kind.INFO)
            }
        } else {
            chime(Kind.INFO)
        }
    }

    fun chime(kind: Kind) {
        if (!prefs.chimeEnabled) return
        try {
            // v1.8.6：按实际音量选通道——媒体音量 0 时旧版完全无声；
            // 通知通道（STREAM_NOTIFICATION）不受媒体音量影响，几乎总可闻
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

    fun vibrate(kind: Kind) {
        if (!prefs.vibrateEnabled) return
        val v = vib ?: return
        // v1.8.6：波形加长——口袋/臂包场景旧版 35ms/60ms 基本无感
        val timings = when (kind) {
            Kind.INFO -> longArrayOf(0, 100)
            Kind.OK -> longArrayOf(0, 180, 140, 180)
            Kind.WARN -> longArrayOf(0, 500, 150, 500, 150, 800)
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(timings, -1))
            } else {
                // v1.8.6：API 24/25 兼容——旧代码无条件调 VibrationEffect 重载，
                // 24/25 上是 NoSuchMethodError（Error），catch(Exception) 捕不住
                @Suppress("DEPRECATION")
                v.vibrate(timings, -1)
            }
        } catch (_: Throwable) { }
    }

    /** v1.8.6：一次性三通道演示（设置页「测试提醒」用），返回后自动延迟释放 */
    fun testAlert(text: String) {
        speak(text)
        chime(Kind.OK)
        vibrate(Kind.OK)
    }
}
