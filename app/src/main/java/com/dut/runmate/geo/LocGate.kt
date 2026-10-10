package com.dut.runmate.geo

import android.location.Location
import android.location.LocationManager

/**
 * 定位源坐标归一：所有进入业务层的 Location 统一转为 WGS-84。
 *
 * - GPS / FUSED / PASSIVE：芯片输出即 WGS-84，原样通过；
 * - NETWORK：国产 ROM 的网络定位（高德/百度等后端）返回 GCJ-02，
 *   必须先 gcj2wgs 归一，否则与 GPS 混用时会产生数百米的双重偏移
 *   （大连地区 GCJ-02 偏移量约 450m）。
 *
 * 消费端：RunTrackerService / MapFragment / CalibrateFragment。
 */
object LocGate {

    /** 该 fix 是否来自 NETWORK 定位源（坐标制式为 GCJ-02） */
    fun isNetwork(loc: Location): Boolean =
        loc.provider == LocationManager.NETWORK_PROVIDER

    /** 任意系统定位 → WGS-84 [lat, lon] */
    fun toWgs(loc: Location): DoubleArray =
        if (isNetwork(loc)) GeoKit.gcj2wgs(loc.latitude, loc.longitude)
        else doubleArrayOf(loc.latitude, loc.longitude)

    /**
     * 返回坐标已归一为 WGS-84 的定位（GPS 源直接返回原对象，网络源返回换算后的副本；
     * accuracy / time / provider 等元数据全部保留）。
     */
    fun normalize(loc: Location): Location {
        if (!isNetwork(loc)) return loc
        val w = GeoKit.gcj2wgs(loc.latitude, loc.longitude)
        return Location(loc).apply {
            latitude = w[0]
            longitude = w[1]
        }
    }
}
