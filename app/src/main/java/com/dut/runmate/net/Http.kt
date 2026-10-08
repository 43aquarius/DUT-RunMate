package com.dut.runmate.net

import com.dut.runmate.data.api.ApiProfile
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

    private fun render(s: String, token: String): String =
        if (token.isBlank()) s else s.replace("{token}", token)

    suspend fun call(profile: ApiProfile, token: String): Resp = withContext(Dispatchers.IO) {
        val t0 = System.nanoTime()
        try {
            val url = render(profile.url, token)
            val b = Request.Builder().url(url)
            val headers = render(if (profile.headers.isBlank()) "{}" else profile.headers, token)
            try {
                val h = JSONObject(headers)
                for (k in h.keys()) b.header(k, h.getString(k))
            } catch (_: Exception) { }
            val method = profile.method.uppercase()
            if (method == "POST") {
                val bodyStr = render(profile.body, token)
                b.post((if (bodyStr.isBlank()) "{}" else bodyStr).toRequestBody(JSON_MEDIA))
            } else if (method == "PUT") {
                val bodyStr = render(profile.body, token)
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
        } catch (e: Exception) {
            Resp(0, ((System.nanoTime() - t0) / 1_000_000).toInt(), "", render(profile.url, token), e.message)
        }
    }

    /**
     * 按 dot 路径提取数值，支持数组下标：data.0.distance / data.runDistance / distance
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
                is String -> cur.toDoubleOrNull()
                else -> null
            }
        } catch (_: Exception) { null }
    }

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
