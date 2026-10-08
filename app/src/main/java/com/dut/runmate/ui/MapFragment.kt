package com.dut.runmate.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.dut.runmate.R
import com.dut.runmate.data.Checkpoint
import com.dut.runmate.data.CheckpointStore
import com.dut.runmate.data.Prefs
import com.dut.runmate.databinding.FragmentMapBinding
import com.dut.runmate.geo.GeoKit
import com.dut.runmate.run.RunBus
import org.json.JSONObject
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import kotlin.math.cos
import kotlin.math.sin

/**
 * 地图选点页：Esri 卫星（可至 20 级）/ OSM 街道切换、长按或点按空白处添加点位、
 * 拖动图标微调、点按图标编辑、检测半径圈、操场环线参考。
 */
class MapFragment : Fragment() {

    private var _b: FragmentMapBinding? = null
    private val b get() = _b!!
    private val prefs by lazy { Prefs.get(requireContext()) }
    private val store by lazy { CheckpointStore.get(requireContext().filesDir) }

    private var myLoc: MyLocationNewOverlay? = null
    private var routeLine: Polyline? = null
    private val circles = mutableListOf<Polygon>()
    private val markers = mutableListOf<Marker>()

    private val esriSat = XYTileSource(
        "EsriSat", 3, 20, 256, ".png",
        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
    )

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
        b.map.minZoomLevel = 11.0
        b.map.controller.setZoom(18.5)
        b.map.controller.setCenter(GeoPoint(39.0853, 121.8085))   // 大工凌水校区
        applyTileSource()

        // 事件层（底部）：空白处点按/长按 → 新增点位
        b.map.overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                if (p != null) openAddSheet(p.latitude, p.longitude)
                return true
            }
            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (p != null) openAddSheet(p.latitude, p.longitude)
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

        // 回到我
        b.fabLocate.setOnClickListener {
            val f = RunBus.state.value.fix
            if (f != null) {
                b.map.controller.animateTo(GeoPoint(f.latitude, f.longitude))
                b.map.controller.setZoom(19.0)
            } else {
                android.widget.Toast.makeText(requireContext(), "暂无定位（先开始跑步或在标定页等待GPS）", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        if (!prefs.mapHintShown) {
            prefs.mapHintShown = true
        } else {
            b.tvMapHint.visibility = View.GONE
        }
        b.tvMapHint.setOnClickListener { it.visibility = View.GONE }
    }

    private fun applyTileSource() {
        b.map.setTileSource(if (prefs.tileSat) esriSat else TileSourceFactory.MAPNIK)
    }

    private fun setRouteVisible(on: Boolean) {
        if (on && routeLine == null) {
            try {
                val txt = requireContext().resources
                    .openRawResource(R.raw.campus_route).bufferedReader().readText()
                val arr = JSONObject(txt).getJSONArray("points")
                val pts = ArrayList<GeoPoint>(arr.length())
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONArray(i)
                    pts.add(GeoPoint(p.getDouble(1), p.getDouble(0)))
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

    private fun addCpOverlays(cp: Checkpoint) {
        // 半径圈
        val poly = Polygon(b.map)
        val pts = ArrayList<GeoPoint>(49)
        val r = cp.radius.toDouble()
        val dLat = r / 111320.0
        val dLon = r / (111320.0 * cos(Math.toRadians(cp.lat)))
        for (i in 0..48) {
            val ang = 2 * Math.PI * i / 48
            pts.add(GeoPoint(cp.lat + dLat * sin(ang), cp.lon + dLon * cos(ang)))
        }
        poly.points = pts
        poly.outlinePaint.color = ContextCompat.getColor(requireContext(), R.color.md_secondary)
        poly.outlinePaint.strokeWidth = 2.5f
        poly.fillPaint.color = Color.argb(26, 17, 81, 255)
        circles.add(poly)
        b.map.overlays.add(poly)

        // 图钉
        val mk = Marker(b.map)
        mk.position = GeoPoint(cp.lat, cp.lon)
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
                cp.lat = marker.position.latitude
                cp.lon = marker.position.longitude
                store.upsert(cp)
                rebuild()
            }
            override fun onMarkerDragStart(marker: Marker) { }
        })
        markers.add(mk)
        b.map.overlays.add(mk)
    }

    private fun openAddSheet(lat: Double, lon: Double) {
        CheckpointSheet.show(this, store, null, lat, lon) { rebuild() }
    }

    private fun openEditSheet(cp: Checkpoint) {
        CheckpointSheet.show(this, store, cp) { rebuild() }
    }

    override fun onResume() {
        super.onResume()
        rebuild()
        if (myLoc == null &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            myLoc = MyLocationNewOverlay(b.map).also {
                it.enableMyLocation()
                b.map.overlays.add(it)
            }
        }
    }

    override fun onPause() {
        myLoc?.disableMyLocation()
        super.onPause()
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }
}
