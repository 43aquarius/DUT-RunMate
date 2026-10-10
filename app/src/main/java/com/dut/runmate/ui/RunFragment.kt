package com.dut.runmate.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.dut.runmate.R
import com.dut.runmate.data.CheckpointStore
import com.dut.runmate.data.Prefs
import com.dut.runmate.data.api.ApiStore
import com.dut.runmate.databinding.FragmentRunBinding
import com.dut.runmate.geo.GeoKit
import com.dut.runmate.geo.LocGate
import com.dut.runmate.net.Http
import com.dut.runmate.run.RunBus
import com.dut.runmate.service.RunTrackerService
import com.dut.runmate.util.SoftLog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * 跑步页。
 *
 * v1.3.0 起「未开始跑步也能实时定位查看」（定位预览）：
 * Fragment 自持 GPS/网络双源监听（坐标经 LocGate 归一，GPS 30s 内优先），
 * 实时刷新位置/精度/最近点位距离 + 雷达方向；开始跑步后由前台服务接管 RunBus。
 *
 * 服务端距离区（右上）：跑步中点按 = 立即核对（触发服务即时轮询一次）；
 * 未跑步点按 = 直接查询一次 findExtExercise 并显示官方距离。
 */
class RunFragment : Fragment() {

    private var _b: FragmentRunBinding? = null
    private val b get() = _b!!
    private val prefs by lazy { Prefs.get(requireContext()) }
    private val store by lazy { CheckpointStore.get(requireContext().filesDir) }
    private val apiStore by lazy { ApiStore.get(requireContext().filesDir) }
    private lateinit var adapter: RunPointAdapter

    private var summaryShown = false

    /** v1.6.1：未跑步时的「查询服务端距离」在逓中标记（防重复点击） */
    private var querying = false

