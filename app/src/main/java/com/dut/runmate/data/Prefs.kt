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

    /**
     * v1.8.13：分场景提醒开关（用户要求每种提醒的语音/震动可独立开关）。
     * 键 = AlertManager.Voice.scn（start/approach/enter/ok/unconfirmed/
     * missed/pass_gps/api_err/end/done）；总开关（tts/vibrate）之上再叠加
     * 一层场景粒度——两层都开才触发。
     * v1.8.15 新默认（用户拍板）：语音全部默认开；震动仅「打卡成功（ok）/
     * 区内未确认（unconfirmed）/漏卡警告（missed）」三场景默认开，其余默认关。
     * 显式设置过的键不受影响（getBoolean 键存在即返回用户值）。
     */
    fun scnVoiceOn(scn: String): Boolean = sp.getBoolean("scnv_$scn", true)
    fun setScnVoiceOn(scn: String, v: Boolean) {
        sp.edit().putBoolean("scnv_$scn", v).apply()
    }
    fun scnVibOn(scn: String): Boolean = sp.getBoolean("scnvi_$scn", scn in VIB_DEFAULT_ON)
    fun setScnVibOn(scn: String, v: Boolean) {
        sp.edit().putBoolean("scnvi_$scn", v).apply()
    }

    /** v1.8.15：默认开启震动的场景（用户要求：只留打卡成功/区内未确认/漏卡警告） */
    private val VIB_DEFAULT_ON = setOf("ok", "unconfirmed", "missed")

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

    /**
     * v1.8.1：WebVPN 用户真实隧道前缀（wengine 路径 /http-8081/<前缀>/ 中的前缀段）。
     * 实测（用户报 http://0/mobilenew/ 错误页）：门户登录会话存在时，占位段 "0"
     * 不被网关接受，会被当作目标 host 解析（→ http://0/mobilenew/ 不可达）。
     * 「前缀段不参与校验」的旧结论只在未登录（固定 302 /login）阶段成立。
     * 前缀在门户资源列表扫描 / H5 捕获成功时自动记录，供下次直达与请求时替换。
     */
    var tunnelPrefix: String
        get() = sp.getString("tunnelPrefix", "") ?: ""
        set(v) = sp.edit().putString("tunnelPrefix", v).apply()

    var tileSat: Boolean
        get() = sp.getBoolean("tileSat", true)
        set(v) = sp.edit().putBoolean("tileSat", v).apply()
    var coordGcj: Boolean
        get() = sp.getBoolean("coordGcj", false)
        set(v) = sp.edit().putBoolean("coordGcj", v).apply()

    var mapHintShown: Boolean
        get() = sp.getBoolean("mapHintShown", false)
        set(v) = sp.edit().putBoolean("mapHintShown", v).apply()

    /**
     * v1.8.15：跑步页点位分组筛选。"auto"（默认，自动选中离当前位置平均
     * 距离最近的一组，开跑时锁定）/ "all"（全部）/ 其余值 = 分组名（锁定该组）。
     */
    var runFilter: String
        get() = sp.getString("runFilter", "auto") ?: "auto"
        set(v) = sp.edit().putString("runFilter", v).apply()

    // 更新（v1.8.13：只走 GitHub Release，见 Updater.GITHUB_RELEASES_PAGE）
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
