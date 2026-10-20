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
 *
 * v1.8.15：
 *  - 点位分组筛选：chips 选「自动（最近一组）/全部/指定分组」，默认自动 =
 *    离当前位置平均距离最近的一组，开跑时锁定（今天去哪个场地就显示哪组）；
 *  - 修复「有时不显示录入的点位」：冷启动/无定位时也立即列出全部点位（dist=--），
 *    不再依赖首个定位回调才填充；
 *  - 下拉刷新彻底免弹窗（用户要求：跑步中刷新距离还要点「确认」太麻烦）。
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

    // ---- v1.8.15：分组筛选 ----

    /** 筛选 chips（tag = "auto"/"all"/分组名） */
    private val chipByTag = LinkedHashMap<String, com.google.android.material.chip.Chip>()

    /** auto 模式下本次跑步锁定的分组（开跑时按当时位置定一次，跑步中不随移动切换） */
    private var lockedGroup: String? = null

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

    /** v1.6.1 隔离 + v1.8.11 改需手动确认对话框（原 Toast 显示不全，用户实报） */
    private fun safeToast(resId: Int) {
        Notice.show(this, resId)
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

        // v1.8.15：分组筛选 chips + 无定位兜底快照（修复「有时不显示点位」）
        rebuildGroupChips()
        rebuildIdleSnaps()

        b.btnRun.setOnClickListener {
            if (RunBus.state.value.running) {
                requireContext().startService(
                    Intent(requireContext(), RunTrackerService::class.java).setAction(RunTrackerService.ACT_STOP)
                )
            } else {
                askAndStart()
            }
        }

        // 服务端距离：点按 = 核对/查询并弹详情（v1.8.6 起与下拉刷新共用同一链路）
        b.layoutApi.setOnClickListener { requestDistanceRefresh(showDialog = true) }

        // v1.8.6：下拉刷新距离查询（用户需求：主页面「跑步」向下划屏幕即刷新；
        // 查询成功静默更新数字，失败 Toast 提示原因——详情弹窗留给点按入口）
        b.swipeRefresh.setOnRefreshListener {
            requestDistanceRefresh(showDialog = false) {
                if (_b != null) b.swipeRefresh.isRefreshing = false
            }
        }
        try {
            b.swipeRefresh.setColorSchemeResources(R.color.md_primary)
        } catch (_: Throwable) { }

        viewLifecycleOwner.lifecycleScope.launch {
            RunBus.state.collect { render(it) }
        }
    }

    // ---------- 定位预览 ----------

    /**
     * v1.9.1 修复：定位新鲜度改用 SystemClock.elapsedRealtimeNanos()。
     * 旧版用 System.nanoTime()（CLOCK_MONOTONIC，不含深度休眠）去比
     * Location.elapsedRealtimeNanos（BOOTTIME，含休眠）——手机息屏一段时间
     * （哪怕放兜里走到了另一个场地）再亮屏，两时间基之差 = 累计休眠时长，
     * 息屏前的旧 GPS fix 差值为负、被永远判定为「30s 内新鲜」→ bestPreviewFix
     * 一直返回旧位置 → 「自动」分组按错误位置选组/锁组（用户实报根因）。
     */
    private fun freshAgeNs(fix: Location): Long =
        android.os.SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos

    /** GPS 30s 内新鲜优先，其次 90s 内网络定位，最后旧定位兜底（仅显示用） */
    private fun bestPreviewFix(): Location? {
        val g = pvGps
        if (g != null && freshAgeNs(g) < 30_000_000_000L) return g
        val n = pvNet
        if (n != null && freshAgeNs(n) < 90_000_000_000L) return n
        return pvNet ?: g
    }

    /**
     * v1.9.1：选组/锁组专用「可信托管」定位——宁可不选（等服务首个合格定位），
     * 也不拿旧/粗定位错锁别组：GPS 需 30s 内且精度 ≤ 100m；网络定位需 90s 内
     * 且精度 ≤ 200m（默认三场地组间距 ~700m+，该精度内不会跨场地）。
     */
    private fun lockGradeFix(): Location? {
        val g = pvGps
        if (g != null && freshAgeNs(g) < 30_000_000_000L && g.accuracy <= 100f) return g
        val n = pvNet
        if (n != null && freshAgeNs(n) < 90_000_000_000L && n.accuracy <= 200f) return n
        return null
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
        // v1.8.15：回到本页时同步地图/标定页的点位与分组改动，重建筛选 chips
        rebuildGroupChips()
        rebuildIdleSnaps()
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

    /** v1.8.6 中文消息版 + v1.8.11 改需手动确认对话框 */
    private fun safeToastText(msg: String) {
        Notice.show(this, msg)
    }

    /** v1.8.15：下拉刷新路径专用——瞬时 Toast（自动消失、免点「确认」不打断跑步；
     *  点按入口仍走 Notice 确认弹窗，长文案可完整阅读，v1.8.11 行为不变） */
    private fun transientToast(msg: String) {
        try {
            android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) { }
    }

    private fun transientToast(resId: Int) = transientToast(getString(resId))

    // ---------- 服务端距离：点按核对 / 下拉刷新 ----------

    /**
     * v1.6.1 原逻辑，v1.8.6 重构为统一入口，v1.8.15 刷新路径免弹窗：
     *  - showDialog=true（点按）：结果弹持久详情弹窗（含响应体/复制），行为与旧版一致；
     *    提示性消息走 Notice 确认弹窗（长文案可完整阅读）。
     *  - showDialog=false（下拉刷新）：成功静默（右上角数字就地更新）；失败/异常只弹
     *    瞬时 Toast（自动消失，无需点「确认」——用户要求跑步中下拉刷新别再弹确认框）；
     *    跑步中触发核对完全静默。
     *  跑步中两者差异：点按会弹「已请求核对」，下拉完全无感。
     *  全链路 runCatching 隔离（v1.6.1 防闪退加固保留）。
     */
    private fun requestDistanceRefresh(showDialog: Boolean, onDone: (() -> Unit)? = null) {
        runCatching {
            /** 点按 → Notice 确认弹窗；下拉 → 瞬时 Toast */
            fun tell(resId: Int) { if (showDialog) safeToast(resId) else transientToast(resId) }
            if (!prefs.apiEnabled) {
                tell(R.string.api_need_enable)
                onDone?.invoke(); return
            }
            if (RunBus.state.value.running) {
                RunBus.update { it.copy(pollReq = it.pollReq + 1) }
                // v1.8.15：下拉刷新静默触发核对（旧版弹确认框，用户要求去掉）
                if (showDialog) safeToast(R.string.verify_requested)
                onDone?.invoke(); return
            }
            if (querying) {
                tell(R.string.pull_refresh_too_frequent)
                onDone?.invoke(); return                       // 防重复点击
            }
            val prof = apiStore.distanceProfile()
            if (prof == null || prof.url.isBlank()) {
                tell(R.string.api_not_configured)
                onDone?.invoke(); return
            }
            querying = true
            // 就地显示查询状态（点按与下拉共用）
            if (_b != null) {
                b.tvApiDist.text = getString(R.string.verify_querying_short)
                b.tvApiDistVal.text = "…"
            }
            viewLifecycleOwner.lifecycleScope.launch {
                var resultToast: String? = null
                // v1.8.11：下拉刷新成功时静默（右上角数字已更新，弹窗反而打扰），
                // 仅失败/异常才弹需手动确认的完整提示（用户要求长文案可完整阅读）
                var resultIsFailure = false
                runCatching {
                    val resp = Http.call(prof, prefs.apiToken, prefs.apiCookie)
                    if (_b == null) return@launch      // 页面已销毁：放弃 UI 更新
                    val ok = resp.error == null && resp.code in 200..299
                    // v1.8.2：会话失效识别（旧版会报「未能提取」，用户无从排查）
                    val webvpnExpired = resp.error == null &&
                        Http.isWebvpnLoginRedirect(resp.finalUrl, resp.body, resp.url)
                    val dv = if (ok) Http.extractDouble(resp.body, prof.distPath) else null
                    if (ok) {
                        RunBus.update {
                            it.copy(apiDistance = dv,
                                apiErr = if (dv == null) "未能提取: ${prof.distPath}" else null)
                        }
                    } else if (webvpnExpired) {
                        RunBus.update {
                            it.copy(apiDistance = null,
                                apiErr = "WebVPN 会话已过期，请重新登录捕获")
                        }
                    } else {
                        RunBus.update {
                            it.copy(apiDistance = null,
                                apiErr = resp.error ?: "HTTP ${resp.code}")
                        }
                    }
                    val headline = when {
                        webvpnExpired -> getString(R.string.api_hint_webvpn_expired)
                        ok && dv != null -> getString(R.string.run_query_ok, String.format("%.1f", dv))
                        // v1.8.3：「无」= 今日尚未开跑（正常基线）
                        ok && Http.extractRaw(resp.body, prof.distPath) == "无" ->
                            getString(R.string.api_test_no_record)
                        ok -> getString(R.string.run_query_no_extract, prof.distPath)
                        else -> getString(R.string.run_query_http, resp.code, resp.ms)
                    }
                    resultToast = when {
                        webvpnExpired -> headline
                        ok && dv != null -> getString(R.string.pull_refresh_done, String.format("%.1f", dv))
                        else -> headline
                    }
                    resultIsFailure = !ok || webvpnExpired || dv == null
                    if (showDialog) showQueryResult(headline, ok && !webvpnExpired, resp, prof)
                }.onFailure { e ->
                    // v1.6.1：页面仍在才展示/记录；已分离则静默丢弃（requireContext 会抛异常）
                    if (_b == null) return@onFailure
                    SoftLog.write(requireContext().filesDir, "run=refresh", e)
                    val err = getString(R.string.api_test_error, "${e.javaClass.simpleName}: ${e.message ?: ""}")
                    resultToast = err
                    resultIsFailure = true
                    if (showDialog) showQueryResult(err, false, null, prof)
                }
                querying = false
                // v1.8.15：下拉刷新失败只弹瞬时 Toast（成功静默——数字已就地更新）
                if (!showDialog && _b != null && resultIsFailure)
                    resultToast?.let { transientToast(it) }
                // 恢复右上角标签（render 也会在下次状态变化时重设）
                if (_b != null && !RunBus.state.value.running) {
                    b.tvApiDist.text = getString(R.string.api_dist_query)
                }
                onDone?.invoke()
            }
        }.onFailure { e ->
            // 双保险：同步路径任何异常同样只落盘不闪退；回调兜底防转圈不停
            try {
                if (_b != null) SoftLog.write(requireContext().filesDir, "run=refreshOuter", e)
            } catch (_: Throwable) {
            }
            onDone?.invoke()
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
            == PackageManager.PERMISSION_GRANTED) checkNotifThenStart()
        else permLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    /**
     * v1.8.3：开跑前检查通知可用性（用户实报「锁屏后没有显示」的两大根因之一：
     * Android 13+ POST_NOTIFICATIONS 被拒（含系统自动拒绝）时前台服务通知
     * 状态栏/锁屏全部不显示；MainActivity 只在首启请求过一次，被拒后永远不会再问）。
     * 不可用时弹窗说明并引导去系统设置开启，用户可选择「仍要开始」。
     */
    private fun checkNotifThenStart() {
        val notifOn = try {
            androidx.core.app.NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()
        } catch (_: Throwable) {
            true
        }
        val permGranted = android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (notifOn && permGranted) {
            startService()
            return
        }
        try {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.notif_blocked_title)
                .setMessage(R.string.notif_blocked_msg)
                .setPositiveButton(R.string.notif_blocked_open) { _, _ ->
                    try {
                        startActivity(android.content.Intent(
                            android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                        ).apply {
                            putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,
                                requireContext().packageName)
                        })
                    } catch (_: Throwable) {
                    }
                }
                .setNegativeButton(R.string.notif_blocked_anyway) { _, _ -> startService() }
                .setNeutralButton(android.R.string.cancel, null)
                .show()
        } catch (_: Throwable) {
            startService()   // 弹窗失败不拦跑步
        }
    }

    private fun startService() {
        val ctx = requireContext()
        // v1.8.15：auto 分组筛选开跑锁定——按按下「开始」时的定位选最近组。
        // v1.9.1：改用 lockGradeFix()（时间基修复 + 精度门槛）——旧定位/粗定位
        // 宁可不锁，交由服务首个合格定位到达后再锁（见 filterSnaps）；旧版用
        // bestPreviewFix() 拿息屏前旧 GPS 锁错组是用户实报 bug 的根因
        lockedGroup = lockGradeFix()?.let { fix ->
            nearestGroupOf(
                store.all().filter { it.group.isNotBlank() }
                    .map { Triple(it.group, it.lat, it.lon) }, fix)
        }
        RunBus.resetSession()
        summaryShown = false
        val i = Intent(ctx, RunTrackerService::class.java).setAction(RunTrackerService.ACT_START)
        if (android.os.Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(ctx, i)
        else ctx.startService(i)
    }

    // ---------- v1.8.15：分组筛选 ----------

    /** 重建筛选 chips：自动 / 全部 / 各分组；无分组数据时整行隐藏 */
    private fun rebuildGroupChips() {
        val groups = store.groups()
        // 保存的分组被删光时回退 auto（防空白筛选）
        if (prefs.runFilter != "auto" && prefs.runFilter != "all" &&
            groups.none { it == prefs.runFilter }) prefs.runFilter = "auto"
        val cg = b.chipGroups
        cg.removeAllViews()
        chipByTag.clear()
        val entries: List<Pair<String, String>> = listOf(
            "auto" to getString(R.string.filter_auto),
            "all" to getString(R.string.filter_all)
        ) + groups.map { it to it }
        for ((tag, label) in entries) {
            val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                text = label
                isCheckable = true
                isClickable = true
                id = android.view.View.generateViewId()
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        prefs.runFilter = tag
                        if (tag == "auto") lockedGroup = null   // 手动回自动：重新选组
                        render(RunBus.state.value)
                    }
                }
            }
            chipByTag[tag] = chip
            cg.addView(chip)
        }
        chipByTag[prefs.runFilter]?.isChecked = true
        b.layoutFilter.visibility = if (groups.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * v1.8.15：未跑步时的兑底快照——冷启动/无定位时也把点位列出来（dist=--），
     * 修复用户实报「主页跑步页有时不显示录入的点位」（旧版 snaps 要等首个
     * 定位回调才填充，室内/GPS 慢/无网络定位时列表长时间空白）。回到本页
     * （onResume）时同步地图/标定页的最新点位改动。
     */
    private fun rebuildIdleSnaps() {
        val st = RunBus.state.value
        if (st.running || st.summary != null) return
        val fix = bestPreviewFix()
        val snaps = store.all().map { cp ->
            RunBus.CpSnap(
                cp, RunBus.CpState.PENDING,
                if (fix != null)
                    Math.round(GeoKit.dist(fix.latitude, fix.longitude, cp.lat, cp.lon)).toInt()
                else -1,
                0, 0
            )
        }
        val nearest = snaps.filter { it.distM >= 0 }.minByOrNull { it.distM }
        RunBus.update {
            it.copy(fix = fix ?: st.fix, gpsAcc = fix?.accuracy ?: st.gpsAcc,
                snaps = snaps, nearest = nearest)
        }
    }

    /** 组内点位到当前位置平均距离最小的组名（无分组数据返回 null） */
    private fun nearestGroupOf(pts: List<Triple<String, Double, Double>>, fix: Location): String? {
        if (pts.isEmpty()) return null
        return pts.groupBy { it.first }
            .mapValues { (_, g) ->
                g.map { GeoKit.dist(fix.latitude, fix.longitude, it.second, it.third) }.average()
            }
            .entries.minByOrNull { it.value }?.key
    }

    /** v1.9.1：auto 模式当前实际生效的分组（null=未解析/全量兑底；用于 chip 标注） */
    private var lastAutoGroup: String? = null

    private fun groupedPtsOf(all: List<RunBus.CpSnap>): List<Triple<String, Double, Double>> =
        all.filter { it.cp.group.isNotBlank() }
            .map { Triple(it.cp.group, it.cp.lat, it.cp.lon) }

    /**
     * 跑步页列表按分组筛选：
     *  - all：全部；指定组名：只显示该组（组空时回退全部防白屏）；
     *  - auto（默认）：未跑步按可信托管定位实时选「平均距离最近的组」；开始跑步时
     *    锁定（用户需求：今天去哪个场地，开跑时离得最近的那组），跑步中不随
     *    位置切换，结束自动解锁。
     *
     * v1.9.1 修复「自动切到错误分组」：选组/锁组只信合格定位——开跑锁组用
     * lockGradeFix()；跑步中兑底锁组要求定位精度 ≤ 60m（服务已过滤 >60m，双保险）；
     * 未跑步不再拿 st.fix（可能是息屏前旧定位）选组，改用 lockGradeFix() 实时判：
     * 定位不可信时先显示全部点位，等下一个合格定位再收敛到最近组（宁晚勿错）。
     */
    private fun filterSnaps(st: RunBus.UiState): List<RunBus.CpSnap> {
        val all = st.snaps
        if (all.isEmpty()) { lastAutoGroup = null; return all }
        val groups = all.map { it.cp.group }.filter { it.isNotBlank() }.distinct()
        if (groups.isEmpty()) { lastAutoGroup = null; return all }   // 无分组数据：不过滤
        val mode = prefs.runFilter
        if (mode == "all") { lastAutoGroup = null; return all }
        if (mode != "auto") {
            lastAutoGroup = null
            return all.filter { s -> s.cp.group == mode }.ifEmpty { all }
        }
        // auto
        if (st.running) {
            if (lockedGroup == null) {
                val fix = st.fix
                if (fix != null && fix.accuracy <= 60f) {
                    lockedGroup = nearestGroupOf(groupedPtsOf(all), fix)
                }
            }
            lastAutoGroup = lockedGroup
            return if (lockedGroup != null)
                all.filter { s -> s.cp.group == lockedGroup } else all
        }
        lockedGroup = null                               // 结束跑步后解除锁定
        val g = lockGradeFix()?.let { nearestGroupOf(groupedPtsOf(all), it) }
        lastAutoGroup = g
        return if (g != null) all.filter { s -> s.cp.group == g } else all
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
        // v1.8.15：列表按分组筛选（默认自动 = 最近一组；开跑锁定）
        val visible = filterSnaps(st)
        adapter.submit(visible)
        // v1.9.1：auto 模式把当前生效分组标注在「自动」chip 上（如「自动 · 西部校区
        // 田径场」）——选组结果可见，错了当场看得出来，不再「默默选错」
        try {
            chipByTag["auto"]?.text = if (prefs.runFilter == "auto" && lastAutoGroup != null)
                getString(R.string.filter_auto) + " · " + lastAutoGroup
            else getString(R.string.filter_auto)
        } catch (_: Throwable) { }

        // 状态大字：跑步中 = 检测状态；未跑步 = 定位预览（有定位时显示源+精度）
        // v1.8.15：最近点位在「可见集合」内取（雷达/大字与列表同源，不跳到别组远点）
        val n = visible.filter { it.state != RunBus.CpState.CONFIRMED && it.distM >= 0 }
            .minByOrNull { it.distM }
        val inZone = visible.firstOrNull { it.state == RunBus.CpState.IN_ZONE }
        val missed = visible.firstOrNull { it.state == RunBus.CpState.MISSED }
        val head = when {
            st.running && missed != null -> getString(R.string.state_missed)
            st.running && inZone != null -> getString(R.string.state_inzone)
            st.running && visible.any { it.state == RunBus.CpState.GRACE } -> getString(R.string.state_grace)
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
