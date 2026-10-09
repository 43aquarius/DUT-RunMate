package com.dut.runmate.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.dut.runmate.R
import com.dut.runmate.data.Checkpoint
import com.dut.runmate.data.CheckpointStore
import com.dut.runmate.data.Prefs
import com.dut.runmate.databinding.FragmentCalibrateBinding
import com.dut.runmate.geo.GeoKit
import com.dut.runmate.geo.LocGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * 现场标定页：实时 GPS 显示 + 定点平均采集 + 保存为打卡点。
 */
class CalibrateFragment : Fragment() {

    private var _b: FragmentCalibrateBinding? = null
    private val b get() = _b!!
    private val prefs by lazy { Prefs.get(requireContext()) }
    private val store by lazy { CheckpointStore.get(requireContext().filesDir) }
    private lateinit var adapter: CalibPointAdapter

    private val samples = mutableListOf<DoubleArray>()   // [lat, lon, acc]
    private var averaging = false
    private var avgJob: Job? = null
    private var duration = 20
    private var lastFix: Location? = null

    private val ui = CoroutineScope(Dispatchers.Main + Job())

    private val locListener = LocationListener { loc -> onFix(loc) }

    private var satsInView = 0
    private var satsUsed = 0
    private var gnssCb: Any? = null

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentCalibrateBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        super.onViewCreated(v, savedInstanceState)
        adapter = CalibPointAdapter(store) { cp -> CheckpointSheet.show(this, store, cp) { refreshList() } }
        b.rvCalib.adapter = adapter
        b.rvCalib.layoutManager = LinearLayoutManager(requireContext())

        b.chip20.isChecked = true
        b.chipsDur.setOnCheckedStateChangeListener { _, ids ->
            duration = when (ids.firstOrNull()) {
                R.id.chip10 -> 10; R.id.chip30 -> 30; else -> 20
            }
        }

        b.btnAvg.setOnClickListener {
            if (averaging) finishAveraging(true)
            else startAveraging()
        }
        b.btnSave.setOnClickListener { savePoint() }
        b.etName.setText(store.defaultName())
        refreshList()
    }

    private fun refreshList() {
        adapter.submit(store.all())
        b.etName.setText(store.defaultName())
    }

    private fun startAveraging() {
        if (lastFix == null) {
            Toast.makeText(requireContext(), R.string.calib_need_more, Toast.LENGTH_SHORT).show()
            return
        }
        samples.clear()
        averaging = true
        b.btnAvg.text = getString(R.string.calib_stop)
        avgJob = ui.launch {
            var left = duration
            while (left > 0 && averaging) {
                b.tvAvgStatus.text = getString(R.string.calib_running, left, samples.size)
                delay(1000)
                left--
            }
            if (averaging) finishAveraging(true)
        }
    }

    private fun finishAveraging(completed: Boolean) {
        averaging = false
        avgJob?.cancel()
        b.btnAvg.text = getString(R.string.calib_start)
        if (samples.size < 3) {
            b.tvAvgStatus.text = getString(R.string.calib_need_more)
            b.btnSave.isEnabled = false
            return
        }
        // 精度加权平均
        var wSum = 0.0
        var la = 0.0; var lo = 0.0; var acc = 0.0
        for (s in samples) {
            val w = 1.0 / (s[2].coerceAtLeast(1.0))
            wSum += w
            la += s[0] * w; lo += s[1] * w; acc += s[2] * w
        }
        val meanLat = la / wSum; val meanLon = lo / wSum; val meanAcc = acc / wSum
        // 离散半径
        var maxDrift = 0.0
        for (s in samples) {
            val d = GeoKit.dist(meanLat, meanLon, s[0], s[1])
            if (d > maxDrift) maxDrift = d
        }
        pending = doubleArrayOf(meanLat, meanLon, meanAcc)
        drift = maxDrift
        val g = GeoKit.wgs2gcj(meanLat, meanLon)
        b.tvAvgStatus.text =
            "均值 ${GeoKit.fmt7(meanLat)}, ${GeoKit.fmt7(meanLon)}\n" +
            "GCJ ${GeoKit.fmt7(g[0])}, ${GeoKit.fmt7(g[1])}\n" +
            "±${"%.1f".format(meanAcc)} m · 离散 ${"%.1f".format(maxDrift)} m · ${samples.size} 点"
        b.btnSave.isEnabled = true
    }

    private var pending: DoubleArray? = null
    private var drift = 0.0

    private fun savePoint() {
        val p = pending ?: return
        val name = b.etName.text.toString().ifBlank { store.defaultName() }
        store.upsert(
            Checkpoint(
                name = name,
                lat = p[0], lon = p[1],
                radius = ((p[2].coerceAtLeast(6.0) * 3).toInt().coerceAtLeast(20)),
                note = "标定 ±%.1fm·离散%.1fm".format(p[2], drift),
                source = "gps"
            )
        )
        pending = null
        b.btnSave.isEnabled = false
        b.tvAvgStatus.text = "已保存「$name」"
        Toast.makeText(requireContext(), "已保存 $name", Toast.LENGTH_SHORT).show()
        refreshList()
    }

    private fun onFix(raw: Location) {
        // 坐标归一：网络定位(GCJ-02)→WGS-84，GPS 原样（v1.2.0）
        val loc = LocGate.normalize(raw)
        lastFix = loc
        if (_b == null) return
        val la = loc.latitude; val lo = loc.longitude
        b.tvLat.text = "纬度 ${GeoKit.fmt7(la)}"
        b.tvLon.text = "经度 ${GeoKit.fmt7(lo)}"
        val g = GeoKit.wgs2gcj(la, lo)
        b.tvGcj.text = "GCJ-02 ${GeoKit.fmt7(g[0])}, ${GeoKit.fmt7(g[1])}"
        val src = if (LocGate.isNetwork(raw)) "网络" else "GPS"
        b.tvAcc.text = "±${"%.1f".format(loc.accuracy)} m · $src"
        b.tvSats.text = getString(R.string.satellites, satsUsed, satsInView)
        // 标定采样：仅采 GPS 源（网络定位精度不足且坐标制式不同）
        if (averaging && !LocGate.isNetwork(raw) && loc.accuracy <= 30f) {
            samples.add(doubleArrayOf(la, lo, loc.accuracy.toDouble()))
        }
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            val lm = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener)
            } catch (_: Exception) { }
            try {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 5f, locListener)
            } catch (_: Exception) { }
            // 卫星计数
            try {
                val cb = object : GnssStatus.Callback() {
                    override fun onSatelliteStatusChanged(status: GnssStatus) {
                        satsInView = status.satelliteCount
                        var used = 0
                        for (i in 0 until status.satelliteCount) {
                            if (status.usedInFix(i)) used++
                        }
                        satsUsed = used
                    }
                }
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    lm.registerGnssStatusCallback(requireContext().mainExecutor, cb)
                } else {
                    @Suppress("DEPRECATION")
                    lm.registerGnssStatusCallback(cb)
                }
                gnssCb = cb
            } catch (_: Exception) { }
        }
    }

    override fun onPause() {
        try {
            val lm = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.removeUpdates(locListener)
            gnssCb?.let { lm.unregisterGnssStatusCallback(it as GnssStatus.Callback) }
        } catch (_: Exception) { }
        super.onPause()
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }
}
