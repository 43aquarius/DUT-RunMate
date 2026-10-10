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
        get() = sp.getInt("graceSec", 15)
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
    /** v1.4.0：{cookie} 占位符（WebVPN 隧道场景携带 wengine_vpn_ticket 等 Cookie） */
    var apiCookie: String
        get() = sp.getString("apiCookie", "") ?: ""
        set(v) = sp.edit().putString("apiCookie", v).apply()

    /** v1.4.0：微哨（i大工）账号会话 —— 登录后自动配置用 */
    var wsUserId: String
        get() = sp.getString("wsUserId", "") ?: ""
        set(v) = sp.edit().putString("wsUserId", v).apply()
    var wsSkey: String
        get() = sp.getString("wsSkey", "") ?: ""
        set(v) = sp.edit().putString("wsSkey", v).apply()
    var wsName: String
        get() = sp.getString("wsName", "") ?: ""
        set(v) = sp.edit().putString("wsName", v).apply()
    var wsNumber: String
        get() = sp.getString("wsNumber", "") ?: ""
        set(v) = sp.edit().putString("wsNumber", v).apply()

    /**
     * v1.5.0：登录信息本地保存 —— 密码随会话一起落盘（Base64 混淆，仅存本机），
     * 下次打开「接口」页自动回填，会话过期时一键重新登录。
     * 通过 wsPwdBlank 清除（长按密码框清空）。
     */
    var wsPwdSaved: String
        get() = sp.getString("wsPwd", "") ?: ""
        set(v) = sp.edit().putString("wsPwd", v).apply()
    fun wsPwdDecoded(): String = try {
        if (wsPwdSaved.isBlank()) ""
        else String(android.util.Base64.decode(wsPwdSaved, android.util.Base64.NO_WRAP), Charsets.UTF_8)
    } catch (_: Exception) { "" }

    /** v1.5.1：CAS 统一认证会话（CASTGC=TGT-xxx，sso.dlut.edu.cn 全局会话）。
     *  主登录通道产物，注入 H5 捕获页 WebView 实现 CAS 域免密。 */
    var casTgt: String
        get() = sp.getString("casTgt", "") ?: ""
        set(v) = sp.edit().putString("casTgt", v).apply()

    /**
     * v1.7.0：多账号列表（JSON 数组，主键 = 学号，结构见 data/Accounts.kt）。
     * 活跃账号仍存于上方旧字段（wsNumber/apiToken/…），切换 = 快照回列表 + 写入目标，
     * 既有跑步轮询/发送测试/H5 捕获逻辑零改动。
     */
    var accountsJson: String
        get() = sp.getString("accountsJson", "") ?: ""
        set(v) = sp.edit().putString("accountsJson", v).apply()

    var tileSat: Boolean
        get() = sp.getBoolean("tileSat", true)
        set(v) = sp.edit().putBoolean("tileSat", v).apply()
    var coordGcj: Boolean
        get() = sp.getBoolean("coordGcj", false)
        set(v) = sp.edit().putBoolean("coordGcj", v).apply()

    var mapHintShown: Boolean
        get() = sp.getBoolean("mapHintShown", false)
        set(v) = sp.edit().putBoolean("mapHintShown", v).apply()

    // 更新（v1.5.0：服务器地址改为内置常量，见 Updater.SERVER_BASE；不再可自定义）
    var updAutoCheck: Boolean
        get() = sp.getBoolean("updAutoCheck", true)
        set(v) = sp.edit().putBoolean("updAutoCheck", v).apply()
    var updLastCheckMs: Long
        get() = sp.getLong("updLastCheckMs", 0L)
        set(v) = sp.edit().putLong("updLastCheckMs", v).apply()

    companion object {
        @Volatile private var inst: Prefs? = null
        fun get(ctx: Context): Prefs =
            inst ?: synchronized(this) {
                inst ?: Prefs(ctx.getSharedPreferences("runmate", Context.MODE_PRIVATE)).also { inst = it }
            }
    }
}
