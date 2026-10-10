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
        val error: String? = null
    )

    private fun render(s: String, token: String, cookie: String): String {
        var r = if (token.isBlank()) s else s.replace("{token}", token)
        if (cookie.isBlank()) {
            // 无 Cookie 时移除 {cookie} 空值头并修复 JSON 逗号
            r = r.replace(Regex("\"Cookie\"\\s*:\\s*\"\\{cookie}\"\\s*,?"), "")
            r = r.replace(Regex(",\\s*,"), ",").replace("{,", "{").replace(Regex(",\\s*}"), "}")
        } else {
            r = r.replace("{cookie}", cookie)
        }
        return r
    }

    /** v1.4.0：token 之外增加 cookie（WebVPN 隧道场景）
     *  v1.5.0：不再吞 CancellationException——否则页面销毁后协程继续跑，
     *  会触摸已置空的 ViewBinding（_b!!）导致闪退（发送测试/登录偶发崩溃根因）。 */
    suspend fun call(profile: ApiProfile, token: String, cookie: String = ""): Resp = withContext(Dispatchers.IO) {
        val t0 = System.nanoTime()
        try {
            val url = render(profile.url, token, cookie)
            val b = Request.Builder().url(url)
            val headers = render(if (profile.headers.isBlank()) "{}" else profile.headers, token, cookie)
            try {
                val h = JSONObject(headers)
                for (k in h.keys()) {
                    val v = h.optString(k, "")
                    if (v.isBlank()) continue
                    b.header(k, v)
                }
            } catch (_: Exception) { }
            val method = profile.method.uppercase()
            if (method == "POST") {
                val bodyStr = render(profile.body, token, cookie)
                b.post((if (bodyStr.isBlank()) "{}" else bodyStr).toRequestBody(JSON_MEDIA))
            } else if (method == "PUT") {
                val bodyStr = render(profile.body, token, cookie)
                b.put((if (bodyStr.isBlank()) "{}" else bodyStr).toRequestBody(JSON_MEDIA))
            }
            client.newCall(b.build()).execute().use { r ->
                Resp(
                    code = r.code,
                    ms = ((System.nanoTime() - t0) / 1_000_000).toInt(),
                    body = r.body?.string() ?: "",
                    url = url
                )
            }
        } catch (e: CancellationException) {
            throw e                     // 页面已销毁：让协程静默终止，绝不触摸 UI
        } catch (e: Exception) {
            Resp(0, ((System.nanoTime() - t0) / 1_000_000).toInt(), "", render(profile.url, token, cookie), e.message)
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
}