    // ---- 定位预览（v1.3.0） ----
    private var locMgr: LocationManager? = null
    private var pvGps: Location? = null
    private var pvNet: Location? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) startService()
        else if (_b != null) {
            safeToast(R.string.run_need_location)
        }
    }

    /** v1.6.1：Toast 隔离——个别 ROM 的 Toast 渲染异常不再可能闪退整个 App */
    private fun safeToast(resId: Int) {
        try {
            Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentRunBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        super.onViewCreated(v, savedInstanceState)
        querying = false        // v1.6.1：页面重建时重置（防协程早退后标志卡死）
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

        // 服务端距离：点按核对/查询
        b.layoutApi.setOnClickListener { onApiTap() }

        viewLifecycleOwner.lifecycleScope.launch {
            RunBus.state.collect { render(it) }
        }
    }

    // ---------- 定位预览 ----------

    /** GPS 30s 内新鲜优先，否则网络定位兜底（坐标均已 LocGate 归一 WGS-84） */
    private fun bestPreviewFix(): Location? {
        val g = pvGps
        if (g != null && System.nanoTime() - g.elapsedRealtimeNanos < 30_000_000_000L) return g
        return pvNet ?: g
    }

    private val pvListener = LocationListener { raw ->
        // v1.6.0：预览定位回调隔离，防止异常元数据闪退
        try {
            if (RunBus.state.value.running) return@LocationListener   // 跑步中由前台服务负责
            val loc = LocGate.normalize(raw)
            if (LocGate.isNetwork(raw)) pvNet = loc else pvGps = loc
            bestPreviewFix()?.let { pushPreview(it) }
        } catch (_: Throwable) {
        }
    }

    private fun pushPreview(loc: Location) {
        if (_b == null) return
        val st = RunBus.state.value
        // 刚结束跑步（summary 仍在）时保留最终点位状态，仅刷新定位
        if (st.summary != null) {
            RunBus.update { it.copy(fix = loc, gpsAcc = loc.accuracy) }
            return
        }
        val snaps = store.all().map { cp ->
            RunBus.CpSnap(
                cp, RunBus.CpState.PENDING,
                Math.round(GeoKit.dist(loc.latitude, loc.longitude, cp.lat, cp.lon)).toInt(), 0
            )
        }
        val nearest = snaps.filter { it.distM >= 0 }.minByOrNull { it.distM }
        RunBus.update { it.copy(fix = loc, gpsAcc = loc.accuracy, snaps = snaps, nearest = nearest) }
    }

    override fun onResume() {
        super.onResume()
        registerPreviewLocation()
    }

    private fun registerPreviewLocation() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        try {
            locMgr = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locMgr?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 2f, pvListener)
            try {
                locMgr?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 5f, pvListener)
            } catch (_: Exception) { }
        } catch (_: Exception) { }
    }

    override fun onPause() {
        locMgr?.removeUpdates(pvListener)
        locMgr = null
        super.onPause()
    }

    // ---------- 服务端距离：点按核对 ----------

    /**
     * v1.6.1 重写（闪退修复 + 反馈改造）：
     *  - 点击链路与协程全程 runCatching 隔离（与接口页 v1.5.1 同款加固，
     *    任何异常只落盘崩溃日志，绝不上抛闪退）；
     *  - 查询状态就地显示在右上角（旧版 Toast 一闪而过看不清）；
     *  - 结果弹窗持久展示：距离 / 错误详情 + 响应体 + 复制按钮。
     */
    private fun onApiTap() {
        runCatching {
            if (!prefs.apiEnabled) {
                safeToast(R.string.api_need_enable)
                return
            }
            if (RunBus.state.value.running) {
                RunBus.update { it.copy(pollReq = it.pollReq + 1) }
                safeToast(R.string.verify_requested)
                return
            }
            if (querying) return                       // 防重复点击
            val prof = apiStore.distanceProfile()
            if (prof == null || prof.url.isBlank()) {
                safeToast(R.string.api_not_configured)
                return
            }
            querying = true
            // 就地显示查询状态（不再用底部 Toast）
            if (_b != null) {
                b.tvApiDist.text = getString(R.string.verify_querying_short)
                b.tvApiDistVal.text = "…"
            }
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching {
                    val resp = Http.call(prof, prefs.apiToken, prefs.apiCookie)
                    if (_b == null) return@launch      // 页面已销毁：放弃 UI 更新
                    val ok = resp.error == null && resp.code in 200..299
                    val dv = if (ok) Http.extractDouble(resp.body, prof.distPath) else null
                    if (ok) {
                        RunBus.update {
                            it.copy(apiDistance = dv,
                                apiErr = if (dv == null) "未能提取: ${prof.distPath}" else null)
                        }
                    } else {
                        RunBus.update {
                            it.copy(apiDistance = null,
                                apiErr = resp.error ?: "HTTP ${resp.code}")
                        }
                    }
                    val headline = when {
                        ok && dv != null -> getString(R.string.run_query_ok, String.format("%.1f", dv))
                        ok -> getString(R.string.run_query_no_extract, prof.distPath)
                        else -> getString(R.string.run_query_http, resp.code, resp.ms)
                    }
                    showQueryResult(headline, ok, resp, prof)
                }.onFailure { e ->
                    // v1.6.1：页面仍在才展示/记录；已分离则静默丢弃（requireContext 会抛异常）
                    if (_b == null) return@onFailure
                    SoftLog.write(requireContext().filesDir, "run=onApiTap", e)
                    showQueryResult(
                        getString(R.string.api_test_error, "${e.javaClass.simpleName}: ${e.message ?: ""}"),
                        false, null, prof)
                }
                querying = false
                // 恢复右上角标签（render 也会在下次状态变化时重设）
                if (_b != null && !RunBus.state.value.running) {
                    b.tvApiDist.text = getString(R.string.api_dist_query)
                }
            }
        }.onFailure { e ->
            // 双保险：点击瞬间同步路径上的任何异常同样只落盘不闪退
            try {
                SoftLog.write(requireContext().filesDir, "run=onApiTapOuter", e)
            } catch (_: Throwable) {
            }
        }
    }

    /** v1.6.1：查询结果持久弹窗（含完整响应 + 复制），替代一闪而过的 Toast */
    private fun showQueryResult(headline: String, ok: Boolean, resp: Http.Resp?, prof: com.dut.runmate.data.api.ApiProfile) {
        if (_b == null) return
        val full = buildString {
            append(if (ok) "✓ " else "✗ ").append(headline).append("\n\n")
            append(getString(R.string.api_query_profile_line, prof.title)).append("\n\n")
            if (resp != null) {
                append(getString(R.string.api_query_req_line, resp.code, resp.ms)).append("\n")
                append("URL: ").append(resp.url.take(180)).append("\n\n")
                append(getString(R.string.api_query_body_title)).append("\n")
                append(Http.pretty(resp.body).take(2500))
            }
        }
        try {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.run_query_title)
                .setMessage(full)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.run_query_copy) { _, _ ->
                    runCatching {
                        val cm = requireActivity()
                            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("runmate", full))
                        safeToast(R.string.pref_crash_copied)
                    }
                }
                .show()
        } catch (e: Throwable) {
            SoftLog.write(requireContext().filesDir, "run=showQueryResult", e)
        }
    }

    // ---------- 跑步控制 ----------

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

    // ---------- 渲染 ----------

    private fun locLabel(fix: Location, acc: Float?): String =
        if (LocGate.isNetwork(fix)) getString(R.string.loc_src_net_fmt, (acc ?: 0f).toInt())
        else getString(R.string.loc_src_gps_fmt, (acc ?: 0f).toInt())

    private fun render(st: RunBus.UiState) {
        if (_b == null) return
        // v1.6.0：渲染隔离——状态流回调里的任何异常都不再冒泡杀死 collect 协程/进程
        try {
            renderInner(st)
        } catch (_: Throwable) {
        }
    }

    private fun renderInner(st: RunBus.UiState) {
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

        // 状态大字：跑步中 = 检测状态；未跑步 = 定位预览（有定位时显示源+精度）
        val n = st.nearest
        val inZone = st.snaps.firstOrNull { it.state == RunBus.CpState.IN_ZONE }
        val missed = st.snaps.firstOrNull { it.state == RunBus.CpState.MISSED }
        val head = when {
            st.running && missed != null -> getString(R.string.state_missed)
            st.running && inZone != null -> getString(R.string.state_inzone)
            st.running && st.snaps.any { it.state == RunBus.CpState.GRACE } -> getString(R.string.state_grace)
            st.running && n != null -> "距 ${n.cp.name}"
            st.running -> getString(R.string.gps_waiting)
            st.fix != null -> getString(R.string.run_preview) + " · " + locLabel(st.fix, st.gpsAcc)
            else -> getString(R.string.gps_waiting)
        }
        b.tvHeadline.text = head
        b.tvBigDist.text = if (n != null) "${n.distM}" else "--"
        b.tvTargetName.text = when {
            st.running && inZone != null -> inZone.cp.name
            st.running && n != null -> n.cp.name + " · " + getString(R.string.state_near)
            st.running && st.fix == null -> "等待首个定位"
            n != null -> n.cp.name + " · " + getString(R.string.state_near)
            st.fix != null -> "（暂无打卡点）"
            else -> "—"
        }

        // 雷达箭头：指向最近待检点（预览/跑步中均生效）
        val fix = st.fix
        if (n != null && fix != null) {
            val brg = GeoKit.bearing(fix.latitude, fix.longitude, n.cp.lat, n.cp.lon).toFloat()
            val course = if (fix.hasBearing() && fix.speed > 0.8f) fix.bearing else 0f
            b.radar.setHeading(brg - course)
        } else {
            b.radar.setHeading(0f)
        }

        // 服务端距离
        val apiOn = prefs.apiEnabled
        b.tvApiDist.text = when {
            !apiOn -> getString(R.string.api_off)
            st.running -> getString(R.string.api_dist_verify)
            else -> getString(R.string.api_dist_query)
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
            try {
                val ok = sum.confirmed.joinToString("、").ifEmpty { "无" }
                val miss = sum.missed.joinToString("、").ifEmpty { "无" }
                val body = "已确认：$ok\n未确认（漏卡）：$miss\n用时 ${sum.stats.timeStr} · 自测 ${String.format("%.2f km", sum.stats.meters / 1000.0)}"
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("本次跑步小结")
                    .setMessage(body)
                    .setPositiveButton(R.string.save, null)
                    .show()
            } catch (_: Throwable) {
            }
        }
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }
}
