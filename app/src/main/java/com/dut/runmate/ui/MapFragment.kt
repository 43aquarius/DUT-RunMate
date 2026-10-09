package com.dut.runmate.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.dut.runmate.R
import com.dut.runmate.data.Checkpoint
import com.dut.runmate.data.CheckpointStore
import com.dut.runmate.data.Prefs
import com.dut.runmate.databinding.FragmentMapBinding
import com.dut.runmate.geo.GeoKit
import com.dut.runmate.geo.LocGate
import com.dut.runmate.map.AmapTileSource
import com.dut.runmate.run.RunBus
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import kotlin.math.cos
import kotlin.math.sin

/**
 * 地图选点页。
 *
 * 图源为高德瓦片（GCJ-02，国内秒开）：卫星实测到 18 级、街道（矢量）到 20 级；
 * 卫星 19~20 级由 osmdroid 自动放大 18 级瓦片。
 *
 * 坐标策略（关键）：所有点位一律以 WGS-84（原始 GPS）存储；
 * 地图显示时 WGS-84 → GCJ-02（toDisplay），点选/拖动落点时 GCJ-02 → WGS-84（fromDisplay）。
 * v1.2.0 起：定位源经 LocGate 归一（网络定位 GCJ-02 → WGS-84），GPS 优先展示，
 * 蓝点附带精度圈，网络定位显示为灰点。
 *
 * 选点流程（v1.2.0）：点按/长按地图 → 放置橙色准星（可拖动微调）→
 * 底部确认栏核对 WGS-84 坐标 → 「确认添加」打开点位信息表单。
 */
class MapFragment : Fragment() {

    private var _b: FragmentMapBinding? = null
    private val b get() = _b!!
    private val prefs by lazy { Prefs.get(requireContext()) }
    private val store by lazy { CheckpointStore.get(requireContext().filesDir) }

    private var routeLine: Polyline? = null
    private val circles = mutableListOf<Polygon>()
    private val markers = mutableListOf<Marker>()
    private var myLocMarker: Marker? = null
    private var myAccCircle: Polygon? = null
    private var locMgr: LocationManager? = null

    // 选点（准星）状态
    private var pickMarker: Marker? = null
    private var pickWgs: DoubleArray? = null

    // 定位缓存（坐标已经 LocGate 归一为 WGS-84）
    private var lastGpsFix: Location? = null
    private var lastNetFix: Location? = null

    // 大工凌水校区中心（WGS-84）
    private val campusWgs = doubleArrayOf(39.0853, 121.8085)

    /** WGS-84（存储）→ GCJ-02（地图显示） */
    private fun toDisplay(lat: Double, lon: Double): GeoPoint {
        val g = GeoKit.wgs2gcj(lat, lon)
        return GeoPoint(g[0], g[1])
    }

    /** GCJ-02（地图交互）→ WGS-84（存储） */
    private fun fromDisplay(p: GeoPoint): DoubleArray =
        GeoKit.gcj2wgs(p.latitude, p.longitude)

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentMapBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        super.onViewCreated(v, savedInstanceState)

        b.map.setUseDataConnection(true)
        b.map.setMultiTouchControls(true)
        b.map.setBuiltInZoomControls(true)
        b.map.setTilesScaledToDpi(true)
        b.map.maxZoomLevel = 20.0
        b.map.minZoomLevel = 3.0
        b.map.controller.setZoom(18.0)
        b.map.controller.setCenter(toDisplay(campusWgs[0], campusWgs[1]))
        applyTileSource()

