package com.dut.runmate.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Checkpoint(
    var id: Long = 0,
    var name: String = "点位",
    var lat: Double = 0.0,          // WGS-84 存储
    var lon: Double = 0.0,
    var radius: Int = 25,           // 检测半径（米）
    var note: String = "",
    var source: String = "map"      // map | gps | manual
)

/**
 * 打卡点仓库：JSON 持久化于 filesDir/checkpoints.json
 */
class CheckpointStore private constructor(private val file: File) {

    private val list = mutableListOf<Checkpoint>()

    fun all(): List<Checkpoint> = list.toList()

    val count: Int get() = list.size

    fun upsert(cp: Checkpoint): Checkpoint {
        if (cp.id == 0L) {
            cp.id = (list.maxOfOrNull { it.id } ?: 0L) + 1L
            list.add(cp)
        } else {
            val i = list.indexOfFirst { it.id == cp.id }
            if (i >= 0) list[i] = cp else list.add(cp)
        }
        persist()
        return cp
    }

    fun delete(id: Long): Boolean {
        val ok = list.removeAll { it.id == id }
        if (ok) persist()
        return ok
    }

    fun byId(id: Long): Checkpoint? = list.firstOrNull { it.id == id }

    fun defaultName(): String = "点位${count + 1}"

    fun exportJson(): String {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        return arr.toString(2)
    }

    private fun persist() {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        file.writeText(arr.toString())
    }

    private fun Checkpoint.toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id); o.put("name", name)
        o.put("lat", lat); o.put("lon", lon)
        o.put("radius", radius); o.put("note", note); o.put("source", source)
        return o
    }

    companion object {
        @Volatile private var inst: CheckpointStore? = null
        fun get(dir: File): CheckpointStore =
            inst ?: synchronized(this) {
                inst ?: CheckpointStore(File(dir, "checkpoints.json")).also { s ->
                    inst = s
                    s.load()
                }
            }

        private fun CheckpointStore.load() {
            if (!file.exists()) return
            try {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        Checkpoint(
                            id = o.getLong("id"),
                            name = o.optString("name", "点位"),
                            lat = o.optDouble("lat"),
                            lon = o.optDouble("lon"),
                            radius = o.optInt("radius", 25),
                            note = o.optString("note"),
                            source = o.optString("source", "map")
                        )
                    )
                }
            } catch (_: Exception) { }
        }
    }
}
