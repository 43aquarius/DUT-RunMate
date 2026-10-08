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
 */
class AlertManager(private val ctx: Context, private val prefs: Prefs) {

    enum class Kind { INFO, OK, WARN }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var tone: ToneGenerator? = null
    private var vib: Vibrator? = null

    init {
        val vm = if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vib = vm
    }

    fun initTts() {
        if (tts != null) return
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                try {
                    tts?.language = Locale.SIMPLIFIED_CHINESE
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    ttsReady = true
                } catch (_: Exception) {
                    ttsReady = false
                }
            }
        }
    }

    fun shutdown() {
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) { }
        tts = null; ttsReady = false
        try { tone?.release() } catch (_: Exception) { }
        tone = null
    }

    fun speak(text: String) {
        if (!prefs.ttsEnabled) return
        val t = tts
        if (t != null && ttsReady) {
            t.speak(text, TextToSpeech.QUEUE_ADD, null, "runmate_${System.nanoTime()}")
        } else {
            chime(Kind.INFO)
        }
    }

    fun chime(kind: Kind) {
        if (!prefs.chimeEnabled) return
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            val t = tone ?: return
            when (kind) {
                Kind.INFO -> t.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                Kind.OK -> {
                    t.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
                }
                Kind.WARN -> t.startTone(ToneGenerator.TONE_PROP_NACK, 400)
            }
        } catch (_: Exception) { }
    }

    fun vibrate(kind: Kind) {
        if (!prefs.vibrateEnabled) return
        val v = vib ?: return
        try {
            val timings = when (kind) {
                Kind.INFO -> longArrayOf(0, 35)
                Kind.OK -> longArrayOf(0, 60, 90, 60)
                Kind.WARN -> longArrayOf(0, 400, 150, 400, 150, 700)
            }
            v.vibrate(VibrationEffect.createWaveform(timings, -1))
        } catch (_: Exception) { }
    }
}
