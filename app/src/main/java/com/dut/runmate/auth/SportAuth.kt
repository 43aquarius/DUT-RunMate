package com.dut.runmate.auth

import com.dut.runmate.data.Prefs
import com.dut.runmate.data.WsAccount
import com.dut.runmate.data.api.ApiStore
import com.dut.runmate.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * v1.8.3 · 健康长跑平台原生认证（一键配置主通道，替代 H5 嗅探）。
 *
 * 背景（用户实报 v1.8.2「已登录却无法获取 token / 账号未捕获 / webvpn 会话未建立或已过期」）：
 * H5 捕获页的根本缺陷——健康长跑 H5（Vue SPA）内部 axios 全部请求【原始内网地址】
 * http://202.118.65.138:8081/service/…（mobile-server-config.js 里写死，网关不改写），
 * 校园网外该地址不可达 → H5 的令牌交换（noPassCheck）永远失败 → 嗅探器永远抓不到
 * 200 的 findExtExercise → 「账号未捕获」；而 App 拿不到 wengine Cookie 直接请求隧道
 * → 网关报「会话未建立或已过期」。官方 i大工 App 校外能用是因为其 WebView 自带
 * 内网 URL 改写，RunMate 的 WebView 没有。
 *
 * 本对象把整条链路【纯原生复刻】（2026-10-11 沙箱用真实账号逐步实测验证）：
 *  1) CAS 统一认证（复用 CasAuth，CASTGC 入共享 Cookie jar）
 *  2) WebVPN 统一身份认证（/login → cas-login 链接 → CAS 票据回跳）→ wengine_vpn_ticket
 *  3) wengine 隧道前缀本地计算：AES-CFB-128（固定 key/IV "Wxzxvpn2023key@$"）加密目标
 *     host，prefix = hex(IV) + hex(密文)[:len(host)]——与门户 portal.js encrypt() 逐字节
 *     一致（实测对 202.118.65.138 的计算结果与官方 App 抓包前缀完全相同）。
 *     ⚠ 推翻 v1.8.1「前缀与登录用户绑定」的结论：前缀是【全局确定性】的（密钥固定），
 *     "0" 占位失败只是因为它不是合法密文。
 *  4) 隧道 service/cas/mobileCheckJump → wengine 服务端 CAS 会话自动出票 → 落地页
 *     携带一次性 user_account（64 hex）
 *  5) 隧道 login/mobile/noPassCheck {username:<user_account>, sys_id:"iscpMobile"}
 *     → PASS：token（"userId:xxx"，Authorization 头原值）/ user_id / 姓名 / 学号 / 班级
 *  6) 隧道 mobile/extExercise/findExeRule {userId} → amId / pmId（学期/时段 ID）
 *  7) 隧道 mobile/extExercise/findExtExercise {userId,amId,pmId,sign} → 距离（验证一步）
 *
 * 请求签名：sign = SHA1(按 key 字典序 "k=v" 用 & 连接 + "&key=8d6c5b73a50d4707bd71c93882ddbc8b")
 * 的大写十六进制（与 H5 app.js 的 S()+u() 逐字节一致，已用官方抓包基准向量验证）。
 */
object SportAuth {

    /** H5 app.js 内置签名密钥（webpack 模块 Tg7E） */
    const val SIGN_KEY = "8d6c5b73a50d4707bd71c93882ddbc8b"

    /** wengine 隧道前缀密钥（实测 2026-10 与门户 /user/info 下发的 wrdvpnKey/wrdvpnIV 相同且固定） */
    private const val VPN_KEY = "Wxzxvpn2023key@$"

