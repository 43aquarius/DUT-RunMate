package com.dut.runmate.data

import com.dut.runmate.geo.GeoKit
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
                    s.migrateGeoV2()
                }
            }

        /**
         * v1.2.0 一次性迁移：v1.1.0 及之前 GCJ-02 换算实现有误，
         * 地图点选录入（source=map/manual）的存储坐标是「假 WGS」（实际偏移约 450~570m）。
         * 现场标定（source=gps）直接存 GPS 原始值，不受影响。
         * 迁移公式（已在脚本中验证，残差 0.0cm）：真WGS = gcj2wgs(旧算法还原出的点选位置)。
         */
        private fun CheckpointStore.migrateGeoV2() {
            val marker = File(file.parentFile, "geo_migration_v2.done")
            if (marker.exists()) return
            try {
                var changed = 0
                list.forEach { cp ->
                    if (cp.source == "map" || cp.source == "manual") {
                        val w = GeoKit.migrateLegacyWgs(cp.lat, cp.lon)
                        cp.lat = w[0]
                        cp.lon = w[1]
                        changed++
                    }
                }
                if (changed > 0) persist()
            } catch (_: Exception) { }
            try { marker.createNewFile() } catch (_: Exception) { }
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
