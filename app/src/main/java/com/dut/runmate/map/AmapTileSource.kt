package com.dut.runmate.map

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

/**
 * 高德地图瓦片源（GCJ-02 坐标系，国内访问稳定、无需 Key）。
 *
 * 说明：
 * - osmdroid 自带的 XYTileSource 只支持「路径式」URL（…/{z}/{x}/{y}.png），
 *   高德是「查询串式」URL，因此这里覆写 getTileURLString 自行拼接；
 * - 四个子域轮询（webst01~04 / wprd01~04），加快并发加载；
 * - 高德瓦片为 GCJ-02（火星坐标），与本 App 以 WGS-84 存储的点位相差约百米级，
 *   显示/交互的坐标换算统一在 MapFragment 的 toDisplay()/fromDisplay() 完成。
 */
class AmapTileSource(
    name: String,
    minZoom: Int,
    maxZoom: Int,
    tileSize: Int,
    fileExt: String,
    private val hosts: Array<String>,
    private val queryPrefix: String
) : OnlineTileSourceBase(name, minZoom, maxZoom, tileSize, fileExt, arrayOf("https://${hosts[0]}")) {

    private var seq = 0

    private fun nextHost(): String = synchronized(this) {
        val h = hosts[seq % hosts.size]
        seq++
        return h
    }

    override fun getTileURLString(pMapTileIndex: Long): String {
        val z = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "https://${nextHost()}/$queryPrefix&x=$x&y=$y&z=$z"
    }

    companion object {
        private val WEBST = arrayOf(
            "webst01.is.autonavi.com", "webst02.is.autonavi.com",
            "webst03.is.autonavi.com", "webst04.is.autonavi.com"
        )
        private val WPRD = arrayOf(
            "wprd01.is.autonavi.com", "wprd02.is.autonavi.com",
            "wprd03.is.autonavi.com", "wprd04.is.autonavi.com"
        )

        /**
         * 卫星影像（style=6）。
         * 国内大部分区域实际只发到 18 级（更深层级服务器返回占位图），
         * 因此 maxZoom 定为 18；19~20 级由 osmdroid 的 MapTileApproximater
         * 自动放大已缓存的 18 级瓦片（超源缩放），仍然可用且不会加载到占位图。
         */
        val SATELLITE = AmapTileSource(
            "AmapSat", 3, 18, 256, ".jpg",
            WEBST, "appmaptile?style=6"
        )

        /** 街道图（wprd 矢量渲染，实测到 20 级均有数据，与高德网页版同源） */
        val STREET = AmapTileSource(
            "AmapVec", 3, 20, 256, ".png",
            WPRD, "appmaptile?lang=zh_cn&size=1&scl=1&style=7&ltype=0"
        )
    }
}
