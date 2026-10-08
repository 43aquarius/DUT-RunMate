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
                }
            }

        private fun ApiStore.seed() {
            profiles.addAll(
                listOf(
                    ApiProfile(
                        id = DIST_ID,
                        title = "跑步距离查询（进区判定用）",
                        method = "GET",
                        url = "",
                        headers = "{\"Cookie\":\"{token}\"}",
                        body = "",
                        distPath = "data.distance"
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
