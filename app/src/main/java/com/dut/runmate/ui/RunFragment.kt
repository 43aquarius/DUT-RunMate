package com.dut.runmate.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.dut.runmate.R
import com.dut.runmate.data.CheckpointStore
import com.dut.runmate.data.Prefs
import com.dut.runmate.databinding.FragmentRunBinding
import com.dut.runmate.geo.GeoKit
import com.dut.runmate.run.RunBus
import com.dut.runmate.service.RunTrackerService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class RunFragment : Fragment() {

    private var _b: FragmentRunBinding? = null
    private val b get() = _b!!
    private val prefs by lazy { Prefs.get(requireContext()) }
    private val store by lazy { CheckpointStore.get(requireContext().filesDir) }
    private lateinit var adapter: RunPointAdapter

    private var summaryShown = false

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) startService()
        else if (_b != null) {
            android.widget.Toast.makeText(requireContext(), "需要定位权限", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentRunBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        super.onViewCreated(v, savedInstanceState)
        adapter = RunPointAdapter(store)
        b.rvPoints.adapter = adapter
        b.rvPoints.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(requireContext())

        b.radar.setTint(ContextCompat.getColor(requireContext(), R.color.md_primary))
        b.tvRunHint.visibility = if (store.count > 0) View.GONE else View.VISIBLE

        b.btnRun.setOnClickListener {
            if (RunBus.state.value.running) {
                requireContext().startService(
                    Intent(requireContext(), RunTrackerService::class.java).setAction(RunTrackerService.ACT_STOP)
                )
            } else {
                askAndStart()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            RunBus.state.collect { render(it) }
        }
    }

    private fun askAndStart() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) startService()
        else permLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun startService() {
        val ctx = requireContext()
        RunBus.resetSession()
        summaryShown = false
        val i = Intent(ctx, RunTrackerService::class.java).setAction(RunTrackerService.ACT_START)
        if (android.os.Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(ctx, i)
        else ctx.startService(i)
    }

    private fun render(st: RunBus.UiState) {
        if (_b == null) return

        // 按钮
        b.btnRun.text = if (st.running) getString(R.string.run_stop) else getString(R.string.run_start)
        b.btnRun.setIconResource(if (st.running) R.drawable.ic_stop else R.drawable.ic_play)
        (b.btnRun as com.google.android.material.button.MaterialButton).apply {
            if (st.running) {
                setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.md_error))
                setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
                iconTint = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.white))
            } else {
                setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.md_primary))
                setTextColor(ContextCompat.getColor(requireContext(), R.color.md_on_primary))
                iconTint = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.md_on_primary))
            }
        }

        b.tvRunHint.visibility = if (store.count > 0 || st.running) View.GONE else View.VISIBLE
        adapter.submit(st.snaps)

        // 状态大字
        val n = st.nearest
        val inZone = st.snaps.firstOrNull { it.state == RunBus.CpState.IN_ZONE }
        val missed = st.snaps.firstOrNull { it.state == RunBus.CpState.MISSED }
        val head = when {
            st.running && missed != null -> getString(R.string.state_missed)
            st.running && inZone != null -> getString(R.string.state_inzone)
            st.running && st.snaps.any { it.state == RunBus.CpState.GRACE } -> getString(R.string.state_grace)
            st.running && n != null -> "距 ${n.cp.name}"
            st.running -> getString(R.string.gps_waiting)
            else -> getString(R.string.gps_waiting)
        }
        b.tvHeadline.text = if (st.running) head else getString(R.string.gps_waiting)
        b.tvBigDist.text = if (st.running && n != null) "${n.distM}" else "--"
        b.tvTargetName.text = when {
            st.running && inZone != null -> inZone.cp.name
            st.running && n != null -> n.cp.name + " · " + getString(R.string.state_near)
            st.running && st.fix == null -> "等待首个定位"
            else -> "—"
        }

        // 雷达箭头：指向最近待检点（有航向时相对行进方向）
        val fix = st.fix
        if (st.running && n != null && fix != null) {
            val brg = GeoKit.bearing(fix.latitude, fix.longitude, n.cp.lat, n.cp.lon).toFloat()
            val course = if (fix.hasBearing() && fix.speed > 0.8f) fix.bearing else 0f
            b.radar.setHeading(brg - course)
        } else {
            b.radar.setHeading(0f)
        }

        // 接口距离
        val apiOn = prefs.apiEnabled
        b.tvApiDist.text = when {
            !apiOn -> getString(R.string.api_off)
            else -> getString(R.string.api_distance_now)
        }
        b.tvApiDistVal.text = when {
            !apiOn -> "--"
            st.apiDistance != null -> String.format("%.1f m", st.apiDistance)
            st.apiErr != null -> "!"
            else -> "--"
        }

        // 统计
        b.tvTime.text = st.stats.timeStr
        b.tvOdometer.text = String.format("%.2f km", st.stats.meters / 1000.0)
        b.tvPace.text = st.stats.pace

        // 总结弹窗
        val sum = st.summary
        if (sum != null && !summaryShown) {
            summaryShown = true
            val ok = sum.confirmed.joinToString("、").ifEmpty { "无" }
            val miss = sum.missed.joinToString("、").ifEmpty { "无" }
            val body = "已确认：$ok\n未确认（漏卡）：$miss\n用时 ${sum.stats.timeStr} · 自测 ${String.format("%.2f km", sum.stats.meters / 1000.0)}"
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("本次跑步小结")
                .setMessage(body)
                .setPositiveButton(R.string.save, null)
                .show()
        }
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }
}
