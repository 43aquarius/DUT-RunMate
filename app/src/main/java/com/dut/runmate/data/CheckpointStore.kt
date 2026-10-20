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
    var radius: Double = 10.0,      // 检测半径（米，v1.9.0 起支持 1 位小数）
    var note: String = "",
    var source: String = "map",     // map | gps | manual
    /** v1.8.15：点位分组（如「中心田径场」）——跑步页按组筛选显示，默认自动选中距离最近的组 */
    var group: String = ""
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

    /** v1.8.15：清空并恢复为 8 个默认打卡点；v1.9.0 起由「全部恢复默认」一并调用 */
    fun resetToDefaults() {
        list.clear()
        DEFAULTS.forEach { list.add(it.copy()) }
        persist()
    }

    /** v1.8.15：现有全部分组名（去重，保留录入顺序；空分组名不计） */
    fun groups(): List<String> = list.map { it.group }.filter { it.isNotBlank() }.distinct()

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
        o.put("group", group)
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
                    s.seedDefaults()   // v1.8.15：首次安装预录 8 个官方打卡点（带分组）
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
                            radius = o.optDouble("radius", 10.0),   // v1.9.0：小数半径；旧文件存的 Int 值 optDouble 同样兼容
                            note = o.optString("note"),
                            source = o.optString("source", "map"),
                            group = o.optString("group")
                        )
                    )
                }
            } catch (_: Exception) { }
        }

        /**
         * v1.8.15 一次性种子：点位库为空（首次安装）时预录 8 个官方打卡点并分组——
         * 中心田径场（a/b）、西山生活区（c/d）、西部校区田径场（e/f/g/h），
         * 坐标为用户现场 GPS 标定值（标定 ±3.8m，离散 ≤0.7m）。
         * 已有点位的旧版本升级不覆盖（marker 防重入）；老用户想换回这套点位
         * 走设置页「全部恢复默认」。
         */
        private fun CheckpointStore.seedDefaults() {
            val marker = File(file.parentFile, "seed_defaults.done")
            if (marker.exists()) return
            try {
                if (list.isEmpty()) {
                    DEFAULTS.forEach { list.add(it.copy()) }
                    persist()
                }
            } catch (_: Exception) { }
            try { marker.createNewFile() } catch (_: Exception) { }
        }

        /** v1.8.15：默认打卡点（id 与用户标定数据一致；radius=20m，source=gps。
         * v1.9.0：radius 类型改 Double——注意 Kotlin 整数字面量不会隐式转 Double，必须写 20.0） */
        private val DEFAULTS = listOf(
            Checkpoint(1, "点位 h", 38.8835245, 121.5099830, 20.0, "标定 ±3.8m·离散0.3m", "gps", "西部校区田径场"),
            Checkpoint(2, "点位 e", 38.8825987, 121.5102414, 20.0, "标定 ±3.8m·离散0.3m", "gps", "西部校区田径场"),
            Checkpoint(3, "点位 f", 38.8831961, 121.5109119, 20.0, "标定 ±3.8m·离散0.2m", "gps", "西部校区田径场"),
            Checkpoint(4, "点位 g", 38.8841217, 121.5106756, 20.0, "标定 ±3.8m·离散0.1m", "gps", "西部校区田径场"),
            Checkpoint(5, "点位 a", 38.875885327499994, 121.52492782749997, 20.0, "标定 ±3.8m·离散0.1m", "gps", "中心田径场"),
            Checkpoint(6, "点位 b", 38.876947427499985, 121.52573790899999, 20.0, "标定 ±3.8m·离散0.0m", "gps", "中心田径场"),
            Checkpoint(7, "点位 c", 38.8793514, 121.5171089, 20.0, "标定 ±3.8m·离散0.7m", "gps", "西山生活区"),
            Checkpoint(8, "点位 d", 38.88111223849999, 121.51680995949997, 20.0, "标定 ±3.8m·离散0.2m", "gps", "西山生活区")
        )
    }
}