        // 事件层（底部）：空白处点按/长按 → 放置选点准星（不直接建点，先确认）
        b.map.overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                if (p != null) placePick(p)
                return true
            }
            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (p != null) placePick(p)
                return true
            }
        }))

        // 瓦片切换
        b.chipSat.isChecked = prefs.tileSat
        b.chipStreet.isChecked = !prefs.tileSat
        b.chipSat.setOnCheckedChangeListener { _, c2 -> if (c2) { prefs.tileSat = true; applyTileSource() } }
        b.chipStreet.setOnCheckedChangeListener { _, c2 -> if (c2) { prefs.tileSat = false; applyTileSource() } }

        // 操场环线
        b.chipRoute.isChecked = false
        b.chipRoute.setOnCheckedChangeListener { _, c2 -> setRouteVisible(c2) }

        // 回到我（GPS 为 WGS-84，显示前转 GCJ-02）
        b.fabLocate.setOnClickListener { locateMe() }

        // 我的位置（蓝点，坐标已归一 WGS-84 → GCJ-02 显示；不使用 MyLocationNewOverlay）
        viewLifecycleOwner.lifecycleScope.launch {
            RunBus.state.collect { st ->
                val fix = st.fix ?: return@collect
                showMyLocation(fix.latitude, fix.longitude, fix.accuracy, LocGate.isNetwork(fix))
            }
        }

        // 选点确认栏
        b.btnPickOk.setOnClickListener { confirmPick() }
        b.btnPickCancel.setOnClickListener { clearPick() }

        if (!prefs.mapHintShown) {
            prefs.mapHintShown = true
        } else {
            b.tvMapHint.visibility = View.GONE
        }
        b.tvMapHint.setOnClickListener { it.visibility = View.GONE }
    }

    private fun applyTileSource() {
        b.map.setTileSource(if (prefs.tileSat) AmapTileSource.SATELLITE else AmapTileSource.STREET)
    }

    private fun locateMe() {
        val f = RunBus.state.value.fix ?: bestFix()
        if (f != null) {
            b.map.controller.animateTo(toDisplay(f.latitude, f.longitude))
            b.map.controller.setZoom(19.0)
            if (LocGate.isNetwork(f)) {
                android.widget.Toast.makeText(
                    requireContext(), R.string.map_loc_net_toast, android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            android.widget.Toast.makeText(
                requireContext(), R.string.map_loc_none, android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** GPS 新鲜（30s 内）优先，否则用最近一次网络定位 */
    private fun bestFix(): Location? {
        val g = lastGpsFix
        if (g != null && System.nanoTime() - g.elapsedRealtimeNanos < 30_000_000_000L) return g
        return lastNetFix ?: g
    }

    private val locListener = LocationListener { raw: Location ->
        if (_b == null) return@LocationListener
        val loc = LocGate.normalize(raw)
        if (LocGate.isNetwork(raw)) lastNetFix = loc else lastGpsFix = loc
        bestFix()?.let {
            showMyLocation(it.latitude, it.longitude, it.accuracy, LocGate.isNetwork(it))
        }
    }

    private fun setRouteVisible(on: Boolean) {
        if (on && routeLine == null) {
            try {
                val txt = requireContext().resources
                    .openRawResource(R.raw.campus_route).bufferedReader().readText()
                val arr = JSONObject(txt).getJSONArray("points")
                val pts = ArrayList<GeoPoint>(arr.length())
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONArray(i)          // [lon, lat]（WGS-84）
                    pts.add(toDisplay(p.getDouble(1), p.getDouble(0)))
                }
                routeLine = Polyline(b.map).apply {
                    outlinePaint.color = Color.parseColor("#FF1151FF")
                    outlinePaint.strokeWidth = 4f
                    setPoints(pts)
                }
                b.map.overlays.add(routeLine)
            } catch (_: Exception) { }
        }
        routeLine?.let { l ->
            val idx = b.map.overlays.indexOf(l)
            if (on && idx < 0) b.map.overlays.add(l)
            if (!on && idx >= 0) b.map.overlays.remove(l)
        }
        b.map.invalidate()
    }

    private fun rebuild() {
        // 清除旧
        circles.forEach { b.map.overlays.remove(it) }
        markers.forEach { b.map.overlays.remove(it) }
        circles.clear(); markers.clear()

        store.all().forEach { cp -> addCpOverlays(cp) }
        b.map.invalidate()
    }

    /** 以 (cLat,cLon) 为中心、r 米为半径的圆周点（显示坐标系内画，半径误差 < 1cm 可忽略） */
    private fun circlePts(cLat: Double, cLon: Double, r: Double): ArrayList<GeoPoint> {
        val pts = ArrayList<GeoPoint>(49)
        val dLat = r / 111320.0
        val dLon = r / (111320.0 * cos(Math.toRadians(cLat)))
        for (i in 0..48) {
            val ang = 2 * Math.PI * i / 48
            pts.add(GeoPoint(cLat + dLat * sin(ang), cLon + dLon * cos(ang)))
        }
        return pts
    }

    private fun addCpOverlays(cp: Checkpoint) {
        // 存储的 WGS-84 中心 → GCJ-02 显示中心
        val g = GeoKit.wgs2gcj(cp.lat, cp.lon)
        val cLat = g[0]; val cLon = g[1]

        // 半径圈
        val poly = Polygon(b.map)
        poly.points = circlePts(cLat, cLon, cp.radius.toDouble())
        poly.outlinePaint.color = ContextCompat.getColor(requireContext(), R.color.md_secondary)
        poly.outlinePaint.strokeWidth = 2.5f
        poly.fillPaint.color = Color.argb(26, 17, 81, 255)
        circles.add(poly)
        b.map.overlays.add(poly)

        // 图钉
        val mk = Marker(b.map)
        mk.position = GeoPoint(cLat, cLon)
        mk.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        mk.isDraggable = true
        val d = ContextCompat.getDrawable(requireContext(), R.drawable.ic_place)!!.mutate()
        d.setTint(ContextCompat.getColor(requireContext(), R.color.md_error))
        mk.icon = d
        mk.title = cp.name
        mk.setOnMarkerClickListener { _, _ -> openEditSheet(cp); true }
        mk.setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
            override fun onMarkerDrag(marker: Marker) { }
            override fun onMarkerDragEnd(marker: Marker) {
                // 拖动落点为 GCJ-02 → 转 WGS-84 存储
                val w = fromDisplay(marker.position)
                cp.lat = w[0]
                cp.lon = w[1]
                store.upsert(cp)
                rebuild()
            }
            override fun onMarkerDragStart(marker: Marker) { }
        })
        markers.add(mk)
        b.map.overlays.add(mk)
    }

    // ---------- 选点（准星）流程 ----------

    /** 在地图上放置/移动选点准星，并弹出确认栏 */
    private fun placePick(p: GeoPoint) {
        val bb = _b ?: return
        bb.tvMapHint.visibility = View.GONE
        if (pickMarker == null) {
            val mk = Marker(bb.map)
            mk.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)   // 准星几何中心 = 精确坐标
            mk.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_crosshair)
            mk.isDraggable = true
            mk.setOnMarkerClickListener { _, _ -> true }               // 消费点击，不弹窗
            mk.setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                override fun onMarkerDrag(marker: Marker) { updatePickCoords(marker.position) }
                override fun onMarkerDragEnd(marker: Marker) { updatePickCoords(marker.position) }
                override fun onMarkerDragStart(marker: Marker) { }
            })
            pickMarker = mk
            bb.map.overlays.add(mk)
        }
        pickMarker?.position = p
        bb.pickBar.visibility = View.VISIBLE
        updatePickCoords(p)
        bb.map.invalidate()
    }

    private fun updatePickCoords(p: GeoPoint) {
        val bb = _b ?: return
        val w = fromDisplay(p)
        pickWgs = w
        bb.tvPickCoords.text = getString(
            R.string.map_pick_coords, GeoKit.fmt6(w[0]), GeoKit.fmt6(w[1])
        )
    }

    private fun confirmPick() {
        val w = pickWgs
        clearPick()
        if (w != null) openAddSheet(w[0], w[1])
    }

    private fun clearPick() {
        val bb = _b ?: return
        pickMarker?.let { bb.map.overlays.remove(it) }
        pickMarker = null
        pickWgs = null
        bb.pickBar.visibility = View.GONE
        bb.map.invalidate()
    }

    private fun openAddSheet(lat: Double, lon: Double) {
        CheckpointSheet.show(this, store, null, lat, lon) { rebuild() }
    }

    private fun openEditSheet(cp: Checkpoint) {
        CheckpointSheet.show(this, store, cp) { rebuild() }
    }

    /** 把 WGS-84 定位画到地图：GPS 蓝点 / 网络灰点 + 精度圈 */
    private fun showMyLocation(lat: Double, lon: Double, acc: Float, network: Boolean) {
        val bb = _b ?: return
        val dp = toDisplay(lat, lon)

        // 精度圈（数值有效时）
        if (acc > 0f && acc < 500f) {
            val circle = myAccCircle ?: Polygon(bb.map).also { p ->
                p.outlinePaint.color = Color.argb(170, 33, 150, 243)
                p.outlinePaint.strokeWidth = 1.2f
                p.fillPaint.color = Color.argb(22, 33, 150, 243)
                myAccCircle = p
                bb.map.overlays.add(p)
            }
            circle.points = circlePts(dp.latitude, dp.longitude, acc.toDouble())
        }

        val mk = myLocMarker ?: Marker(bb.map).also { m ->
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            m.isDraggable = false
            myLocMarker = m
            bb.map.overlays.add(m)
        }
        mk.icon = ContextCompat.getDrawable(
            requireContext(), if (network) R.drawable.ic_myloc_net else R.drawable.ic_myloc
        )
        mk.position = dp
        bb.map.invalidate()
    }

    override fun onResume() {
        super.onResume()
        rebuild()
        // 不开跑步服务也能看到自身位置：独立监听系统定位（坐标经 LocGate 归一）
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            try {
                locMgr = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
                locMgr?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 2f, locListener)
                locMgr?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 5f, locListener)
            } catch (_: Exception) { }
        }
    }

    override fun onPause() {
        locMgr?.removeUpdates(locListener)
        locMgr = null
        super.onPause()
    }

    override fun onDestroyView() {
        _b = null
        // 视图销毁：清空所有绑定旧 MapView 的覆盖物引用，视图重建后全部重画
        // （否则蓝点/精度圈/准星持有已销毁地图的 Marker，切页返回后不再显示）
        routeLine = null
        circles.clear()
        markers.clear()
        myLocMarker = null
        myAccCircle = null
        pickMarker = null
        pickWgs = null
        super.onDestroyView()
    }
}
