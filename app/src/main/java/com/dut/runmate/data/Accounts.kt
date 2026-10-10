package com.dut.runmate.data

import com.dut.runmate.data.api.ApiStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.7.0 · 多账号体系（同时保存多个同学账号，随时切换查询各自的健康长跑距离）。
 *
 * 背景：findExtExercise 的四个参数（userId/amId/pmId/sign）与 Authorization 令牌
 * 全部是「账号绑定」值，H5 捕获每次只针对当前登录者。多账号 = 每人一套完整的
 * 「登录会话 + 接口快照」：
 *   - 登录会话：wsSkey（微哨）/ casTgt（CAS 统一认证）→ H5 捕获页免密用
 *   - 接口快照：apiUrl / apiHeaders / apiBody / apiDistPath + apiToken / apiCookie
 *
 * 存储：Prefs.accountsJson（JSON 数组，主键 = 学号）。
 * 兼容迁移：首次访问时若列表为空且旧版已有单账号（wsNumber 非空 + 已捕获过
 * 接口），自动把当前活跃状态转成第一个账号条目 —— 老用户升级零感知。
 *
 * 切换语义（关键设计）：当前活跃账号始终存于 Prefs 旧字段
 * （wsNumber/apiToken/DIST 模板…），切换时先把当前状态快照回列表，
 * 再把目标账号整体写入 Prefs —— 跑步页轮询、发送测试、H5 捕获等
 * 全部既有逻辑零改动。
 */
data class WsAccount(
    var number: String,            // 学号（主键）
    var name: String = "",         // 姓名
    var pwdB64: String = "",       // 密码（Base64 混淆，同旧版 wsPwdSaved）
    var wsUserId: String = "",
    var wsSkey: String = "",
    var casTgt: String = "",
    var apiToken: String = "",     // Authorization：userId:token
    var apiCookie: String = "",    // WebVPN 隧道 Cookie（wengine_vpn_ticket 等）
    var apiUrl: String = "",       // findExtExercise URL（校内直连或 WebVPN 隧道）
    var apiHeaders: String = "",   // 请求头 JSON（含 {token}/{cookie} 占位符）
    var apiBody: String = "",      // {"userId":…,"amId":…,"pmId":…,"sign":…}
    var apiDistPath: String = "",  // pmDel.distance
    var capturedAt: Long = 0       // 最后一次 H5 捕获成功的时间戳
) {
    /** 是否已完成过 H5 捕获（接口快照是否可用） */
    val captured: Boolean get() = apiUrl.isNotBlank() && apiBody.isNotBlank()
}

object Accounts {

    /** 读取全部账号（含旧版单账号自动迁移）。顺序稳定：按 capturedAt 降序，活跃者优先展示由 UI 处理 */
    fun load(prefs: Prefs, store: ApiStore): MutableList<WsAccount> {
        val list = mutableListOf<WsAccount>()
        if (prefs.accountsJson.isNotBlank()) {
            try {
                val arr = JSONArray(prefs.accountsJson)
                for (i in 0 until arr.length()) list.add(fromJson(arr.getJSONObject(i)))
            } catch (_: Exception) {
            }
        }
        // 旧版迁移：列表为空但本机已有单账号配置（登录过 + 捕获过）→ 转成第一个账号
        if (list.isEmpty()) {
            val snap = snapshotCurrent(prefs, store)
            if (snap != null) {
                list.add(snap)
                save(prefs, list)
            }
        }
        return list
    }

    fun save(prefs: Prefs, list: List<WsAccount>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs.accountsJson = arr.toString()
    }

    fun byNumber(prefs: Prefs, store: ApiStore, number: String): WsAccount? =
        load(prefs, store).firstOrNull { it.number == number }

    /** 按学号新增/合并（捕获成功、切换快照、批量迁移都会走这里） */
    fun upsert(prefs: Prefs, store: ApiStore, acc: WsAccount) {
        if (acc.number.isBlank()) return
        val list = load(prefs, store)
        val i = list.indexOfFirst { it.number == acc.number }
        if (i >= 0) list[i] = merge(list[i], acc) else list.add(acc)
        save(prefs, list)
    }

    /** 合并策略：新值非空才覆盖（保留历史密码/会话，避免部分快照把数据抹掉） */
    private fun merge(old: WsAccount, new: WsAccount): WsAccount {
        fun pick(a: String, b: String) = b.ifBlank { a }
        return WsAccount(
            number = new.number.ifBlank { old.number },
            name = pick(old.name, new.name),
            pwdB64 = pick(old.pwdB64, new.pwdB64),
            wsUserId = pick(old.wsUserId, new.wsUserId),
            wsSkey = pick(old.wsSkey, new.wsSkey),
            casTgt = pick(old.casTgt, new.casTgt),
            apiToken = pick(old.apiToken, new.apiToken),
            apiCookie = new.apiCookie.ifBlank { old.apiCookie },   // 隧道 Cookie 以最新为准可清空场景少，保留旧值
            apiUrl = pick(old.apiUrl, new.apiUrl),
            apiHeaders = pick(old.apiHeaders, new.apiHeaders),
            apiBody = pick(old.apiBody, new.apiBody),
            apiDistPath = pick(old.apiDistPath, new.apiDistPath),
            capturedAt = if (new.capturedAt > 0) new.capturedAt else old.capturedAt
        )
    }

