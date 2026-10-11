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
import com.dut.runmate.R
import com.dut.runmate.data.Checkpoint
import com.dut.runmate.data.CheckpointStore
import com.dut.runmate.data.Prefs
import com.dut.runmate.databinding.FragmentCalibrateBinding
import com.dut.runmate.databinding.ItemCpEditBinding
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
        // v1.8.3：CheckpointSheet 回调可能晚于视图销毁到达（用户在弹窗开着时切页），
        // 与 startAveraging 协程同源的 NPE 防护。
        val bb = _b ?: return
        // v1.8.4：RecyclerView(wrap_content) 嵌 ScrollView 在部分 ROM 只测出一个 item
        // 高度（用户实测只能看到一个点位），改为 LinearLayout 逐条渲染，全部展开。
        bb.layCalib.removeAllViews()
        val inf = layoutInflater
        store.all().forEach { cp ->
            val ib = ItemCpEditBinding.inflate(inf, bb.layCalib, false)
            ib.tvName.text = cp.name
            val g = GeoKit.wgs2gcj(cp.lat, cp.lon)
            ib.tvCoords.text =
                "WGS ${GeoKit.fmt7(cp.lat)}, ${GeoKit.fmt7(cp.lon)}\n" +
                "GCJ ${GeoKit.fmt7(g[0])}, ${GeoKit.fmt7(g[1])} · 半径${cp.radius}m"
            ib.btnDelete.setOnClickListener {
                store.delete(cp.id)
                refreshList()
            }
            ib.root.setOnClickListener {
                CheckpointSheet.show(this@CalibrateFragment, store, cp) { refreshList() }
            }
            bb.layCalib.addView(ib.root)
        }
        bb.etName.setText(store.defaultName())
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
                // v1.8.3 崩溃修复（用户实报 NPE @ getB/startAveraging$1）：
                // 协程 delay 后视图可能已销毁（切页/转屏 → onDestroyView 置 _b=null），
                // 旧版 _b!! 在后台恢复时必崩。改为取局部引用，视图没了直接停。
                val bb = _b ?: break
                bb.tvAvgStatus.text = getString(R.string.calib_running, left, samples.size)
                delay(1000)
                left--
            }
            if (averaging) finishAveraging(true)
        }
    }

    private fun finishAveraging(completed: Boolean) {
        averaging = false
        avgJob?.cancel()
        // v1.8.3：视图已销毁（协程延迟恢复场景）时只停状态不碰 UI——
        // 均值照常算出存 pending，用户切回页面后仍可保存，不再崩溃。
        val bb = _b
        bb?.btnAvg?.text = getString(R.string.calib_start)
        if (samples.size < 3) {
            bb?.tvAvgStatus?.text = getString(R.string.calib_need_more)
            bb?.btnSave?.isEnabled = false
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
        bb?.tvAvgStatus?.text =
            "均值 ${GeoKit.fmt7(meanLat)}, ${GeoKit.fmt7(meanLon)}\n" +
            "GCJ ${GeoKit.fmt7(g[0])}, ${GeoKit.fmt7(g[1])}\n" +
            "±${"%.1f".format(meanAcc)} m · 离散 ${"%.1f".format(maxDrift)} m · ${samples.size} 点"
        bb?.btnSave?.isEnabled = true
    }

    private var pending: DoubleArray? = null
    private var drift = 0.0

    private fun savePoint() {
        val bs = _b ?: return      // v1.8.4：与 refreshList 同源的判空防护
        val p = pending ?: return
        val name = bs.etName.text.toString().ifBlank { store.defaultName() }
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
        bs.btnSave.isEnabled = false
        bs.tvAvgStatus.text = "已保存「$name」"
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
        // v1.8.3：先停采样协程再置空 binding——旧版只置 _b=null，
        // 协程还在 delay 循环里，恢复时 _b!! 直接 NPE（用户实报崩溃堆栈）。
        averaging = false
        avgJob?.cancel()
        avgJob = null
        _b = null
        super.onDestroyView()
    }
}
