package com.dut.runmate.data

import android.content.Context
import android.content.SharedPreferences

/** 全局设置 */
class Prefs private constructor(private val sp: SharedPreferences) {

    init {
        // v1.9.0：检测参数从整数滑杆改为手填小数（保留 1 位）——旧版存的是 Int，
        // getFloat 会 ClassCastException，首次构造时一次性迁移为 Float。
        // v1.9.2：confirmDelta 参数已删（学校固定点位每次打卡固定 +100/+200m），
        // 死键一并清理。
        val e = sp.edit()
        for (k in listOf("defaultRadius", "approachDist", "pollInterval", "graceSec")) {
            if (sp.all[k] is Int) {
                e.putFloat(k, (sp.all[k] as Int).toFloat())
            }
        }
        e.remove("confirmDelta")
        // v1.9.2：跑步页「自动」分组已删（用户需求）——历史存值 auto 一律迁移为 all
        if (sp.getString("runFilter", null) == "auto") {
            e.putString("runFilter", "all")
        }
        e.apply()
    }

    /** v1.9.0：各检测参数默认值（用户拍板：默认检测半径 10m、预告距离 20m）。
     * 「全部恢复默认」与 getter 共用这套常量，改默认只改这一处。
     * v1.9.2：确认增量阈值已删（固定点位每次打卡固定 +100/+200m，参数无意义），
     * 阈值内置在 DetectEngine.CONFIRM_DELTA_M。 */
    private object D {
        const val RADIUS = 10f
        const val APPROACH = 20f
        const val POLL = 3f
        const val GRACE = 15f
    }

    var defaultRadius: Float
        get() = sp.getFloat("defaultRadius", D.RADIUS)
        set(v) = sp.edit().putFloat("defaultRadius", v).apply()
    var approachDist: Float
        get() = sp.getFloat("approachDist", D.APPROACH)
        set(v) = sp.edit().putFloat("approachDist", v).apply()
    var pollInterval: Float
        get() = sp.getFloat("pollInterval", D.POLL)
        set(v) = sp.edit().putFloat("pollInterval", v).apply()
    var graceSec: Float
        get() = sp.getFloat("graceSec", D.GRACE)
        set(v) = sp.edit().putFloat("graceSec", v).apply()

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
     * v1.9.3：后台定位权限「只主动问一次」标记（用户需求：第一次打开 App 就
     * 申请全部权限）。配合 MainActivity.maybeAskBackgroundLocation 两段式申请：
     * 后台定位必须在前台定位授权后单独发起（Android 11+ 会静默拒绝同批申请），
     * 问过一次后不再重复弹窗（拒绝不影响跑步判定——前台服务定位仅需
     * 「使用期间」权限）。属于流程状态而非配置，不参与 resetAllConfig。
     */
    var bgPermAsked: Boolean
        get() = sp.getBoolean("bgPermAsked", false)
        set(v) = sp.edit().putBoolean("bgPermAsked", v).apply()

    /**
     * v1.9.0：跑步通知显示开关（用户需求：开始跑步后弹窗可自由选择是否出现在
     * 锁屏页面与状态栏）。锁屏关 = 通知渠道/通知 VISIBILITY_SECRET（锁屏不显示
     * 媒体卡片）；状态栏关 = 渠道 IMPORTANCE_MIN（不显示状态栏图标；Android
     * 对前台服务通知有系统级可见性要求，部分 ROM 仍可能显示）。均为默认开。
     * 渠道级设置只在渠道创建时生效——服务每次开跑会删旧渠道重建，改开关后
     * 下次开始跑步生效。
     */
    var notifLockscreen: Boolean
        get() = sp.getBoolean("notifLockscreen", true)
        set(v) = sp.edit().putBoolean("notifLockscreen", v).apply()
    var notifStatusbar: Boolean
        get() = sp.getBoolean("notifStatusbar", true)
        set(v) = sp.edit().putBoolean("notifStatusbar", v).apply()

    /**
     * v1.9.2：媒体卡片显示开关（用户实报：状态栏图标关了，手机「正在播放」
     * 位置仍显示——那是系统对 MediaSession 的独立渲染，不受通知渠道
     * IMPORTANCE_MIN / VISIBILITY_SECRET 控制）。
     * 关 = 开跑不创建媒体会话：锁屏/通知栏的音乐样式卡片与状态栏音乐指示
     * 一并消失，通知变为普通样式；开 = 保持 v1.8.6 以来的音乐卡片行为。
     * 与渠道级设置一样，改开关后下次开始跑步生效。
     */
    var notifMedia: Boolean
        get() = sp.getBoolean("notifMedia", true)
        set(v) = sp.edit().putBoolean("notifMedia", v).apply()

    /**
     * v1.8.15：跑步页点位分组筛选（v1.9.2：「自动」分组已删）。
     * "all"（默认，全部点位）/ 其余值 = 分组名（锁定该组）。
     */
    var runFilter: String
        get() = sp.getString("runFilter", "all") ?: "all"
        set(v) = sp.edit().putString("runFilter", v).apply()

    // 更新（v1.8.13：只走 GitHub Release，见 Updater.GITHUB_RELEASES_PAGE）
    var updAutoCheck: Boolean
        get() = sp.getBoolean("updAutoCheck", true)
        set(v) = sp.edit().putBoolean("updAutoCheck", v).apply()
    var updLastCheckMs: Long
        get() = sp.getLong("updLastCheckMs", 0L)
        set(v) = sp.edit().putLong("updLastCheckMs", v).apply()

    /**
     * v1.9.0：全部配置恢复默认（用户需求：替代旧「恢复默认打卡点」单一入口）。
     * 重置范围 = 全部可调配置：检测参数 / 提醒总开关 + 分场景开关 / 跑步通知
     * 显示 / API 判定 / 地图与坐标 / 跑步页筛选 / 更新自动检查。
     * 【不碰】账号与会话（casTgt / token / apiCookie / ws* / accountsJson /
     * tunnelPrefix）——恢复配置不应把用户登出；点位由调用方配合
     * CheckpointStore.resetToDefaults() 一并重建 8 个官方默认点。
     */
    fun resetAllConfig() {
        val e = sp.edit()
        e.putFloat("defaultRadius", D.RADIUS)
        e.putFloat("approachDist", D.APPROACH)
        e.putFloat("pollInterval", D.POLL)
        e.putFloat("graceSec", D.GRACE)
        e.putBoolean("tts", true)
        e.putBoolean("chime", true)
        e.putBoolean("vibrate", true)
        e.putBoolean("apiEnabled", false)
        e.putBoolean("tileSat", true)
        e.putBoolean("coordGcj", false)
        e.putString("runFilter", "all")
        e.putBoolean("updAutoCheck", true)
        e.putBoolean("notifLockscreen", true)
        e.putBoolean("notifStatusbar", true)
        e.putBoolean("notifMedia", true)
        // 分场景开关为动态键（scnv_*/scnvi_*）——显式移除后回到默认白名单
        for (k in sp.all.keys.filter { it.startsWith("scnv_") || it.startsWith("scnvi_") }) {
            e.remove(k)
        }
        e.apply()
    }

    companion object {
        @Volatile private var inst: Prefs? = null
        fun get(ctx: Context): Prefs =
            inst ?: synchronized(this) {
                inst ?: Prefs(ctx.getSharedPreferences("runmate", Context.MODE_PRIVATE)).also { inst = it }
            }
    }
}
