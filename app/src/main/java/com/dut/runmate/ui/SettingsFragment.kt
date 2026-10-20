package com.dut.runmate.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.dut.runmate.BuildConfig
import com.dut.runmate.R
import com.dut.runmate.data.Accounts
import com.dut.runmate.data.CheckpointStore
import com.dut.runmate.data.Prefs
import com.dut.runmate.data.WsAccount
import com.dut.runmate.data.api.ApiProfile
import com.dut.runmate.data.api.ApiStore
import com.dut.runmate.databinding.FragmentSettingsBinding
import com.dut.runmate.net.Http
import com.dut.runmate.update.UpdateFlow
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页（v1.3.0 起为底部导航 Tab，替代原独立 SettingsActivity）。
 *
 * v1.8.14：**账号登录整体并入**——原「接口」Tab（v1.8.13 已移除自定义接口调试，
 * 仅剩账号登录/多账号/批量查询）按用户需求合并为设置页顶部「账号」分区，底部导航
 * 精简为 4 Tab（跑步/地图/标定/设置）；同版新增「联系作者」邮箱入口
 * （aquars@mail.dlut.edu.cn）。
 */
class SettingsFragment : Fragment() {

    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!
    private val prefs by lazy { Prefs.get(requireContext()) }
    private val store by lazy { CheckpointStore.get(requireContext().filesDir) }

    /** v1.8.14：接口模板存储（账号登录/多账号/批量查询沿用原 ApiFragment 逻辑） */
    private val apiStore by lazy { ApiStore.get(requireContext().filesDir) }

    /** v1.7.0：批量查询全部账号距离进行中（防重复点击） */
    private var queryingAll = false

