package com.dut.runmate.net

import com.dut.runmate.data.api.ApiProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

object Http {

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class Resp(
        val code: Int,
        val ms: Int,
        val body: String,
        val url: String,
        val error: String? = null,
        /** v1.8.0：跟随重定向后的终态 URL（OkHttp r.request.url）；未重定向时与 url 相同。
         *  用于识别「WebVPN 会话失效 → 302 登录页」——否则用户看到 HTTP 200 +
         *  登录页 HTML + 提取失败，完全不知道发生了什么。 */
        val finalUrl: String = ""
    )

    /**
     * v1.6.2：占位符替换全面去正则化。
     *
     * 旧版在 Cookie 为空时用 Regex("\"Cookie\"\\s*:\\s*\"\\{cookie}\"...") 删除空值头，
     * 该模式含 `\{` 转义——OpenJDK 接受，但 Android 14+ 的 ICU 正则
     * （com.android.icu.util.regex）拒绝编译，报 PatternSyntaxException，
     * 导致「发送测试/查询服务端距离/登录后自动验证」全部失败（请求根本没发出去）。
     * 新方案：请求头先 JSONObject 解析再逐值替换，占位符无值时丢弃该头，
     * 同时消除 Cookie 值含引号时破坏 JSON 的注入风险。 */
    private fun renderPlain(s: String, token: String, cookie: String): String {
        var r = s
        if (token.isNotBlank()) r = r.replace("{token}", token)
        if (cookie.isNotBlank()) r = r.replace("{cookie}", cookie)
        return r
    }

    /** 请求头专用：替换后若仍含占位符（令牌/Cookie 未配置）→ 返回 null 丢弃该头 */
    private fun renderHeader(s: String, token: String, cookie: String): String? {
        val r = renderPlain(s, token, cookie)
        if (r.isBlank() || r.contains("{token}") || r.contains("{cookie}")) return null
        return r
    }

    /** v1.4.0：token 之外增加 cookie（WebVPN 隧道场景）
     *  v1.5.0：不再吞 CancellationException——否则页面销毁后协程继续跑，
     *  会触摸已置空的 ViewBinding（_b!!）导致闪退（发送测试/登录偶发崩溃根因）。 */
    suspend fun call(profile: ApiProfile, token: String, cookie: String = ""): Resp = withContext(Dispatchers.IO) {
        val t0 = System.nanoTime()
        var url = profile.url
        try {
            url = renderPlain(profile.url, token, cookie)
            // 占位符无值时提前失败并给出明确原因（旧版会拿着含 {token} 的 URL 发出晦涩报错）
            if (url.contains("{token}") || url.contains("{cookie}")) {
                return@withContext Resp(0, 0, "", url,
                    "URL 中 {token}/{cookie} 占位符无值：请先在「接口」页登录自动配置，或手动填写令牌")
            }
            val b = Request.Builder().url(url)
            try {
                val h = JSONObject(if (profile.headers.isBlank()) "{}"
                    else renderPlain(profile.headers, token, cookie))
                for (k in h.keys()) {
                    val v = renderHeader(h.optString(k, ""), token, cookie) ?: continue
                    b.header(k, v)
                }
            } catch (_: Exception) { }
            val method = profile.method.uppercase()
            if (method == "POST") {
                val bodyStr = renderPlain(profile.body, token, cookie)
                b.post((if (bodyStr.isBlank()) "{}" else bodyStr).toRequestBody(JSON_MEDIA))
            } else if (method == "PUT") {
                val bodyStr = renderPlain(profile.body, token, cookie)
                b.put((if (bodyStr.isBlank()) "{}" else bodyStr).toRequestBody(JSON_MEDIA))
            }
            client.newCall(b.build()).execute().use { r ->
                Resp(
                    code = r.code,
                    ms = ((System.nanoTime() - t0) / 1_000_000).toInt(),
                    body = r.body?.string() ?: "",
                    url = url,
                    finalUrl = try { r.request.url.toString() } catch (_: Exception) { url }
                )
            }
        } catch (e: CancellationException) {
            throw e                     // 页面已销毁：让协程静默终止，绝不触摸 UI
        } catch (e: Exception) {
            Resp(0, ((System.nanoTime() - t0) / 1_000_000).toInt(), "", url, e.message)
        }
    }

    /**
     * 按 dot 路径提取数值，支持数组下标：data.0.distance / data.runDistance / distance。
     * 字符串值先直接转数，失败则提取首个数字（兼容实测的 "2700米"、"3.5km" 等格式）。
     */
    fun extractDouble(body: String, path: String): Double? {
        if (body.isBlank() || path.isBlank()) return null
        return try {
            var cur: Any? = JSONObject(body)
            for (seg in path.trim('.').split('.')) {
                if (cur == null) return null
                cur = when (cur) {
                    is JSONObject -> if (cur.has(seg)) cur.get(seg) else return null
                    is JSONArray -> {
                        val idx = seg.toIntOrNull() ?: return null
                        if (idx in 0 until cur.length()) cur.get(idx) else return null
                    }
                    else -> return null
                }
            }
            when (cur) {
                is Number -> cur.toDouble()
                is String -> parseNum(cur)
                else -> null
            }
        } catch (_: Exception) { null }
    }

    /** "2700米"/"2700"→2700.0；"无"/""→null */
    private fun parseNum(s: String): Double? =
        s.trim().toDoubleOrNull()
            ?: Regex("[-+]?\\d+(?:\\.\\d+)?").find(s.trim())?.value?.toDoubleOrNull()

    fun pretty(body: String): String {
        if (body.isBlank()) return "(空)"
        return try {
            JSONObject(body).toString(2)
        } catch (_: Exception) {
            try { JSONArray(body).toString(2) } catch (_: Exception) { body.take(4000) }
        }
    }

    fun Double.fmtM(): String = "${roundToInt()} m"

    /**
     * v1.8.0：识别「WebVPN 隧道请求被重定向到登录页」——会话 Cookie 缺失/过期时
     * wengine 门户返回 302 → /login，OkHttp 跟随后拿到的是登录页 HTML（HTTP 200）。
     * 表现为「测试通过却提取不到距离」，用户完全无从排查；统一在这里识别。
     */
    fun isWebvpnLoginRedirect(finalUrl: String, body: String, reqUrl: String): Boolean {
        if (!reqUrl.contains("webvpn.dlut.edu.cn")) return false
        if (finalUrl.isNotBlank()) {
            val path = finalUrl.substringAfter("webvpn.dlut.edu.cn", "")
            if (path.startsWith("/login") || path.startsWith("/cas")) return true
            return false
        }
        // 兑底：无终态 URL 时看登录页特征
        val b = body.lowercase()
        return b.contains("<html") && (b.contains("webvpn") || b.contains("统一身份认证"))
    }
}
