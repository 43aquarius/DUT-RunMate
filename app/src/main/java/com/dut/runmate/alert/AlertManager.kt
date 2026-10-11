package com.dut.runmate.alert

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import com.dut.runmate.data.Prefs
import java.util.Locale

/**
 * 提醒通道：TTS 语音（耳机/扬声器）+ 提示音 + 震动。
 *
 * v1.8.7（用户实报「提示音有用，语音引擎异常/TTS 引擎不可用；震动显示触发
 * 了但手机没震」）：
 *  - 【语音】旧实现只试系统默认引擎，一次失败即放弃——国产 ROM 默认引擎
 *    缺失/损坏/无中文语音数据时（MIUI、ColorOS 等常见）语音通道直接判死。
 *    改为「引擎链」：系统默认 → 已知引擎按优先级（Google → 小爱 → 讯飞 →
 *    百度 → 三星）→ 扫描出的其余引擎，逐个尝试（初始化失败或缺中文语音
 *    数据自动换下一个）；ttsEngineLabel 暴露当前引擎名供自检展示。
 *  - 【震动】旧调用不带属性（系统归为 UNKNOWN 用法），部分 ROM 在静音/
 *    勿扰/触感策略下直接吞掉——表现「日志显示触发、手上无震感」。
 *    现按 API 分级挂 USAGE_NOTIFICATION 属性（30+ VibrationAttributes /
 *    26~29、24~25 AudioAttributes），归入系统「通知震动」通道；vibState
 *    暴露真实结果（无震动模块/调用异常/成功），不再写死「已触发」。
 *  - 测试演示用独立强波形（vibrateDemo），三档波形再加长。
 *
 * v1.8.6 保留（三通道重修基线）：
 *  - TTS 音频属性 USAGE_MEDIA（语音跟媒体音量，导航通道默认极小是旧病根）；
 *    语言包缺失如实判「未就绪」，不谎报。
 *  - 提示音按实际音量选 STREAM_MUSIC / STREAM_NOTIFICATION 兜底。
 *  - API 24/25 震动走 vibrate(long[],int,AudioAttributes) 旧重载。
 */
class AlertManager(private val ctx: Context, private val prefs: Prefs) {

    enum class Kind { INFO, OK, WARN }

    /**
     * TTS 诊断（空串 = 就绪）。设置页「测试提醒」显示。
     */
    @Volatile
    var ttsState: String = "未初始化"
        private set

    /** v1.8.7：当前就绪引擎的显示名（"Google 文字转语音" 等），自检展示用 */
    @Volatile
    var ttsEngineLabel: String = ""
        private set

    /**
     * v1.8.7：震动真实结果（空串 = 最近一次下发成功；非空 = 失败原因：
     * 开关未开启 / 未获取震动服务 / 设备无震动模块 / 震动调用异常：X）。
     * 自检对话框据此如实展示，不再静态写「已触发」。
     */
    @Volatile
    var vibState: String = "未触发"
        private set

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var tone: ToneGenerator? = null
    private var toneStream = -1
    private var vib: Vibrator? = null