    companion object {
        /** v1.8.14：联系作者邮箱（用户指定） */
        private const val CONTACT_EMAIL = "aquars@mail.dlut.edu.cn"
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentSettingsBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        super.onViewCreated(v, savedInstanceState)

        // ==================== 账号登录（v1.8.14 自原「接口」Tab 并入） ====================

        // v1.8.2：复位批量查询在逓标记（旧版取消路径不复位——切页中断后 queryingAll
        // 永久卡 true，「查询全部账号距离」从此点击无反应，只能杀进程恢复）
        queryingAll = false

        // v1.4.0 账号登录 · 一键配置（v1.5.0 登录信息本机回填；v1.5.1 CAS+微哨双通道）
        b.etWsNumber.setText(prefs.wsNumber)
        b.etWsPwd.setText(prefs.wsPwdDecoded())
        // 长按密码框：删除当前账号（v1.7.0 起带确认对话框；未入库时退化为清空本机登录信息）
        b.etWsPwd.setOnLongClickListener {
            val cur = prefs.wsNumber.trim()
            val inList = cur.isNotBlank() && Accounts.byNumber(prefs, apiStore, cur) != null
            if (inList) {
                Accounts.byNumber(prefs, apiStore, cur)?.let { confirmDeleteAccount(it) }
            } else {
                clearActiveAccount()
                renderAccounts()
                Notice.show(this, "已清除本机保存的登录信息")
            }
            true
        }
        renderWsStatus()
        b.btnWsLogin.setOnClickListener { doLogin() }
        // v1.9.0：微信扫码登录（免输密码；协议复刻网页端 login-qrcode.js，见
        // CasAuth.beginQrLogin / pollQrLogin）
        b.btnQrLogin.setOnClickListener { showQrLoginDialog() }
        // v1.9.1：移除「重新捕获 H5 参数」手动按钮（用户拍板：令牌失效直接重新登录
        // 即可，登录链路会重跑原生一键配置全套参数）。openH5Catcher 仍保留作
        // 原生配置失败/微哨仅存时的自动兑底入口（doLogin/startNativeConfig 调用），
        // 只是不再提供手动入口。

        // v1.7.0 多账号：chips 列表（切换/长按删除/＋添加）+ 批量查询全部账号距离
        renderAccounts()
        b.btnQueryAll.setOnClickListener { runCatching { queryAll() } }

        // v1.6.1：账号登录说明（多账号/重新捕获/API 判定都是干什么的）
        b.btnHelpApi.setOnClickListener { HelpDialog.show(requireContext()) }

        // ==================== 检测参数 / 提醒 / 通知显示 / API / 地图 ====================
        //
        // v1.9.0：检测参数改手动输入（用户需求：直接手动填写、自动保留 1 位小数，
        // 可自定义精确参数如 12.5m；旧滑杆步长 1m 只能整数）。可刷新绑定集中到
        // bindConfigControls()——onViewCreated 首次调用，「全部恢复默认」后重调
        // 一次即可整页回填默认值（v1.4.0 的「历史脏数据自愈」语义一并保留：
        // 越界/非法输入夹取到合法范围后写回）。
        bindConfigControls()

        // v1.8.13：分场景提醒开关（每种提醒的语音/震动独立开关，默认全开，
        // 与上方总开关叠加——两层都开才触发）
        b.btnAlertScn.setOnClickListener { showScnMatrixDialog() }

        // v1.8.6：测试提醒——当场播报语音+提示音+震动，4.5s 后弹出三通道自检结果。
        // v1.8.8：语音改预录音（res/raw/voice_*.ogg 随 App 打包）——彻底不依赖
        // 系统 TTS 引擎（连续两轮修「引擎不可用」的终结方案）：即点即播、
        // 无初始化等待；自检展示 voiceState（真实播放结果）。
        b.btnTestAlert.setOnClickListener {
            val am = com.dut.runmate.alert.AlertManager(requireContext(), prefs)
            try {
                // v1.8.11：Toast 改需手动确认对话框
                Notice.show(this, R.string.alert_test_playing)
            } catch (_: Throwable) { }
            // 立即触发三通道（预录语音无初始化延迟）
            try { am.testAlert() } catch (_: Throwable) { }
            // 播放完成后展示诊断 + 释放播放器
            view?.postDelayed({
                // v1.8.10 崩溃修复：4.5s 延迟期间用户可能已切走设置页，Fragment
                // 脱离宿主后再调 getString()/requireContext() 会抛
                // IllegalStateException("not attached to a context")（用户实报闪退，
                // SettingsFragment.kt:103）。现在：播放器无条件释放（防泄漏），
                // 已脱离则直接放弃弹窗（am.shutdown 幂等，UI 不再被触摸）。
                try { am.shutdown() } catch (_: Throwable) { }
                val act = activity as? AppCompatActivity ?: return@postDelayed
                if (!isAdded) return@postDelayed
                val voiceOk = am.voiceState.isBlank()
                val voiceLine = if (voiceOk)
                    act.getString(R.string.alert_test_voice_ok)
                else act.getString(R.string.alert_test_voice_bad, am.voiceState)
                val vibOk = am.vibState.isBlank()
                val vibLine = if (vibOk)
                    act.getString(R.string.alert_test_vib_ok)
                else act.getString(R.string.alert_test_vib_bad, am.vibState)
                val sb = buildString {
                    append(voiceLine).append('\n')
                    append(act.getString(R.string.alert_test_chime_ok)).append('\n')
                    append(vibLine)
                    if (!vibOk) append('\n').append(act.getString(R.string.alert_test_vib_hint))
                    if (!prefs.ttsEnabled || !prefs.chimeEnabled || !prefs.vibrateEnabled)
                        append("\n\n").append(act.getString(R.string.alert_test_switch_off))
                }
                try {
                    android.app.AlertDialog.Builder(act)
                        .setTitle(R.string.alert_test_title)
                        .setMessage(sb)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } catch (_: Throwable) { }
            }, 4500L)
        }

        // 更新（v1.6.0：界面精简——只留「检查更新」；v1.8.13：更新只走 GitHub
        // Release，发布中心服务器通道已随 Updater 一并移除）
        b.btnUpdCheck.setOnClickListener {
            (requireActivity() as? AppCompatActivity)?.let { UpdateFlow.manualCheck(it) }
        }

        // GitHub 开源仓库（v1.6.0：图标入口，点击进浏览器）
        b.rowGithub.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/" + com.dut.runmate.update.Updater.GITHUB_REPO)))
            } catch (_: Exception) {
                Notice.show(this, "https://github.com/" + com.dut.runmate.update.Updater.GITHUB_REPO)
            }
        }

        // v1.8.14：联系作者（邮箱）——发送邮件 / 复制邮箱地址
        b.rowContact.setOnClickListener { showContactDialog() }

        // 系统
        b.btnBattery.setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${requireContext().packageName}"))
                )
            } catch (_: Exception) {
                Notice.show(this, "请手动在系统设置中允许后台运行")
            }
        }
        // v1.8.3：系统通知设置直达（锁屏显示修复链路的一环——Android 13+ 通知
        // 权限被拒 / MIUI「锁屏通知」默认不展示时，只有系统设置里能改，用户
        // 自己很难找到入口）。跑步开始时检测到通知被关也会引导到这里。
        b.btnNotifSetting.setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                )
            } catch (_: Exception) {
                Notice.show(this, R.string.notif_settings_fallback)
            }
        }
        b.btnExport.setOnClickListener {
            val cm = requireActivity().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("runmate", store.exportJson()))
            Notice.show(this, getString(R.string.pref_export_done, store.count))
        }

        // v1.9.0：全部恢复默认（替代 v1.8.15「恢复默认打卡点」单一入口：检测参数/
        // 提醒开关/通知显示/API/地图坐标/点位一并重置；账号登录信息不受影响）
        b.btnResetAll.setOnClickListener { confirmResetAll() }

        // v1.5.1：崩溃日志查看（filesDir/crash_log.txt，App.kt 全局捕获落盘）。
        // 用户遇到闪退时可打开复制给开发者，远程定位问题不再靠猜。
        b.btnCrashLog.setOnClickListener {
            val text = try {
                java.io.File(requireContext().filesDir, "crash_log.txt")
                    .takeIf { it.exists() }?.readText() ?: ""
            } catch (_: Exception) { "" }
            if (text.isBlank()) {
                Notice.show(this, R.string.pref_crash_none)
            } else {
                val cm = requireActivity().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("crash", text))
                android.app.AlertDialog.Builder(requireContext())
                    .setTitle(R.string.pref_crash_title)
                    .setMessage(text.take(6000))
                    .setPositiveButton(R.string.pref_crash_copy) { d, _ ->
                        Notice.show(this, R.string.pref_crash_copied)
                        d.dismiss()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }

        // 关于（动态版本号）
        b.tvAbout.text = getString(R.string.pref_about_body, BuildConfig.VERSION_NAME)
    }

    /**
     * v1.8.13：分场景提醒开关矩阵。v1.8.15 重设计（用户要求：开关状态不要用
     * 颜色深浅表示，改用文字开关）——每行 = 场景名 + 「语音 开/关」「震动 开/关」
     * 两个文字状态按钮：状态直接写在按钮文字与 ✓/✗ 图标里，点按即切换即存；
     * 同时新增第 10 行「打卡完成（达 3km）」。与总开关（语音/震动）叠加：
     * 两层都开才触发。提示音保持全局开关不分场景（提醒主通道是语音+震动）。
     */
    private fun showScnMatrixDialog() {
        val act = activity as? AppCompatActivity ?: return
        val pad = (16 * resources.displayMetrics.density).toInt()
        val rows = listOf(
            com.dut.runmate.alert.AlertManager.Voice.START to getString(R.string.scn_start),
            com.dut.runmate.alert.AlertManager.Voice.APPROACH to getString(R.string.scn_approach),
            com.dut.runmate.alert.AlertManager.Voice.ENTER to getString(R.string.scn_enter),
            com.dut.runmate.alert.AlertManager.Voice.OK to getString(R.string.scn_ok),
            com.dut.runmate.alert.AlertManager.Voice.UNCONFIRMED to getString(R.string.scn_unconfirmed),
            com.dut.runmate.alert.AlertManager.Voice.MISSED to getString(R.string.scn_missed),
            com.dut.runmate.alert.AlertManager.Voice.PASS_GPS to getString(R.string.scn_pass_gps),
            com.dut.runmate.alert.AlertManager.Voice.API_ERR to getString(R.string.scn_api_err),
            com.dut.runmate.alert.AlertManager.Voice.END to getString(R.string.scn_end),
            com.dut.runmate.alert.AlertManager.Voice.DONE to getString(R.string.scn_done)
        )
        val root = android.widget.LinearLayout(act).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        for ((v, label) in rows) {
            val row = android.widget.LinearLayout(act).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, pad / 4, 0, pad / 4)
            }
            row.addView(android.widget.TextView(act).apply {
                text = label
                // 不引用平台样式资源（TextAppearance_DeviceDefault_Body1 在编译期
                // 不可解析导致 CI 失败），直接设字号走默认前景色
                textSize = 15f
                layoutParams = android.widget.LinearLayout.LayoutParams(0,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            /** 文字开关按钮：状态写在文字里（✓ 语音 开 / ✗ 语音 关），点按即切换 */
            fun toggleBtn(isVoice: Boolean): com.google.android.material.button.MaterialButton {
                val base = act.getString(if (isVoice) R.string.scn_voice else R.string.scn_vib)
                val btn = com.google.android.material.button.MaterialButton(
                    act, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    textSize = 12f
                    setMinHeight(0)
                    minimumHeight = 0
                    insetTop = 0
                    insetBottom = 0
                    iconSize = (16 * resources.displayMetrics.density).toInt()
                    iconPadding = pad / 4
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = pad / 2 }
                }
                fun applyState(on: Boolean) {
                    btn.text = "$base ${act.getString(if (on) R.string.scn_on else R.string.scn_off)}"
                    btn.setIconResource(if (on) R.drawable.ic_check else R.drawable.ic_close)
                }
                fun current() = if (isVoice) prefs.scnVoiceOn(v.scn) else prefs.scnVibOn(v.scn)
                fun store(v2: Boolean) {
                    if (isVoice) prefs.setScnVoiceOn(v.scn, v2) else prefs.setScnVibOn(v.scn, v2)
                }
                applyState(current())
                btn.setOnClickListener {
                    val now = !current()
                    store(now)
                    applyState(now)
                }
                return btn
            }
            row.addView(toggleBtn(isVoice = true))
            row.addView(toggleBtn(isVoice = false))
            root.addView(row)
        }
        val scroll = android.widget.ScrollView(act).apply { addView(root) }
        try {
            MaterialAlertDialogBuilder(act)
                .setTitle(R.string.scn_matrix_title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } catch (_: Throwable) {
        }
    }

    /**
     * v1.9.0：检测参数手动输入绑定（替代旧滑杆——用户需求：直接手动填写、
     * 自动保留 1 位小数，可自定义精确参数如 12.5m）。
     * 失焦时生效：非法/空输入回退当前值；越界夹取到 [min,max]；四舍五入到
     * 1 位小数后落盘并回显（沿用 v1.4.0 的历史脏数据自愈语义）。
     */
    private fun bindDecimalInput(
        et: com.google.android.material.textfield.TextInputEditText,
        min: Float, max: Float,
        get: () -> Float, set: (Float) -> Unit
    ) {
        fun fmt(v: Float) = String.format("%.1f", v)
        fun refresh() {
            val cur = get().coerceIn(min, max)
            if (cur != get()) set(cur)            // 自愈：越界/历史脏数据夹取后写回
            et.setText(fmt(cur))
        }
        fun apply() {
            val raw = et.text.toString().toFloatOrNull()
            if (raw == null) { refresh(); return }
            val v = Math.round(raw.coerceIn(min, max) * 10f) / 10f
            set(v)
            et.setText(fmt(v))
            et.setSelection(et.text?.length ?: 0)
        }
        refresh()
        et.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) apply() }
        // 键盘「完成」→ 先收焦点（经焦点监听统一走 apply），保持单一落盘路径
        et.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                et.clearFocus(); true
            } else false
        }
    }

    /**
     * v1.9.0：可刷新的配置绑定（检测参数手填框/提醒总开关/跑步通知显示/API/地图）。
     * onViewCreated 首次调用；「全部恢复默认」后重调一次，整页即时回填默认值
     * （各监听器 attach 均为替换语义，重复绑定安全）。
     */
    private fun bindConfigControls() {
        // 检测参数（手填，范围与旧滑杆一致；1 位小数）
        bindDecimalInput(b.etRadius, 1f, 80f, { prefs.defaultRadius }, { prefs.defaultRadius = it })
        bindDecimalInput(b.etApproach, 1f, 150f, { prefs.approachDist }, { prefs.approachDist = it })
        bindDecimalInput(b.etDelta, 1f, 10f, { prefs.confirmDelta }, { prefs.confirmDelta = it })
        bindDecimalInput(b.etPoll, 2f, 15f, { prefs.pollInterval }, { prefs.pollInterval = it })
        bindDecimalInput(b.etGrace, 5f, 40f, { prefs.graceSec }, { prefs.graceSec = it })

        // 提醒总开关
        b.swTts.isChecked = prefs.ttsEnabled
        b.swTts.setOnCheckedChangeListener { _, c -> prefs.ttsEnabled = c }
        b.swChime.isChecked = prefs.chimeEnabled
        b.swChime.setOnCheckedChangeListener { _, c -> prefs.chimeEnabled = c }
        b.swVibrate.isChecked = prefs.vibrateEnabled
        b.swVibrate.setOnCheckedChangeListener { _, c -> prefs.vibrateEnabled = c }

        // v1.9.0：跑步通知显示（锁屏/状态栏；RunTrackerService 下次开跑时重建渠道生效）
        b.swNotifLock.isChecked = prefs.notifLockscreen
        b.swNotifLock.setOnCheckedChangeListener { _, c -> prefs.notifLockscreen = c }
        b.swNotifBar.isChecked = prefs.notifStatusbar
        b.swNotifBar.setOnCheckedChangeListener { _, c -> prefs.notifStatusbar = c }

        // API
        b.swApi.isChecked = prefs.apiEnabled
        b.swApi.setOnCheckedChangeListener { _, c -> prefs.apiEnabled = c }

        // 地图
        if (prefs.tileSat) b.rbTileSat.isChecked = true else b.rbTileStreet.isChecked = true
        b.rgTile.setOnCheckedChangeListener { _, id ->
            prefs.tileSat = id == R.id.rbTileSat
        }
        if (prefs.coordGcj) b.rbCoordGcj.isChecked = true else b.rbCoordWgs.isChecked = true
        b.rgCoord.setOnCheckedChangeListener { _, id ->
            prefs.coordGcj = id == R.id.rbCoordGcj
        }
    }

    /**
     * v1.9.0：全部恢复默认（替代 v1.8.15「恢复默认打卡点」单一入口，用户需求：
     * 删掉单一入口、加「将所有配置改为默认」，默认检测半径 10m / 预告距离 20m）。
     * 重置范围见 Prefs.resetAllConfig（账号登录信息保留）；点位同步重建 8 个
     * 官方默认点；随后重调 bindConfigControls 整页即时回填。
     */
    private fun confirmResetAll() {
        val act = activity as? AppCompatActivity ?: return
        try {
            MaterialAlertDialogBuilder(act)
                .setTitle(R.string.pref_reset_all)
                .setMessage(getString(R.string.reset_all_confirm, store.count))
                .setPositiveButton(R.string.reset_all_go) { _, _ ->
                    prefs.resetAllConfig()
                    store.resetToDefaults()
                    if (_b != null) bindConfigControls()
                    Notice.show(this, getString(R.string.reset_all_done))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } catch (_: Throwable) { }
    }

    // ==================== v1.9.0：微信扫码登录 ====================

    /**
     * 扫码登录对话框（协议逆向自 sso 登录页 login-qrcode.js + login12.js）：
     * beginQrLogin（GET /cas/login 播种会话 + 本地 UUID）→ 展示二维码 →
     * 每 3s pollQrLogin（checkQrCodeScan：wait / wait_confirm / cancel / success）
     * → success 后 CASTGC 入共享 jar → 与密码登录同路径 startNativeConfig（免密码）。
     * 60s 未扫过期（对齐网页端 20×3s），点「刷新二维码」重新生成；
     * 密码框留空时扫码成功后不落盘密码（会话自动续期不可用，属可接受降级）。
     */
    private fun showQrLoginDialog() {
        if (_b == null) return
        val number = b.etWsNumber.text.toString().trim()
        if (number.isBlank()) {
            b.tvWsStatus.text = getString(R.string.qr_need_number)
            return
        }
        // 换账号扫码前先把当前活跃账号快照入库（与 doLogin 同策略）
        if (prefs.wsNumber.isNotBlank() && number != prefs.wsNumber.trim()) {
            try {
                Accounts.snapshotCurrent(prefs, apiStore)?.let { Accounts.upsert(prefs, apiStore, it) }
            } catch (_: Exception) {
            }
        }
        val view = layoutInflater.inflate(R.layout.dialog_qr_login, null)
        val imgQr = view.findViewById<ImageView>(R.id.imgQr)
        val tvQrStatus = view.findViewById<TextView>(R.id.tvQrStatus)

        var pollJob: kotlinx.coroutines.Job? = null
        val dlg = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.qr_login_title)
            .setView(view)
            .setPositiveButton(R.string.qr_btn_refresh, null)   // 手动接管：点按=刷新
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .setOnDismissListener { pollJob?.cancel() }
            .create()

        fun startQr() {
            tvQrStatus.text = getString(R.string.qr_status_loading)
            pollJob?.cancel()
            pollJob = viewLifecycleOwner.lifecycleScope.launch {
                val beg = com.dut.runmate.auth.CasAuth.beginQrLogin()
                if (_b == null || !dlg.isShowing) return@launch
                if (beg == null) {
                    tvQrStatus.text = getString(R.string.qr_status_net_err)
                    return@launch
                }
                val bmp = withContext(Dispatchers.IO) { qrBitmap(beg.qrUrl) }
                if (_b == null || !dlg.isShowing) return@launch
                if (bmp == null) {
                    tvQrStatus.text = getString(R.string.qr_status_net_err)
                    return@launch
                }
                imgQr.setImageBitmap(bmp)
                tvQrStatus.text = getString(R.string.qr_status_wait)
                var polls = 0
                while (polls < 20 && isActive && dlg.isShowing) {   // 网页端同款 20×3s=60s 有效期
                    delay(3000)
                    polls++
                    if (_b == null || !dlg.isShowing) return@launch
                    when (val st = com.dut.runmate.auth.CasAuth.pollQrLogin(beg.uuid)) {
                        com.dut.runmate.auth.CasAuth.QrPoll.WaitConfirm ->
                            tvQrStatus.text = getString(R.string.qr_status_confirm)
                        com.dut.runmate.auth.CasAuth.QrPoll.Cancel -> {
                            tvQrStatus.text = getString(R.string.qr_status_cancel)
                            return@launch
                        }
                        is com.dut.runmate.auth.CasAuth.QrPoll.Ok -> {
                            tvQrStatus.text = getString(R.string.qr_status_ok)
                            prefs.casTgt = st.castgc
                            prefs.wsNumber = number
                            val pwd = b.etWsPwd.text.toString()
                            if (pwd.isNotBlank()) prefs.wsPwdSaved = android.util.Base64.encodeToString(
                                pwd.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                            dlg.dismiss()
                            renderWsStatus()
                            // 与密码登录同路径：CASTGC 就绪 → 原生一键配置
                            //（SportAuth 免密调用，密码仅会话失效兜底时才需要）
                            startNativeConfig(number, pwd)
                            return@launch
                        }
                        is com.dut.runmate.auth.CasAuth.QrPoll.Err -> {
                            tvQrStatus.text = st.msg
                            return@launch
                        }
                        com.dut.runmate.auth.CasAuth.QrPoll.Wait -> { }
                    }
                }
                if (polls >= 20 && dlg.isShowing)
                    tvQrStatus.text = getString(R.string.qr_status_expired)
            }
        }
        dlg.setOnShowListener {
            dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener { startQr() }
        }
        dlg.show()
        startQr()
    }

    /** v1.9.0：二维码位图（zxing；IO 线程调用；内容 = qrLogin?uuid=<uuid>&service=） */
    private fun qrBitmap(content: String, size: Int = 512): Bitmap? = try {
        val m = com.google.zxing.qrcode.QRCodeWriter()
            .encode(content, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size)
            px[y * size + x] = if (m.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    } catch (_: Exception) {
        null
    }

    /**
     * v1.8.14：联系作者对话框——「发送邮件」（ACTION_SENDTO mailto，无邮件应用
     * 时自动退回复制）/「复制邮箱地址」。邮箱同时展示在副标题与正文中。
     */
    private fun showContactDialog() {
        val act = activity as? AppCompatActivity ?: return
        fun copyEmail() {
            try {
                val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("email", CONTACT_EMAIL))
                Notice.show(this, R.string.pref_crash_copied)
            } catch (_: Exception) { }
        }
        try {
            MaterialAlertDialogBuilder(act)
                .setTitle(R.string.pref_contact)
                .setMessage(getString(R.string.contact_dialog_msg))
                .setPositiveButton(R.string.contact_send) { _, _ ->
                    try {
                        startActivity(Intent(Intent.ACTION_SENDTO,
                            Uri.parse("mailto:$CONTACT_EMAIL")))
                    } catch (_: android.content.ActivityNotFoundException) {
                        copyEmail()
                        Notice.show(this, getString(R.string.contact_no_mail))
                    } catch (_: Exception) {
                        copyEmail()
                    }
                }
                .setNeutralButton(R.string.contact_copy) { _, _ -> copyEmail() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } catch (_: Throwable) { }
    }

    // ==================== v1.8.14 账号登录逻辑（自原 ApiFragment 整体并入） ====================

    private fun renderWsStatus() {
        val wsOk = prefs.wsUserId.isNotBlank()
        val casOk = prefs.casTgt.isNotBlank()
        when {
            wsOk -> {
                // v1.9.1：按钮已删（重新登录即重新捕获）；状态只描述现状，操作指引入「说明」
                b.tvWsStatus.text = getString(
                    R.string.ws_status_logged, prefs.wsName, prefs.wsUserId.take(8)
                )
            }
            casOk -> {
                // v1.5.1：微哨通道失败但 CAS 会话可用；v1.9.1：指引改为重新登录
                b.tvWsStatus.text = getString(R.string.ws_status_cas_only, prefs.wsNumber)
            }
            prefs.wsNumber.isNotBlank() && prefs.wsPwdSaved.isNotBlank() -> {
                b.tvWsStatus.text = getString(R.string.ws_status_saved, prefs.wsNumber)
            }
            else -> {
                b.tvWsStatus.text = getString(R.string.ws_status_none)
            }
        }
    }

    /**
     * v1.5.1 双通道登录：
     *  1) CAS 统一认证（sso.dlut.edu.cn，与网页端同协议）—— 主通道，实测校验可靠
     *  2) 微哨 userLoginCas —— 辅通道，成功可多得 userId/whistlekey（H5 直连场景更稳）
     * 任一成功即打开 H5 捕获页完成自动配置；全部失败才报错（CAS 错误信息更准，优先展示）。
     */
    private fun doLogin() {
        val number = b.etWsNumber.text.toString().trim()
        val password = b.etWsPwd.text.toString()
        if (number.isBlank() || password.isBlank()) {
            b.tvWsStatus.text = getString(R.string.ws_status_need_input)
            return
        }
        // v1.7.0：换账号登录前，先把当前活跃账号快照入库（配置不丢，随时可切回来）
        if (prefs.wsNumber.isNotBlank() && number != prefs.wsNumber.trim()) {
            try {
                Accounts.snapshotCurrent(prefs, apiStore)?.let { Accounts.upsert(prefs, apiStore, it) }
            } catch (_: Exception) {
            }
        }
        b.btnWsLogin.isEnabled = false
        b.tvWsStatus.text = getString(R.string.ws_status_logging)
        viewLifecycleOwner.lifecycleScope.launch {
            var casOk = false
            var wsOk = false
            var casErr = ""
            var wsErr = ""
            // v1.8.8：学校 CAS 启用短信二次认证（MFA）时暂存，待用户在对话框完成
            var mfa: com.dut.runmate.auth.CasAuth.Result.MfaRequired? = null

            // —— 通道 1：CAS 统一认证 ——
            try {
                when (val r = com.dut.runmate.auth.CasAuth.login(number, password)) {
                    is com.dut.runmate.auth.CasAuth.Result.Ok -> {
                        casOk = true
                        prefs.casTgt = r.castgc
                    }
                    // v1.8.8：凭据已验对，学校要求短信二次认证（实测：账号密码无误，
                    // 旧版遇到此页无 CASTGC 又无错误文案，兜底误报「密码错误」）
                    is com.dut.runmate.auth.CasAuth.Result.MfaRequired -> mfa = r
                    // v1.8.9：MfaRetry 只会由 submitMfa 返回，login() 不会出现——占位保证穷尽
                    is com.dut.runmate.auth.CasAuth.Result.MfaRetry -> casErr = r.msg
                    is com.dut.runmate.auth.CasAuth.Result.Err -> casErr = r.msg
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                casErr = "网络错误：${e.message ?: "未知"}"
            }

            // —— 通道 2：微哨（不因 CAS 成功而跳过：成功时能拿 userId/whistlekey）——
            try {
                when (val r = com.dut.runmate.auth.WhistleAuth.login(number, password)) {
                    is com.dut.runmate.auth.WhistleAuth.Result.Ok -> {
                        wsOk = true
                        val s = r.s
                        prefs.wsUserId = s.userId; prefs.wsSkey = s.skey
                        prefs.wsName = s.name; prefs.wsNumber = s.number.ifBlank { number }
                    }
                    is com.dut.runmate.auth.WhistleAuth.Result.Err -> wsErr = r.msg
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                wsErr = "网络错误：${e.message ?: "未知"}"
            }

            if (_b == null) return@launch          // 页面已销毁：直接放弃，绝不触摸 UI
            b.btnWsLogin.isEnabled = true

            // 落盘登录信息（v1.7.0：仅任一通道成功才落盘 —— 失败不再覆盖活跃账号，
            // 否则输错一次新学号就会把当前账号的保存信息挤掉）
            if (casOk || wsOk) {
                prefs.wsNumber = number
                prefs.wsPwdSaved = android.util.Base64.encodeToString(
                    password.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            }

            when {
                casOk -> startNativeConfig(number, password)
                // v1.8.8：CAS 要短信二次认证——弹 MFA 对话框（图形码→发短信→输短信码
                // → CASTGC），完成后与直登同路径；用户取消时若微哨通道成功仍可走
                // H5 捕获页兜底。优先于 wsOk：拿到 CASTGC 才能走原生一键配置。
                mfa != null -> showMfaDialog(mfa!!, number, password, wsOk)
                wsOk -> {
                    renderWsStatus()
                    Notice.show(this@SettingsFragment, getString(R.string.ws_login_ok, prefs.wsName))
                    openH5Catcher(password)
                }
                else -> {
                    // 全部失败：展示 CAS 的错误（更准确）；附带微哨错误便于排查
                    b.tvWsStatus.text = getString(R.string.ws_login_fail, casErr) +
                        if (wsErr.isNotBlank()) "\n（微哨通道：$wsErr）" else ""
                }
            }
        }
    }

    /**
     * v1.8.8：CAS 凭据就绪（直登 Ok 或 MFA 完成）后的原生一键配置。
     * 从 doLogin 的 casOk 分支提取——MFA 对话框完成回调与直登共用同一路径。
     */
    private fun startNativeConfig(number: String, password: String) {
        if (_b == null) return
        // v1.8.3：CAS 会话可用 → 原生一键配置（复刻 H5 全链路，不依赖
        // WebView 嗅探，校外网络同样可用——「账号未捕获」的根治方案）。
        renderWsStatus()
        b.tvWsStatus.text = getString(R.string.ws_native_running)
        viewLifecycleOwner.lifecycleScope.launch {
            // ⚠ SportAuth.login 在 Dispatchers.IO 上回调 log —— 必须转主线程
            // 再触摸 TextView，否则 CalledFromWrongThreadException 直接闪退
            fun uiLog(line: String) {
                if (_b == null) return
                viewLifecycleOwner.lifecycleScope.launch(
                    kotlinx.coroutines.Dispatchers.Main
                ) {
                    if (_b != null) b.tvWsStatus.append("\n$line")
                }
            }
            val sr = com.dut.runmate.auth.SportAuth.login(
                number, password, freshCas = false
            ) { line -> uiLog(line) }
            if (_b == null) return@launch
            when (sr) {
                is com.dut.runmate.auth.SportAuth.Result.Ok -> {
                    com.dut.runmate.auth.SportAuth.applySession(prefs, apiStore, sr.s)
                    b.swApi.isChecked = prefs.apiEnabled
                    renderWsStatus()
                    renderAccounts()
                    showNativeOkDialog(sr.s)
                }
                is com.dut.runmate.auth.SportAuth.Result.Err -> {
                    // 原生失败 → 退回 H5 捕获页（手动兜底路径）
                    b.tvWsStatus.text =
                        getString(R.string.ws_native_fail, sr.step, sr.msg)
                    Notice.show(this@SettingsFragment, getString(R.string.ws_native_fallback),
                        getString(R.string.ws_native_fail_title))
                    openH5Catcher(password)
                }
            }
        }
    }

    /**
     * v1.8.8：学校 CAS 新启用短信二次认证（MFA）——凭据已验对（login() 返回
     * MfaRequired，实测：账号密码无误），需用户交互完成：
     *   ① /cas/code 拉图形验证码（点图刷新）
     *   ② 输图形码 → recheckcode 发短信（Set-Cookie 反馈码）
     *   ③ 输短信码 → POST PM1 提交 → 服务端发 CASTGC
     * 与网页端 sso.dlut.edu.cn 二次认证页一一对应；三原语必须在 login() 之后
     * 同进程调用（CAS 会话存 CasAuth 共享 Cookie jar，进程重启作废）。
     * 短信码错误可重试（MfaRetry 携带新 execution）；重试需重新输图形码。
     *
     * v1.8.11 重做（用户实报「验证会话被学校服务重置」+「临时弹窗显示不全」）：
     *  1. 短信码为空不再关对话框——点「完成登录」直接在原对话框内提示，避免
     *     整个 MFA 流程作废重来（旧版按钮一点就关，白填一遍）；
     *  2. 失败按 ErrKind 分流（旧版一切失败都报「会话被重置」并强制重登，误导）：
     *     - NETWORK：本机网络异常，CAS 会话未受影响——重开同一对话框（execution
     *       与已填短信码保留），直接再点「完成登录」即可；
     *     - SESSION_LOST：先自动重登 + 用同一短信码重提一次（若短信码绑定在
     *       账号而非会话上，用户无感救回）；仍失败才开新对话框重发短信；
     *     - SERVER_ERROR/OTHER：提示后重开同一对话框供重试；
     *  3. 全部提示改 Notice（需手动确认，长文案可完整阅读）。
     */
    private fun showMfaDialog(
        mfa: com.dut.runmate.auth.CasAuth.Result.MfaRequired,
        number: String, password: String, wsOk: Boolean,
        prefillSms: String = ""
    ) {
        if (_b == null) return
        val view = layoutInflater.inflate(R.layout.dialog_mfa, null)
        val tvPhone = view.findViewById<TextView>(R.id.tvMfaPhone)
        val imgCode = view.findViewById<ImageView>(R.id.imgMfaCode)
        val etGraphic = view.findViewById<EditText>(R.id.etMfaGraphic)
        val btnSend = view.findViewById<Button>(R.id.btnMfaSend)
        val etSms = view.findViewById<EditText>(R.id.etMfaSms)
        val tvErr = view.findViewById<TextView>(R.id.tvMfaErr)

        tvPhone.text = getString(R.string.mfa_intro) + "\n" +
            getString(R.string.mfa_phone_fmt, mfa.phoneTail.ifBlank { "****" })
        if (prefillSms.isNotBlank()) etSms.setText(prefillSms)

        fun showErr(res: Int) {
            tvErr.setText(res); tvErr.visibility = View.VISIBLE
        }

        fun showErrText(s: String) {
            tvErr.text = s; tvErr.visibility = View.VISIBLE
        }

        // ① 图形验证码：拉取 + 点击刷新
        fun loadCaptcha() {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val bytes = com.dut.runmate.auth.CasAuth.fetchCaptcha()
                withContext(Dispatchers.Main) {
                    if (_b == null) return@withContext
                    if (bytes == null) {
                        showErr(R.string.mfa_fb_network_error)
                    } else {
                        tvErr.visibility = View.GONE
                        imgCode.setImageBitmap(
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                    }
                }
            }
        }
        imgCode.setOnClickListener { loadCaptcha() }
        loadCaptcha()

        // v1.8.9：持有对话框引用（get_session_error 时需主动关闭后重开登录流程）
        var dialog: androidx.appcompat.app.AlertDialog? = null

        // ② 发送短信验证码（需图形码；反馈码来自 Set-Cookie，与网页端同源）
        btnSend.setOnClickListener {
            val graphic = etGraphic.text.toString().trim()
            if (graphic.isBlank()) { showErr(R.string.mfa_need_graphic); return@setOnClickListener }
            btnSend.isEnabled = false
            btnSend.setText(R.string.mfa_sending)
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val fb = com.dut.runmate.auth.CasAuth.sendMfaSms(graphic)
                withContext(Dispatchers.Main) {
                    if (_b == null) return@withContext
                    btnSend.isEnabled = true
                    btnSend.setText(R.string.mfa_send_sms)
                    when (fb) {
                        "success" -> {
                            // v1.8.9 关键修复：发送成功后【绝不】再拉 /cas/code！
                            // 官方页面仅在错误分支刷新图形码，成功分支从不刷新——
                            // 短信已发出后服务端流程进入「待提交短信码」状态，
                            // 此时再请求 /cas/code 会重置二次认证流程：提交短信码必报
                            // 「验证码过期或无效」（用户实报问题1），之后的再次发送也
                            // 会报 get_session_error（用户实报问题2）。需要重发时用户
                            // 自己点图片刷新（此时会重新发送，流程重新推进，安全）。
                            showErr(R.string.mfa_send_ok)
                            etGraphic.setText("")
                        }
                        "img_code_error" -> {
                            showErr(R.string.mfa_fb_img_code_error)
                            loadCaptcha()
                            etGraphic.setText("")
                        }
                        "error1" -> showErr(R.string.mfa_fb_error1)
                        "error2" -> showErr(R.string.mfa_fb_error2)
                        "get_times_more" -> showErr(R.string.mfa_fb_get_times_more)
                        "user_not_exist" -> showErr(R.string.mfa_fb_user_not_exist)
                        "network_error" -> showErr(R.string.mfa_fb_network_error)
                        // v1.8.9：get_session_error = 本次登录会话已被服务端作废
                        // （旧流程状态被重置/过期），唯一恢复方式 = 重新 login()。
                        // 自动重开登录流程，用户只需在新弹出的对话框里重新验证。
                        "get_session_error" -> {
                            dialog?.dismiss()
                            Notice.show(this@SettingsFragment,
                                getString(R.string.mfa_fb_session_error),
                                getString(R.string.mfa_title))
                            doLogin()
                        }
                        else -> showErrText(getString(R.string.mfa_fb_unknown, fb))
                    }
                }
            }
        }

        // v1.8.11：提交逻辑提取（按钮提交 / 网络错误重试 / 会话找回重提 共用）。
        // attemptRescue：SESSION_LOST 时是否尝试「重登+同码重提」自动找回（只试一层，
        // 防递归循环）
        fun doSubmit(sms: String, execution: String, attemptRescue: Boolean) {
            b.btnWsLogin.isEnabled = false
            b.tvWsStatus.text = getString(R.string.mfa_submitting)
            viewLifecycleOwner.lifecycleScope.launch {
                val r2 = com.dut.runmate.auth.CasAuth.submitMfa(sms, execution)
                if (_b == null) return@launch
                b.btnWsLogin.isEnabled = true
                when (r2) {
                    is com.dut.runmate.auth.CasAuth.Result.Ok -> {
                        // 与直登成功同路径：落盘 + 原生一键配置
                        prefs.casTgt = r2.castgc
                        prefs.wsNumber = number
                        prefs.wsPwdSaved = android.util.Base64.encodeToString(
                            password.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                        renderWsStatus()
                        startNativeConfig(number, password)
                    }
                    is com.dut.runmate.auth.CasAuth.Result.MfaRetry -> {
                        // 短信码未过但流程仍在二次认证页——用响应页的
                        // 【新 execution】重开对话框（实测 PM1 提交后 e1s2→e1s3
                        // 推进，旧值作废）。新对话框会重新拉图形码（此时流程允许）。
                        b.tvWsStatus.text = getString(R.string.ws_login_fail, r2.msg)
                        Notice.show(this@SettingsFragment, r2.msg, getString(R.string.mfa_title))
                        showMfaDialog(
                            com.dut.runmate.auth.CasAuth.Result.MfaRequired(
                                r2.execution,
                                r2.phoneTail.ifBlank { mfa.phoneTail }),
                            number, password, wsOk)
                    }
                    is com.dut.runmate.auth.CasAuth.Result.Err -> when (r2.kind) {
                        // v1.8.11：网络异常——CAS 会话大概率还活着，同一 execution
                        // 直接重试即可，不重登不重发短信（重发反而烧短信配额）
                        com.dut.runmate.auth.CasAuth.Result.ErrKind.NETWORK -> {
                            b.tvWsStatus.text = getString(R.string.ws_login_fail, r2.msg)
                            Notice.show(this@SettingsFragment,
                                getString(R.string.mfa_net_retry, r2.msg),
                                getString(R.string.mfa_title))
                            showMfaDialog(
                                com.dut.runmate.auth.CasAuth.Result.MfaRequired(
                                    execution, mfa.phoneTail),
                                number, password, wsOk, prefillSms = sms)
                        }
                        // v1.8.11：会话真没了——先自动「重登 + 同一短信码重提」找回
                        com.dut.runmate.auth.CasAuth.Result.ErrKind.SESSION_LOST -> {
                            if (attemptRescue) {
                                b.tvWsStatus.text = getString(R.string.mfa_rescuing)
                                val re = com.dut.runmate.auth.CasAuth.login(number, password)
                                if (_b == null) return@launch
                                when (re) {
                                    // 重登后学校又要二次认证 → 同一短信码自动重提一次
                                    // （若短信码绑定账号而非会话，直接救回，用户无感）
                                    is com.dut.runmate.auth.CasAuth.Result.MfaRequired ->
                                        doSubmit(sms, re.execution, attemptRescue = false)
                                    // 重登直出 CASTGC（学校未再要求 MFA）→ 直接走原生配置
                                    is com.dut.runmate.auth.CasAuth.Result.Ok -> {
                                        prefs.casTgt = re.castgc
                                        prefs.wsNumber = number
                                        prefs.wsPwdSaved = android.util.Base64.encodeToString(
                                            password.toByteArray(Charsets.UTF_8),
                                            android.util.Base64.NO_WRAP)
                                        renderWsStatus()
                                        startNativeConfig(number, password)
                                    }
                                    // 重登失败（Err/MfaRetry——login 不返回 MfaRetry，穷举）：
                                    // 如实提示，走完整重登流程
                                    else -> {
                                        val em = when (re) {
                                            is com.dut.runmate.auth.CasAuth.Result.Err -> re.msg
                                            is com.dut.runmate.auth.CasAuth.Result.MfaRetry -> re.msg
                                            else -> "登录失败"
                                        }
                                        b.tvWsStatus.text = getString(R.string.ws_login_fail, em)
                                        Notice.show(this@SettingsFragment,
                                            getString(R.string.mfa_session_lost_resend),
                                            getString(R.string.mfa_title))
                                        doLogin()
                                    }
                                }
                            } else {
                                // 已尝试过自动找回仍失败：开新流程重新发短信
                                b.tvWsStatus.text = getString(R.string.ws_login_fail, r2.msg)
                                Notice.show(this@SettingsFragment,
                                    getString(R.string.mfa_session_lost_resend),
                                    getString(R.string.mfa_title))
                                doLogin()
                            }
                        }
                        // SERVER_ERROR / OTHER：服务端异常或未知响应——提示后重开
                        // 同一对话框（execution 保留），稍后重试即可
                        else -> {
                            b.tvWsStatus.text = getString(R.string.ws_login_fail, r2.msg)
                            Notice.show(this@SettingsFragment, r2.msg, getString(R.string.mfa_title))
                            showMfaDialog(
                                com.dut.runmate.auth.CasAuth.Result.MfaRequired(
                                    execution, mfa.phoneTail),
                                number, password, wsOk, prefillSms = sms)
                        }
                    }
                    // submitMfa 不会返回 MfaRequired——占位保证穷尽
                    else -> {}
                }
            }
        }

        // v1.8.11：手动接管「完成登录」按钮——短信码为空时【不关对话框】，
        // 在框内提示（旧版按钮一点就自动关闭，空码即白填一遍，流程作废重来）
        val dlg = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mfa_title)
            .setView(view)
            .setPositiveButton(R.string.mfa_submit, null)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                if (wsOk) {
                    // 微哨通道已成功：仍可走 H5 捕获页兜底
                    renderWsStatus()
                    openH5Catcher(password)
                } else {
                    b.tvWsStatus.text = getString(R.string.ws_login_fail,
                        "未完成短信二次认证（学校新要求，网页/客户端登录均需短信验证）")
                }
            }
            .create()
        dlg.setOnShowListener {
            dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val sms = etSms.text.toString().trim()
                    if (sms.isBlank()) {
                        showErr(R.string.mfa_need_sms)
                        return@setOnClickListener
                    }
                    dlg.dismiss()
                    doSubmit(sms, mfa.execution, attemptRescue = true)
                }
        }
        dialog = dlg
        dlg.show()
    }

    private fun openH5Catcher(password: String) {
        val hasCredential = prefs.casTgt.isNotBlank() || prefs.wsSkey.isNotBlank()
        // v1.8.9：无已保存会话但学号+密码已填 → 也允许进入（学校开启短信二次认证后
        // 的兜底路径：页面自动填表，短信验证在页面上手动完成，捕获器不会再打扰）
        val hasForm = b.etWsNumber.text.toString().trim().isNotBlank() && password.isNotBlank()
        if (!hasCredential && !hasForm) {
            b.tvWsStatus.text = getString(R.string.ws_status_none)
            return
        }
        val it = android.content.Intent(requireContext(), H5CatcherActivity::class.java)
        it.putExtra(H5CatcherActivity.EXTRA_NUMBER, b.etWsNumber.text.toString().trim())
        if (password.isNotEmpty()) it.putExtra(H5CatcherActivity.EXTRA_PASSWORD, password)
        startActivity(it)
    }

    /**
     * v1.8.3：原生一键配置成功弹窗——姓名/学号/班级 + 距离验证结果 + 使用提示。
     * 替代旧版「登录成功 → 打开 H5 捕获页」的两步流程，一步到位。
     */
    private fun showNativeOkDialog(s: com.dut.runmate.auth.SportAuth.Session) {
        if (_b == null) return
        val distLine = if (s.distanceRaw == "无")
            getString(R.string.ws_native_dist_none)
        else
            getString(R.string.ws_native_dist, s.distanceRaw)
        val msg = getString(R.string.ws_native_ok_body,
            s.name, s.number, s.userId.take(8), distLine)
        try {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ws_native_ok_title)
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } catch (_: Throwable) {
        }
    }

    // ==================== v1.7.0 多账号 ====================

    /** chips 显示名：优先姓名，无姓名用学号 */
    private fun acctLabel(a: WsAccount): String =
        a.name.ifBlank { a.number }.ifBlank { "未命名" }

    /**
     * 渲染账号 chips：✓=当前活跃账号；点击切换；长按删除；末尾「＋添加」录入新账号。
     * 「查询全部账号距离」按钮：至少一个账号完成过 H5 捕获才显示。
     */
    private fun renderAccounts() {
        val list = Accounts.load(prefs, apiStore)
        val active = prefs.wsNumber.trim()
        b.layAccounts.removeAllViews()
        for (acc in list) {
            val chip = Chip(requireContext())
            val isActive = acc.number == active
            chip.text = (if (isActive) "✓ " else "") + acctLabel(acc) +
                if (acc.captured) "" else "（未捕获）"
            chip.isCheckable = false
            chip.setOnClickListener { runCatching { switchAccount(acc.number) } }
            chip.setOnLongClickListener {
                confirmDeleteAccount(acc)
                true
            }
            b.layAccounts.addView(chip)
        }
        val addChip = Chip(requireContext())
        addChip.text = getString(R.string.acct_add)
        addChip.isCheckable = false
        addChip.setOnClickListener {
            b.etWsNumber.setText("")
            b.etWsPwd.setText("")
            b.etWsNumber.requestFocus()
            b.tvWsStatus.text = getString(R.string.acct_add_hint)
        }
        b.layAccounts.addView(addChip)

        b.scrollAccounts.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        b.btnQueryAll.visibility = if (list.any { it.captured }) View.VISIBLE else View.GONE
    }

    /** 切换活跃账号：快照当前 → 目标写入 Prefs/DIST 模板 → 刷新整页 */
    private fun switchAccount(number: String) {
        val acc = Accounts.byNumber(prefs, apiStore, number) ?: return
        val changed = Accounts.switchTo(prefs, apiStore, number)
        if (!changed) return
        b.etWsNumber.setText(acc.number)
        b.etWsPwd.setText(prefs.wsPwdDecoded())
        renderWsStatus()
        renderAccounts()
        // v1.8.11：Toast 改需手动确认对话框（长文案可完整阅读）
        Notice.show(this, getString(
            if (acc.captured) R.string.acct_switched else R.string.acct_switch_need_capture,
            acctLabel(acc)))
    }

    private fun confirmDeleteAccount(acc: WsAccount) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.acct_delete_title)
            .setMessage(getString(R.string.acct_delete_msg, acctLabel(acc)))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                runCatching { deleteAccountFlow(acc) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 删除账号。删的是当前活跃账号时：先清空活跃字段再切换到剩余账号
     * （顺序很关键 —— switchAccount 会快照当前状态，若不清空会把刚删的账号又加回来）。
     */
    private fun deleteAccountFlow(acc: WsAccount) {
        Accounts.delete(prefs, apiStore, acc.number)
        if (acc.number == prefs.wsNumber.trim()) {
            prefs.wsNumber = ""; prefs.wsPwdSaved = ""
            prefs.wsUserId = ""; prefs.wsSkey = ""; prefs.wsName = ""
            prefs.casTgt = ""; prefs.apiToken = ""; prefs.apiCookie = ""
            val remaining = Accounts.load(prefs, apiStore)
            if (remaining.isNotEmpty()) {
                renderAccounts()
                switchAccount(remaining.first().number)
                return
            }
            b.etWsNumber.setText(""); b.etWsPwd.setText("")
            renderWsStatus()
        }
        renderAccounts()
        Notice.show(this, getString(R.string.acct_deleted, acctLabel(acc)))
    }

    /** 清空活跃账号的全部本机信息（不动作账号列表） */
    private fun clearActiveAccount() {
        prefs.wsNumber = ""; prefs.wsPwdSaved = ""
        prefs.wsUserId = ""; prefs.wsSkey = ""; prefs.wsName = ""
        prefs.casTgt = ""; prefs.apiToken = ""; prefs.apiCookie = ""
        b.etWsNumber.setText(""); b.etWsPwd.setText("")
        renderWsStatus()
    }

    /**
     * v1.7.0 核心：批量查询全部账号距离。
     * 每个账号用它自己的接口快照（URL/请求体/令牌/Cookie）逐个请求 findExtExercise，
     * 校外直连失败会标注原因；结果汇总成持久对话框（可复制）。
     */
    private fun queryAll() {
        if (queryingAll) return
        // 先把当前活跃账号最新状态入库（刚捕获/刚改过配置也能查到）
        try {
            Accounts.snapshotCurrent(prefs, apiStore)?.let { Accounts.upsert(prefs, apiStore, it) }
        } catch (_: Exception) {
        }
        val accounts = Accounts.load(prefs, apiStore)
        if (accounts.isEmpty()) {
            Notice.show(this, R.string.acct_query_none)
            return
        }
        queryingAll = true
        b.btnQueryAll.isEnabled = false
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.acct_query_title, accounts.size))
            .setMessage(getString(R.string.acct_query_progress, 0, accounts.size,
                acctLabel(accounts.first())))
            .setCancelable(false)
            .show()

        viewLifecycleOwner.lifecycleScope.launch {
            val sb = StringBuilder()
            var offCampusAny = false
            try {
                accounts.forEachIndexed { i, acc ->
                    if (_b == null) {                       // 页面已销毁：放弃 UI 更新
                        dialog.dismiss(); queryingAll = false; return@launch
                    }
                    dialog.setMessage(getString(R.string.acct_query_progress,
                        i + 1, accounts.size, acctLabel(acc)))
                    val line = queryAccountLine(acc)
                    if (line.contains("【校外直连】")) offCampusAny = true
                    sb.append(line).append('\n')
                }
            } catch (e: CancellationException) {
                dialog.dismiss()
                queryingAll = false        // v1.8.2：页面销毁取消时同样复位（见 onViewCreated 注释）
                throw e
            } catch (_: Exception) {
            }
            if (offCampusAny) sb.append('\n').append(getString(R.string.api_hint_offcampus))
            if (sb.contains("【需重新捕获】"))
                sb.append('\n').append(getString(R.string.api_hint_webvpn_expired))
            dialog.dismiss()
            if (_b == null) return@launch
            val result = sb.toString()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.acct_query_title, accounts.size))
                .setMessage(result)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.h5_test_copy) { _, _ ->
                    runCatching {
                        val cm = requireActivity().getSystemService(
                            android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("runmate", result))
                        Notice.show(this@SettingsFragment, R.string.pref_crash_copied)
                    }
                }
                .show()
            queryingAll = false
            b.btnQueryAll.isEnabled = true
        }
    }

    /** 查询单个账号的距离（IO 挂起），返回一行结果文本 */
    private suspend fun queryAccountLine(acc: WsAccount): String {
        val label = acctLabel(acc)
        if (!acc.captured) return "✗ $label：" + getString(R.string.acct_not_captured)
        val prof = ApiProfile(
            id = "batch_${acc.number}", title = label, method = "POST",
            url = acc.apiUrl, headers = acc.apiHeaders.ifBlank { "{}" },
            body = acc.apiBody, distPath = acc.apiDistPath.ifBlank { ApiStore.DIST_PATH }
        )
        val resp = Http.call(prof, acc.apiToken, acc.apiCookie)
        return when {
            resp.error != null -> {
                val off = resp.error.contains("connect", true) &&
                        acc.apiUrl.contains("202.118.65.138") &&
                        !acc.apiUrl.contains("webvpn")
                "✗ $label：${resp.error}" + if (off) "【校外直连】" else ""
            }
            // v1.8.0：隧道会话失效被重定向到登录页（HTTP 200 但拿回的是 HTML）
            Http.isWebvpnLoginRedirect(resp.finalUrl, resp.body, resp.url) ->
                "✗ $label：WebVPN 会话已过期【需重新捕获】"
            resp.code == 200 -> {
                val dv = Http.extractDouble(resp.body, prof.distPath)
                if (dv != null) "✓ $label：${String.format("%.0f", dv)} m（${resp.ms} ms）"
                else "✗ $label：HTTP 200 但未提取到距离（令牌可能过期，切换到该账号重新捕获）"
            }
            else -> "✗ $label：HTTP ${resp.code}（令牌可能过期，切换到该账号重新捕获）"
        }
    }

    override fun onResume() {
        super.onResume()
        // v1.6.2 + v1.8.14：从 H5 捕获页返回后刷新登录状态/账号列表/API 开关。
        // MainActivity 用 replace() 管理 Fragment、捕获页是覆盖其上的独立 Activity，
        // 返回时 View 不重建——旧版令牌框不刷新，即使捕获成功用户也以为「没自动填充」。
        if (_b != null) {
            b.swApi.isChecked = prefs.apiEnabled
            renderWsStatus()
            // v1.7.0：从 H5 捕获页返回后刷新账号 chips（新捕获的账号会自动入库）
            renderAccounts()
        }
    }

    override fun onPause() {
        // v1.5.0：更新地址已内置，离开设置页无需再保存
        super.onPause()
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }
}