    private const val WEBVPN = "https://webvpn.dlut.edu.cn"
    private const val SPORT_HOST = "202.118.65.138"
    private const val SPORT_PORT = 8081
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) Mobile Safari/537.36 weishao(3.3.12.75026)"

    /** 健康长跑内网地址（H5 内部使用的原始形式） */
    const val SPORT_BASE = "http://$SPORT_HOST:$SPORT_PORT"

    /** 一次性登录结果：足以配置「接口」页全部内容 */
    data class Session(
        val number: String,          // 学号
        val name: String,            // 姓名
        val userId: String,          // 体育平台 userId（= 微哨 user_id）
        val token: String,           // "userId:xxx"（Authorization 头原值）
        val cookie: String,          // webvpn Cookie 串（wengine_vpn_ticket 等）
        val prefix: String,          // 隧道前缀
        val amId: String,
        val pmId: String,
        val findUrl: String,         // 隧道 findExtExercise URL（完整可回放）
        val distBody: String,        // findExtExercise 完整请求体（含 sign）
        val distanceRaw: String      // 验证一步拿到的 distance 原始值（"无" = 今日未跑）
    )

    sealed class Result {
        data class Ok(val s: Session) : Result()
        data class Err(val step: String, val msg: String) : Result()
    }

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /** 不跟随重定向（mobileCheckJump 需要手动跟随并读最终页） */
    private val noRedirectClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    // ================= 工具 =================

    /**
     * 请求签名（对齐 H5 S()+u()）：非空值按 key 字典序 "k=v" 以 & 连接，
     * 追加 "&key=<SIGN_KEY>" 后 SHA-1 大写十六进制。已用抓包基准向量验证。
     */
    fun mkSign(params: Map<String, String>): String {
        val base = params.entries
            .filter { it.key != "sign" && it.value.isNotEmpty() }
            .sortedBy { it.key }
            .joinToString("&") { "${it.key}=${it.value}" }
        val md = MessageDigest.getInstance("SHA-1")
        val digest = md.digest((base + "&key=" + SIGN_KEY).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02X".format(it) }
    }

    /**
     * wengine 隧道前缀（对齐门户 portal.js encrypt()）：AES-CFB-128（全块反馈），
     * key/IV 同为 "Wxzxvpn2023key@$"；输出 hex(IV) + hex(密文)[:len(host)]。
     * 实测对 202.118.65.138 结果 = 57787a78…（与官方 App 抓包一致，全局确定性）。
     */
    fun tunnelPrefix(host: String, key: String = VPN_KEY, iv: String = VPN_KEY): String {
        val ct = cfb128(key.toByteArray(Charsets.UTF_8), iv.toByteArray(Charsets.UTF_8),
            host.toByteArray(Charsets.UTF_8))
        return iv.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) } +
                ct.joinToString("") { "%02x".format(it) }
    }

    /** 手写 AES-CFB-128（全块反馈，每块寄存器=上一块密文）——不依赖 JCE CFB 变体命名差异 */
    private fun cfb128(key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val out = ByteArray(input.size)
        var reg = iv
        var i = 0
        while (i < input.size) {
            val ks = c.doFinal(reg)
            val n = minOf(16, input.size - i)
            for (j in 0 until n) out[i + j] = (input[i + j].toInt() xor ks[j].toInt()).toByte()
            reg = if (n == 16) out.copyOfRange(i, i + 16) else reg.copyOf()
            i += n
        }
        return out
    }

    /** 隧道化 URL：http://202.118.65.138:8081/xxx → https://webvpn.dlut.edu.cn/http-8081/<prefix>/xxx */
    fun tunnelUrl(internalUrl: String, prefix: String = tunnelPrefix(SPORT_HOST)): String {
        if (!internalUrl.contains(SPORT_HOST)) return internalUrl
        val path = internalUrl.substringAfter("$SPORT_HOST:$SPORT_PORT", "")
        return "$WEBVPN/http-8081/$prefix$path"
    }

    /** webvpn 会话 Cookie（从 CasAuth 共享 jar 按 domain/path 过滤） */
    private fun webvpnCookie(): String = CasAuth.cookieHeader(WEBVPN)

    // ================= 主链路 =================

    /**
     * 原生一键配置。freshCas=true 时内部先做 CAS 登录（自动续期场景）；
     * SettingsFragment（一键配置）场景 CASTGC 刚拿到可传 false 省一步。
     */
    suspend fun login(
        number: String, password: String,
        freshCas: Boolean = true,
        log: (String) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        try {
            if (number.isBlank() || password.isBlank())
                return@withContext Result.Err("参数", "请输入学号和密码")

            // 1) CAS（可跳过：调用方刚登录过，jar 里已有新鲜 CASTGC）
            if (freshCas) {
                log("① CAS 统一认证…")
                when (val r = CasAuth.login(number, password)) {
                    is CasAuth.Result.Err ->
                        return@withContext Result.Err("CAS 登录", r.msg)
                    // v1.8.8：学校 CAS 开启短信二次认证——无头链路（跑步中自动续期）
                    // 无法完成图形验证码+短信码，转明确报错引导回「接口」页手动登录
                    is CasAuth.Result.MfaRequired ->
                        return@withContext Result.Err("二次认证",
                            "学校已开启短信二次认证，自动续期无法完成；请到「接口」页重新登录")
                    else -> {}
                }
            } else {
                log("① 使用现有 CAS 会话…")
            }

            // 2) WebVPN 统一身份认证（wengine 会话）
            log("② WebVPN 统一身份认证…")
            val hopCount = webvpnLogin(number, password)
            val ck = webvpnCookie()
            if (!ck.contains("wengine_vpn_ticket"))
                return@withContext Result.Err("WebVPN 登录", "未取得 wengine 会话（重试 $hopCount 跳）")

            // 3) 隧道前缀
            val prefix = tunnelPrefix(SPORT_HOST)
            val base = "$WEBVPN/http-8081/$prefix"
            log("③ 隧道前缀就绪（${prefix.length} 位）")

            // 4) mobileCheckJump → 一次性 user_account
            log("④ 体育平台 CAS 免密跳转…")
            val jumpUrl = "$base/service/cas/mobileCheckJump"
            val jumpBody = fetchFollowing(jumpUrl, ck)
                ?: return@withContext Result.Err("免密跳转", "网络错误（mobileCheckJump 无响应）")
            val uaM = Regex("user_account=([0-9a-fA-F]{64})").find(jumpBody)
                ?: return@withContext Result.Err("免密跳转", "落地页未含 user_account（页面 ${jumpBody.length} 字节）")
            val userAccount = uaM.groupValues[1]

            // 5) noPassCheck → token / userId / 姓名
            log("⑤ 免密校验换取令牌…")
            val npBody = JSONObject()
                .put("username", userAccount)
                .put("sys_id", "iscpMobile")
            npBody.put("sign", mkSign(mapOf("username" to userAccount, "sys_id" to "iscpMobile")))
            val npJson = postJson("$base/service/login/mobile/noPassCheck", npBody, ck, "")
                ?: return@withContext Result.Err("令牌交换", "网络错误（noPassCheck 无响应）")
            if (npJson.optString("status") != "PASS") {
                return@withContext Result.Err(
                    "令牌交换", npJson.optString("message", "noPassCheck 未通过")
                )
            }
            val userId = npJson.optString("user_id")
            val token = npJson.optString("token")
            if (userId.isBlank() || token.isBlank())
                return@withContext Result.Err("令牌交换", "响应缺少 user_id/token")
            val name = npJson.optString("user_name").ifBlank { "同学" }
            val stdNumber = npJson.optString("std_number").ifBlank { number }
            log("⑥ 认证成功：$name（$stdNumber）")

            // 6) findExeRule → amId / pmId
            log("⑦ 获取学期/时段 ID…")
            val frParams = mutableMapOf("userId" to userId)
            val frBody = JSONObject().put("userId", userId)
            frBody.put("sign", mkSign(frParams))
            val frJson = postJson("$base/service/mobile/extExercise/findExeRule", frBody, ck, token)
            val amId = frJson?.optString("amId") ?: ""
            val pmId = frJson?.optString("pmId") ?: ""

            // 7) findExtExercise 验证一步（distance 可能是 "无" = 今日未跑，属正常）
            log("⑧ 验证 findExtExercise…")
            val feParams = linkedMapOf(
                "userId" to userId,
                "amId" to amId,
                "pmId" to pmId
            )
            val feBody = JSONObject()
            feParams.forEach { (k, v) -> if (v.isNotEmpty()) feBody.put(k, v) }
            feBody.put("sign", mkSign(feParams))
            val feJson = postJson(
                "$base/service/mobile/extExercise/findExtExercise", feBody, ck, token
            )
            if (feJson == null || feJson.optString("status") != "PASS")
                return@withContext Result.Err(
                    "距离接口验证", feJson?.optString("message") ?: "无响应"
                )
            val distRaw = (feJson.optJSONObject("pmDel") ?: feJson.optJSONObject("amDel"))
                ?.optString("distance") ?: "无"

            Result.Ok(
                Session(
                    number = stdNumber, name = name, userId = userId, token = token,
                    cookie = ck, prefix = prefix, amId = amId, pmId = pmId,
                    findUrl = "$base/service/mobile/extExercise/findExtExercise",
                    distBody = feBody.toString(), distanceRaw = distRaw
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Err("异常", "${e.javaClass.simpleName}: ${e.message ?: ""}")
        }
    }

    /**
     * WebVPN 统一身份认证：GET /login → 点「统一身份认证登录」（cas-login 链接）→
     * 跟随 CAS 票据回跳链 → 门户首页。CASTGC 失效落回 CAS 登录页时自动重登一次。
     * 返回跟随的跳数（诊断用）。
     */
    private suspend fun webvpnLogin(number: String, password: String): Int {
        for (round in 0 until 2) {
            val page = CasAuth.client.newCall(
                Request.Builder().url("$WEBVPN/login").header("User-Agent", UA).build()
            ).execute().use { it.body?.string() ?: "" }
            val href = Regex("""<a[^>]+id="cas-login"[^>]+href="([^"]+)"""")
                .find(page)?.groupValues?.get(1)
                ?: Regex("""href="([^"]*cas_login[^"]*)"""").find(page)?.groupValues?.get(1)
                ?: "/login?cas_login=true"
            val target = if (href.startsWith("http")) href else "$WEBVPN$href"
            var hops = 0
            var url = target
            // 手动跟随跳转（最多 12 跳），记录最终 URL 判断落地。
            // ⚠ CasAuth.client 本身 followRedirects=true，多数情况一跳到底；
            // body 必须在 use 块内消费——旧版 `.use { it }` 把已关闭的 Response
            // 拿出来再读 body，会抛 IllegalStateException("body is closed")。
            while (hops < 12) {
                var code = 0
                var body = ""
                var finalUrl = url
                CasAuth.client.newCall(
                    Request.Builder().url(url).header("User-Agent", UA).build()
                ).execute().use { r ->
                    code = r.code
                    finalUrl = r.request.url.toString()
                    if (code in 300..399) {
                        url = r.header("Location")?.let { loc ->
                            if (loc.startsWith("http")) loc
                            else r.request.url.resolve(loc)?.toString()
                        } ?: ""
                    } else {
                        body = r.body?.string() ?: ""
                    }
                }
                hops++
                if (code in 300..399) {
                    if (url.isBlank()) break
                    continue
                }
                // 200 落地：CAS 登录页 = CASTGC 失效 → 下一轮重登 CAS
                // （用【最终 URL】判断——auto-follow 下 url 变量停在起点）
                val onCasForm =
                    (finalUrl.contains("/cas/login") || url.contains("/cas/login")) &&
                        body.contains("loginForm")
                if (onCasForm) {
                    if (round == 0 && CasAuth.login(number, password) is CasAuth.Result.Ok)
                        break                                   // 重登成功，外层 for 再走一轮
                    return hops
                }
                return hops                                     // 门户首页等正常落地
            }
        }
        return 0
    }

    /** GET + 跟随全部跳转，返回最终页正文（v1.8.3 修正：use{} 内联 lambda 里
     *  不允许 continue——跳转状态先落到局部变量，循环控制留在 while 层） */
    private fun fetchFollowing(url: String, cookie: String): String? {
        var cur = url
        var hops = 0
        while (hops < 12) {
            val rb = Request.Builder().url(cur).header("User-Agent", UA)
            if (cookie.isNotBlank()) rb.header("Cookie", cookie)
            val r = try {
                noRedirectClient.newCall(rb.build()).execute()
            } catch (e: Exception) {
                return null
            }
            var wasRedirect = false
            var redirectUrl: String? = null
            var bodyOut: String? = null
            r.use { resp ->
                if (resp.code in 300..399) {
                    wasRedirect = true
                    val loc = resp.header("Location")
                    redirectUrl = when {
                        loc == null -> null
                        loc.startsWith("http") -> loc
                        else -> resp.request.url.resolve(loc)?.toString()
                    }
                } else {
                    bodyOut = resp.body?.string()
                }
            }
            hops++
            if (wasRedirect) {
                cur = redirectUrl ?: return null
                continue
            }
            return bodyOut
        }
        return null
    }

    /** POST JSON（带 Cookie / Authorization），返回解析后的 JSONObject（失败 null） */
    private fun postJson(
        url: String, body: JSONObject, cookie: String, auth: String
    ): JSONObject? {
        val rb = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Content-Type", "application/json;charset=UTF-8")
            .post(body.toString().toRequestBody(JSON_MEDIA))
        if (cookie.isNotBlank()) rb.header("Cookie", cookie)
        if (auth.isNotBlank()) rb.header("Authorization", auth)
        return try {
            val txt = noRedirectClient.newCall(rb.build()).execute().use { it.body?.string() ?: "" }
            if (txt.isBlank()) null else JSONObject(txt)
        } catch (e: Exception) {
            null
        }
    }

    // ================= 落库 =================

    /**
     * 把原生登录结果写入「接口」页全部配置 + 多账号快照。
     * SettingsFragment（一键配置）与 RunTrackerService（会话自动续期）共用。
     */
    fun applySession(prefs: Prefs, store: ApiStore, s: Session) {
        // 令牌 + 隧道 Cookie
        prefs.apiToken = s.token
        prefs.apiCookie = s.cookie
        prefs.apiEnabled = true
        prefs.tunnelPrefix = s.prefix
        Http.tunnelPrefixHint = s.prefix

        // 账号信息（体育平台 userId 与微哨 user_id 同源）
        prefs.wsUserId = s.userId
        prefs.wsName = s.name
        prefs.wsNumber = s.number

        // 距离模板：完整可回放（{token}/{cookie} 占位符）
        val p = store.distanceProfile() ?: return
        p.method = "POST"
        p.title = "健康长跑距离 findExtExercise（WebVPN 隧道·原生配置）"
        p.url = s.findUrl
        p.headers = if (s.cookie.isBlank())
            "{\"Authorization\":\"{token}\",\"Content-Type\":\"application/json;charset=UTF-8\"}"
        else
            "{\"Authorization\":\"{token}\",\"Content-Type\":\"application/json;charset=UTF-8\",\"Cookie\":\"{cookie}\"}"
        p.body = s.distBody
        p.distPath = ApiStore.DIST_PATH
        store.update(p)

        // 多账号入库（captured = true）
        try {
            com.dut.runmate.data.Accounts.upsert(prefs, store, WsAccount(
                number = s.number, name = s.name, pwdB64 = prefs.wsPwdSaved,
                wsUserId = s.userId, wsSkey = prefs.wsSkey, casTgt = prefs.casTgt,
                apiToken = s.token, apiCookie = s.cookie,
                apiUrl = s.findUrl, apiHeaders = p.headers, apiBody = s.distBody,
                apiDistPath = ApiStore.DIST_PATH,
                capturedAt = System.currentTimeMillis()
            ))
        } catch (_: Exception) {
        }
    }
}