    // ---------- v1.8.7：TTS 引擎链 ----------
    private val engineQueue = mutableListOf<String?>()
    private var engineIdx = 0

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
        buildEngineQueue()
        tryNextEngine()
    }

    /**
     * 组装引擎尝试队列：①系统默认（用户/ROM 设定的默认引擎）②已知引擎按
     * 优先级（仅装了的）③扫描出的其余引擎兜底（覆盖未知 ROM 自带引擎）。
     * 引擎发现 = PackageManager 查 TTS_SERVICE 系统服务（与系统选择引擎
     * 的机制同源），不依赖写死包名是否预装。
     */
    private fun buildEngineQueue() {
        engineIdx = 0
        engineQueue.clear()
        engineQueue.add(null)                       // null = 系统默认引擎
        val installed = linkedSetOf<String>()
        try {
            val pm = ctx.packageManager
            val svcs = pm.queryIntentServices(
                Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            for (ri in svcs) {
                try { ri.serviceInfo?.packageName?.let { installed.add(it) } } catch (_: Throwable) { }
            }
        } catch (_: Throwable) { }
        val defPkg = try {
            Settings.Secure.getString(ctx.contentResolver, "tts_default_synth")
        } catch (_: Throwable) { null }
        for (pkg in KNOWN_TTS_ENGINES.keys) {
            if (pkg == defPkg) continue             // 默认引擎已在队首
            if (pkg in installed && pkg !in engineQueue) engineQueue.add(pkg)
        }
        for (pkg in installed.sorted()) {           // 未知 ROM 引擎殿后
            if (pkg != defPkg && pkg !in engineQueue) engineQueue.add(pkg)
        }
    }

    private fun tryNextEngine() {
        if (engineIdx >= engineQueue.size) {
            ttsReady = false
            ttsEngineLabel = ""
            ttsState = "已尝试 ${engineIdx} 个引擎均不可用，语音降级为提示音；" +
                "可在系统「文字转语音（TTS）输出」设置安装/选择引擎后重试"
            return
        }
        val pkg = engineQueue[engineIdx++]
        val inst: TextToSpeech? = try {
            TextToSpeech(ctx.applicationContext, { status -> onEngineInit(pkg, status) }, pkg)
        } catch (_: Throwable) { null }             // 构造即抛（引擎损坏/被停用）
        if (inst == null) {
            tryNextEngine()                          // 同步失败立即换下一个
            return
        }
        tts = inst
    }

    private fun onEngineInit(pkg: String?, status: Int) {
        try {
            if (status != TextToSpeech.SUCCESS) {
                engineFail(label(pkg) + " 初始化失败(status=$status)")
                return
            }
            val t = tts ?: return
            // 语言包如实判定（v1.8.6 基线）：缺数据/不支持 → 换下一个引擎
            var lang = t.setLanguage(Locale.SIMPLIFIED_CHINESE)
            if (lang < 0) lang = t.setLanguage(Locale.CHINA)
            if (lang < 0) {
                engineFail(label(pkg) + " 无中文语音数据")
                return
            }
            applyTtsAudio(t)
            ttsReady = true
            ttsEngineLabel = label(pkg)
            ttsState = ""
        } catch (e: Throwable) {
            engineFail("初始化异常（${e.javaClass.simpleName}）")
        }
    }

    /** 当前引擎失败：释放实例，链上还有候选就继续，否则落最终诊断 */
    private fun engineFail(reason: String) {
        try { tts?.stop(); tts?.shutdown() } catch (_: Throwable) { }
        tts = null
        if (engineIdx >= engineQueue.size) {
            ttsReady = false
            ttsEngineLabel = ""
            ttsState = "TTS 不可用：$reason。语音降级为提示音；" +
                "可在系统「文字转语音（TTS）输出」安装语音引擎/中文语音数据后重试"
        } else {
            tryNextEngine()
        }
    }

    /** 引擎显示名：已知表 → 应用真实 label → 包名 */
    private fun label(pkg: String?): String {
        if (pkg == null) return "系统默认引擎"
        KNOWN_TTS_ENGINES[pkg]?.let { return it }
        return try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))?.toString() ?: pkg
        } catch (_: Throwable) { pkg }
    }

    /** v1.8.6：USAGE_MEDIA——语音跟随媒体音量 */
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
        tts = null; ttsReady = false; ttsState = "未初始化"; ttsEngineLabel = ""
        engineQueue.clear(); engineIdx = 0
        try { tone?.release() } catch (_: Exception) { }
        tone = null; toneStream = -1
        vibState = "未触发"
    }

    fun speak(text: String) {
        if (!prefs.ttsEnabled) return
        val t = tts
        if (t != null && ttsReady) {
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
            // v1.8.6：按实际音量选通道——媒体音量 0 时切 STREAM_NOTIFICATION
            val want = currentChimeStream()
            if (tone == null || toneStream != want) {
                try { tone?.release() } catch (_: Throwable) { }
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

    fun vibrate(kind: Kind): Boolean {
        if (!prefs.vibrateEnabled) { vibState = "开关未开启"; return false }
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

    /** v1.8.6：三通道演示；v1.8.7 震动改专用强波形 */
    fun testAlert(text: String) {
        speak(text)
        chime(Kind.OK)
        vibrateDemo()
    }

    companion object {
        /** v1.8.7：已知引擎优先级表（仅作排序，装没装以 PackageManager 为准） */
        private val KNOWN_TTS_ENGINES = linkedMapOf(
            "com.google.android.tts" to "Google 文字转语音",
            "com.xiaomi.mibrain.speech" to "小爱语音引擎",
            "com.iflytek.speechsuite" to "讯飞语音+",
            "com.baidu.duersdk.opensdk" to "百度语音引擎",
            "com.samsung.SMT" to "三星 TTS"
        )
    }
}
