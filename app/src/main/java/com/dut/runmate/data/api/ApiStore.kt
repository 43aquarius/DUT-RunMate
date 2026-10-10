package com.dut.runmate.data.api

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 可配置的接口模板：配合抓包把「查询跑步距离」等接口填进来。
 * url / headers / body 中 {token} 会被 Prefs.apiToken 替换。
 */
data class ApiProfile(
    var id: String,
    var title: String,
    var method: String = "GET",
    var url: String = "",
    var headers: String = "{}",
    var body: String = "",
    var distPath: String = ""
)

class ApiStore private constructor(private val file: File) {

    val profiles = mutableListOf<ApiProfile>()

    fun byId(id: String): ApiProfile? = profiles.firstOrNull { it.id == id }
    fun distanceProfile(): ApiProfile? = profiles.firstOrNull { it.id == DIST_ID }

    fun update(p: ApiProfile) {
        val i = profiles.indexOfFirst { it.id == p.id }
        if (i >= 0) profiles[i] = p
        persist()
    }

    private fun persist() {
        val arr = JSONArray()
        profiles.forEach {
            val o = JSONObject()
            o.put("id", it.id); o.put("title", it.title); o.put("method", it.method)
            o.put("url", it.url); o.put("headers", it.headers); o.put("body", it.body)
            o.put("distPath", it.distPath)
            arr.put(o)
        }
        file.writeText(arr.toString())
    }

    companion object {
        const val DIST_ID = "p_dist"
        const val PING_ID = "p_ping"
        const val CUSTOM_ID = "p_custom"

        /**
         * v1.8.0 · 默认模板改为 WebVPN 隧道地址（校内外通用，不依赖校园网）。
         * 隧道路由由查询参数 vpn-12-o1-202.118.65.138:8081 决定，前缀段不参与校验
         * （实测任意前缀可达，见 docs API 文档 §3.4）；需要 webvpn 会话 Cookie，
         * 未登录时会被 302 到登录页（App 会给出明确提示）。
         */
        const val DIST_TITLE = "健康长跑距离 findExtExercise（WebVPN 隧道）"
        const val DIST_URL =
            "https://webvpn.dlut.edu.cn/http-8081/0/service/mobile/extExercise/findExtExercise?vpn-12-o1-202.118.65.138:8081"

        /** v1.7.0 及之前的默认种子（校内直连，仅校园网内可达）——用于迁移识别 */
        const val LEGACY_DIST_URL =
            "http://202.118.65.138:8081/service/mobile/extExercise/findExtExercise"

        const val DIST_HEADERS =
            "{\"Authorization\":\"{token}\",\"Content-Type\":\"application/json;charset=UTF-8\",\"Cookie\":\"{cookie}\"}"
        const val DIST_PATH = "pmDel.distance"

        @Volatile private var inst: ApiStore? = null
        fun get(dir: File): ApiStore =
            inst ?: synchronized(this) {
                inst ?: ApiStore(File(dir, "api_profiles.json")).also { s ->
                    inst = s
                    if (s.file.exists()) {
                        try {
                            val arr = JSONArray(s.file.readText())
                            for (i in 0 until arr.length()) {
                                val o = arr.getJSONObject(i)
                                s.profiles.add(
                                    ApiProfile(
                                        id = o.getString("id"),
                                        title = o.optString("title"),
                                        method = o.optString("method", "GET"),
                                        url = o.optString("url"),
                                        headers = o.optString("headers", "{}"),
                                        body = o.optString("body"),
                                        distPath = o.optString("distPath")
                                    )
                                )
                            }
                        } catch (_: Exception) { }
                    }
                    if (s.profiles.isEmpty()) s.seed()
                    else {
                        s.migrateBlankDist()
                        s.migrateLegacyDirectSeed()
                    }
                }
            }

        /**
         * v1.3.0 迁移：老版本（≤1.2.0）默认距离模板 URL 为空。
         * 若用户未自定义，则升级为实测抓包得到的 findExtExercise 模板；已自定义（URL 非空）不动。
         */
        private fun ApiStore.migrateBlankDist() {
            val p = profiles.firstOrNull { it.id == DIST_ID } ?: return
            if (p.url.isNotBlank()) return
            p.title = DIST_TITLE
            p.method = "POST"
            p.url = DIST_URL
            p.headers = DIST_HEADERS
            p.body = "{\"userId\":\"抓包值\",\"amId\":\"抓包值\",\"pmId\":\"抓包值\",\"sign\":\"抓包值\"}"
            p.distPath = DIST_PATH
            persist()
        }

        /**
         * v1.8.0 迁移：≤1.7.0 的默认种子是校内直连地址（校园网外必连接超时，
         * 即用户实报的 failed to connect to /202.118.65.138:8081）。
         * 识别条件 = URL 原封是旧直连种子 且 请求体仍含「抓包值」占位符（= 从未
         * 成功捕获过）→ 升级为 WebVPN 隧道种子。用户自己改过/捕获过的配置绝不动。
         */
        private fun ApiStore.migrateLegacyDirectSeed() {
            val p = profiles.firstOrNull { it.id == DIST_ID } ?: return
            if (p.url.trim() != LEGACY_DIST_URL) return
            if (!p.body.contains("抓包值")) return
            p.title = DIST_TITLE
            p.method = "POST"
            p.url = DIST_URL
            p.headers = DIST_HEADERS
            p.body = "{\"userId\":\"抓包值\",\"amId\":\"抓包值\",\"pmId\":\"抓包值\",\"sign\":\"抓包值\"}"
            p.distPath = DIST_PATH
            persist()
        }

        private fun ApiStore.seed() {
            profiles.addAll(
                listOf(
                    ApiProfile(
                        id = DIST_ID,
                        title = DIST_TITLE,
                        method = "POST",
                        url = DIST_URL,
                        headers = DIST_HEADERS,
                        body = "{\"userId\":\"抓包值\",\"amId\":\"抓包值\",\"pmId\":\"抓包值\",\"sign\":\"抓包值\"}",
                        distPath = DIST_PATH
                    ),
                    ApiProfile(
                        id = PING_ID,
                        title = "连通性测试（whistlenew RPC）",
                        method = "GET",
                        url = "https://service.m.dlut.edu.cn/whistlenew/index.php?m=confInfo&a=getDlutAddress&stage=",
                        headers = "{\"Cookie\":\"{token}\"}",
                        body = "",
                        distPath = ""
                    ),
                    ApiProfile(
                        id = CUSTOM_ID,
                        title = "自定义接口",
                        method = "GET",
                        url = "",
                        headers = "{\"Cookie\":\"{token}\"}",
                        body = "",
                        distPath = ""
                    )
                )
            )
            persist()
        }
    }
}