    fun delete(prefs: Prefs, store: ApiStore, number: String) {
        val list = load(prefs, store)
        list.removeAll { it.number == number }
        save(prefs, list)
    }

    /**
     * 把「当前活跃状态」快照为账号条目。
     * 学号为空（从未登录）或接口模板仍为预填占位（未捕获）时返回 null ——
     * 避免把「抓包值」占位模板当成有效快照存进列表。
     */
    fun snapshotCurrent(prefs: Prefs, store: ApiStore): WsAccount? {
        val number = prefs.wsNumber.trim()
        if (number.isBlank()) return null
        val p = store.distanceProfile() ?: return null
        // 预填模板的 body 仍是「抓包值」占位 → 尚未捕获，不算有效接口快照
        if (p.url.isBlank() || p.body.contains("抓包值")) {
            // 仍然保存登录信息（可切换回去重新捕获），只是 captured=false
            return WsAccount(
                number = number, name = prefs.wsName, pwdB64 = prefs.wsPwdSaved,
                wsUserId = prefs.wsUserId, wsSkey = prefs.wsSkey, casTgt = prefs.casTgt
            )
        }
        return WsAccount(
            number = number, name = prefs.wsName, pwdB64 = prefs.wsPwdSaved,
            wsUserId = prefs.wsUserId, wsSkey = prefs.wsSkey, casTgt = prefs.casTgt,
            apiToken = prefs.apiToken, apiCookie = prefs.apiCookie,
            apiUrl = p.url, apiHeaders = p.headers, apiBody = p.body,
            apiDistPath = p.distPath, capturedAt = System.currentTimeMillis()
        )
    }

    /**
     * 切换活跃账号：快照当前 → 目标账号整体写入 Prefs 旧字段 + DIST 模板。
     * 返回 false = 目标不存在或就是当前账号（无需动作）。
     */
    fun switchTo(prefs: Prefs, store: ApiStore, number: String): Boolean {
        if (number.isBlank() || number == prefs.wsNumber.trim()) return false
        val target = byNumber(prefs, store, number) ?: return false
        snapshotCurrent(prefs, store)?.let { upsert(prefs, store, it) }
        // —— 写入活跃状态 ——
        prefs.wsNumber = target.number
        prefs.wsName = target.name
        prefs.wsPwdSaved = target.pwdB64
        prefs.wsUserId = target.wsUserId
        prefs.wsSkey = target.wsSkey
        prefs.casTgt = target.casTgt
        prefs.apiToken = target.apiToken
        prefs.apiCookie = target.apiCookie
        // DIST 模板：仅当目标账号有有效快照时才覆盖（避免用空值抹掉可用的公共模板）
        if (target.captured) {
            val p = store.distanceProfile()
            if (p != null) {
                p.method = "POST"
                p.url = target.apiUrl
                p.headers = target.apiHeaders
                p.body = target.apiBody
                p.distPath = target.apiDistPath
                store.update(p)
            }
        }
        return true
    }

    private fun toJson(a: WsAccount): JSONObject = JSONObject()
        .put("number", a.number).put("name", a.name).put("pwdB64", a.pwdB64)
        .put("wsUserId", a.wsUserId).put("wsSkey", a.wsSkey).put("casTgt", a.casTgt)
        .put("apiToken", a.apiToken).put("apiCookie", a.apiCookie)
        .put("apiUrl", a.apiUrl).put("apiHeaders", a.apiHeaders)
        .put("apiBody", a.apiBody).put("apiDistPath", a.apiDistPath)
        .put("capturedAt", a.capturedAt)

    private fun fromJson(o: JSONObject): WsAccount = WsAccount(
        number = o.optString("number"),
        name = o.optString("name"),
        pwdB64 = o.optString("pwdB64"),
        wsUserId = o.optString("wsUserId"),
        wsSkey = o.optString("wsSkey"),
        casTgt = o.optString("casTgt"),
        apiToken = o.optString("apiToken"),
        apiCookie = o.optString("apiCookie"),
        apiUrl = o.optString("apiUrl"),
        apiHeaders = o.optString("apiHeaders"),
        apiBody = o.optString("apiBody"),
        apiDistPath = o.optString("apiDistPath"),
        capturedAt = o.optLong("capturedAt", 0)
    )
}
