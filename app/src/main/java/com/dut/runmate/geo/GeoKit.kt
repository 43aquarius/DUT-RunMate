package com.dut.runmate.geo

import kotlin.math.*

/**
 * 地理工具：WGS-84 与 GCJ-02（高德/火星坐标）互转、距离、方位角。
 * 坐标一律以 WGS-84（原始 GPS）存储，仅在显示/互操作时换算 GCJ-02。
 */
object GeoKit {

    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323
    private const val PI = Math.PI

    fun outOfChina(lat: Double, lon: Double): Boolean =
        lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun delta(lat: Double, lon: Double): DoubleArray {
        // 标准算法：dlat 用 transformLat（-100 式），dlon 用 transformLon（300 式），分母不可互换。
        // v1.1.0 及之前此处两个函数/分母被互换，导致显示偏移约 570m（大连实测），v1.2.0 修正。
        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLon = (dLon * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return doubleArrayOf(dLat, dLon)
    }

    /** v1.1.0 及之前的错误 wgs2gcj（仅用于历史点位一次性迁移，勿作他用） */
    fun legacyBrokenWgs2gcj(lat: Double, lon: Double): DoubleArray {
        if (outOfChina(lat, lon)) return doubleArrayOf(lat, lon)
        var dLat = transformLon(lon - 105.0, lat - 35.0)
        var dLon = transformLat(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A / sqrtMagic) * cos(radLat) * PI)
        dLon = (dLon * 180.0) / (A / sqrtMagic * PI)
        return doubleArrayOf(lat + dLat, lon + dLon)
    }

    /**
     * 历史错误存储坐标 → 正确 WGS-84（v1.2.0 数据迁移专用）。
     * 旧版点选坐标 q 满足 legacyBrokenWgs2gcj(q) = 用户当时点选的 GCJ 位置，
     * 故先用旧算法还原当时位置，再用修正后的 gcj2wgs 求真 WGS-84。
     */
    fun migrateLegacyWgs(lat: Double, lon: Double): DoubleArray {
        val tapGcj = legacyBrokenWgs2gcj(lat, lon)
        return gcj2wgs(tapGcj[0], tapGcj[1])
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }

    /** WGS-84 → GCJ-02 */
    fun wgs2gcj(lat: Double, lon: Double): DoubleArray {
        if (outOfChina(lat, lon)) return doubleArrayOf(lat, lon)
        val d = delta(lat, lon)
        return doubleArrayOf(lat + d[0], lon + d[1])
    }

    /** GCJ-02 → WGS-84（两轮迭代，误差 < 1 m） */
    fun gcj2wgs(lat: Double, lon: Double): DoubleArray {
        if (outOfChina(lat, lon)) return doubleArrayOf(lat, lon)
        var wgsLat = lat
        var wgsLon = lon
        repeat(3) {
            val g = wgs2gcj(wgsLat, wgsLon)
            wgsLat -= g[0] - lat
            wgsLon -= g[1] - lon
        }
        return doubleArrayOf(wgsLat, wgsLon)
    }

    fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371008.8
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }

    /** 真北方位角 0..360 */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        var deg = Math.toDegrees(atan2(y, x))
        if (deg < 0) deg += 360.0
        return deg
    }

    fun fmt7(v: Double): String = String.format("%.7f", v)
    fun fmt6(v: Double): String = String.format("%.6f", v)

    fun fmtDist(m: Double): String = when {
        m < 0 -> "--"
        m >= 1000 -> String.format("%.2f km", m / 1000.0)
        else -> "${m.roundToInt()} m"
    }

    private fun Double.roundToInt(): Int = Math.round(this).toInt()
}
