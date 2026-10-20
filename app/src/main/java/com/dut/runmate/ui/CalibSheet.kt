package com.dut.runmate.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.view.LayoutInflater
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.dut.runmate.R
import com.dut.runmate.data.Checkpoint
import com.dut.runmate.data.CheckpointStore
import com.dut.runmate.databinding.SheetCalibrateBinding
import com.dut.runmate.geo.GeoKit
import com.dut.runmate.geo.LocGate
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * v1.9.1：现场标定底部面板——地图页 FAB「标定」弹出（原「标定」Tab 并入地图页，
 * 底部导航 4→3）。按用户需求：能在「地图」页一边看地图一边增加点位——面板
 * 只占下半屏，身后的地图/蓝点/精度圈仍然可见；保存后地图即时长出新图钉。
 *
 * 逻辑自 CalibrateFragment 原样平移（精度加权平均/离散半径/仅采 GPS 源/
 * 卫星计数全部不变），差异只有两点：
 *  - 面板自持定位 + 卫星监听（Fragment 的 onResume/onPause 注册改为
 *    dialog show/dismiss 注册注销，弹着面板切到后台不再空烧 GPS）；
 *  - 保存后面板不关闭（tvAvgStatus 提示「已保存」+ 名称自动顺延），
 *    可连续标定下一个点位；关闭面板由用户下拉/点外部决定。
 */
object CalibSheet {

    fun show(fragment: Fragment, store: CheckpointStore, onSaved: () -> Unit) {
        val ctx = fragment.requireContext()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Notice.show(fragment, R.string.run_need_location)
            return
        }
        val dialog = BottomSheetDialog(ctx, R.style.Theme_RunMate_BottomSheet)
        val b = SheetCalibrateBinding.inflate(LayoutInflater.from(ctx), null, false)
        dialog.setContentView(b.root)

        // ---- 标定状态（自 CalibrateFragment 平移） ----
        val samples = mutableListOf<DoubleArray>()   // [lat, lon, acc]
        var averaging = false
        var duration = 20
        var pending: DoubleArray? = null
        var drift = 0.0
        var satsInView = 0
        var satsUsed = 0
        var lastFix: Location? = null

        val ui = CoroutineScope(Dispatchers.Main + Job())
        var avgJob: Job? = null

        fun finishAveraging() {
            averaging = false
            avgJob?.cancel()
            b.btnAvg.text = ctx.getString(R.string.calib_start)
            if (samples.size < 3) {
                b.tvAvgStatus.text = ctx.getString(R.string.calib_need_more)
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

        fun startAveraging() {
            if (lastFix == null) {
                Notice.show(fragment, R.string.calib_need_more)
                return
            }
            samples.clear()
            averaging = true
            b.btnAvg.text = ctx.getString(R.string.calib_stop)
            avgJob = ui.launch {
                var left = duration
                while (left > 0 && averaging) {
                    b.tvAvgStatus.text = ctx.getString(R.string.calib_running, left, samples.size)
                    delay(1000)
                    left--
                }
                if (averaging) finishAveraging()
            }
        }

        b.chip20.isChecked = true
        b.chipsDur.setOnCheckedStateChangeListener { _, ids ->
            duration = when (ids.firstOrNull()) {
                R.id.chip10 -> 10; R.id.chip30 -> 30; else -> 20
            }
        }
        b.btnAvg.setOnClickListener {
            if (averaging) finishAveraging() else startAveraging()
        }
        b.etName.setText(store.defaultName())
        b.btnSave.setOnClickListener {
            val p = pending ?: return@setOnClickListener
            val name = b.etName.text.toString().ifBlank { store.defaultName() }
            store.upsert(
                Checkpoint(
                    name = name,
                    lat = p[0], lon = p[1],
                    // v1.9.0 起 radius 为 Double：均值精度×3、下限 20m（旧版 toInt 取整，现保留小数）
                    radius = (p[2].coerceAtLeast(6.0) * 3).coerceAtLeast(20.0),
                    note = "标定 ±%.1fm·离散%.1fm".format(p[2], drift),
                    source = "gps"
                )
            )
            pending = null
            b.btnSave.isEnabled = false
            b.tvAvgStatus.text = "已保存「$name」"
            Notice.show(fragment, "已保存 $name")
            try { onSaved() } catch (_: Throwable) { }   // 地图重画点位标记
            b.etName.setText(store.defaultName())        // 连续标定：名称自动顺延
        }

        // ---- 实时定位 + 卫星计数（面板自持；dismiss 时全部注销） ----
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val locListener = LocationListener { raw ->
            // 坐标归一：网络定位(GCJ-02)→WGS-84，GPS 原样（v1.2.0，与原标定页一致）
            val loc = LocGate.normalize(raw)
            lastFix = loc
            if (!dialog.isShowing) return@LocationListener
            val la = loc.latitude; val lo = loc.longitude
            b.tvLat.text = "纬度 ${GeoKit.fmt7(la)}"
            b.tvLon.text = "经度 ${GeoKit.fmt7(lo)}"
            val g = GeoKit.wgs2gcj(la, lo)
            b.tvGcj.text = "GCJ-02 ${GeoKit.fmt7(g[0])}, ${GeoKit.fmt7(g[1])}"
            val src = if (LocGate.isNetwork(raw)) "网络" else "GPS"
            b.tvAcc.text = "±${"%.1f".format(loc.accuracy)} m · $src"
            b.tvSats.text = ctx.getString(R.string.satellites, satsUsed, satsInView)
            // 标定采样：仅采 GPS 源（网络定位精度不足且坐标制式不同）
            if (averaging && !LocGate.isNetwork(raw) && loc.accuracy <= 30f) {
                samples.add(doubleArrayOf(la, lo, loc.accuracy.toDouble()))
            }
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener)
        } catch (_: Exception) { }
        try {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 5f, locListener)
        } catch (_: Exception) { }
        var gnssCb: GnssStatus.Callback? = null
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
                lm.registerGnssStatusCallback(ctx.mainExecutor, cb)
            } else {
                @Suppress("DEPRECATION")
                lm.registerGnssStatusCallback(cb)
            }
            gnssCb = cb
        } catch (_: Exception) { }

        dialog.setOnDismissListener {
            averaging = false
            avgJob?.cancel()
            ui.cancel()
            try { lm.removeUpdates(locListener) } catch (_: Exception) { }
            gnssCb?.let { cb ->
                try { lm.unregisterGnssStatusCallback(cb) } catch (_: Exception) { }
            }
        }
        dialog.show()
    }
}
