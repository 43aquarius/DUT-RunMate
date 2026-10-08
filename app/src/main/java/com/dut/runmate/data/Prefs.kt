package com.dut.runmate.data

import android.content.Context
import android.content.SharedPreferences

/** 全局设置 */
class Prefs private constructor(private val sp: SharedPreferences) {

    var defaultRadius: Int
        get() = sp.getInt("defaultRadius", 25)
        set(v) = sp.edit().putInt("defaultRadius", v).apply()
    var approachDist: Int
        get() = sp.getInt("approachDist", 60)
        set(v) = sp.edit().putInt("approachDist", v).apply()
    var confirmDelta: Int
        get() = sp.getInt("confirmDelta", 3)
        set(v) = sp.edit().putInt("confirmDelta", v).apply()
    var pollInterval: Int
        get() = sp.getInt("pollInterval", 3)
        set(v) = sp.edit().putInt("pollInterval", v).apply()
    var graceSec: Int
        get() = sp.getInt("graceSec", 12)
        set(v) = sp.edit().putInt("graceSec", v).apply()

    var ttsEnabled: Boolean
        get() = sp.getBoolean("tts", true)
        set(v) = sp.edit().putBoolean("tts", v).apply()
    var chimeEnabled: Boolean
        get() = sp.getBoolean("chime", true)
        set(v) = sp.edit().putBoolean("chime", v).apply()
    var vibrateEnabled: Boolean
        get() = sp.getBoolean("vibrate", true)
        set(v) = sp.edit().putBoolean("vibrate", v).apply()

    var apiEnabled: Boolean
        get() = sp.getBoolean("apiEnabled", false)
        set(v) = sp.edit().putBoolean("apiEnabled", v).apply()
    var apiToken: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v).apply()

    var tileSat: Boolean
        get() = sp.getBoolean("tileSat", true)
        set(v) = sp.edit().putBoolean("tileSat", v).apply()
    var coordGcj: Boolean
        get() = sp.getBoolean("coordGcj", false)
        set(v) = sp.edit().putBoolean("coordGcj", v).apply()

    var mapHintShown: Boolean
        get() = sp.getBoolean("mapHintShown", false)
        set(v) = sp.edit().putBoolean("mapHintShown", v).apply()

    companion object {
        @Volatile private var inst: Prefs? = null
        fun get(ctx: Context): Prefs =
            inst ?: synchronized(this) {
                inst ?: Prefs(ctx.getSharedPreferences("runmate", Context.MODE_PRIVATE)).also { inst = it }
            }
    }
}
