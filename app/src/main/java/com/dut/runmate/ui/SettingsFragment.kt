package com.dut.runmate.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.dut.runmate.BuildConfig
import com.dut.runmate.R
import com.dut.runmate.data.CheckpointStore
import com.dut.runmate.data.Prefs
import com.dut.runmate.databinding.FragmentSettingsBinding
import com.dut.runmate.update.UpdateFlow
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 设置页（v1.3.0 起为底部导航第 5 个 Tab，替代原独立 SettingsActivity）。
 *
 * 此前设置入口只有主界面右上角齿轮，不够显眼——用户反馈「app内没有设置」，
 * 现在设置是一级页面，「检查更新」直接可见。
 */
class SettingsFragment : Fragment() {

    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!
    private val prefs by lazy { Prefs.get(requireContext()) }
    private val store by lazy { CheckpointStore.get(requireContext().filesDir) }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentSettingsBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        super.onViewCreated(v, savedInstanceState)

        // 检测参数
        //
        // v1.4.0 修复闪退：Material 1.12 的 Slider.setValue() 要求值必须落在
        // [valueFrom, valueTo] 且与 stepSize 网格对齐，否则抛 IllegalArgumentException。
        // 旧版默认 graceSec=12 不在 5/10/15… 网格上 → 一进设置页就崩。
        // bindSlider 现在先夹取到滑杆范围、再吸附到最近网格点，并把吸附后的值
        // 写回偏好（自愈），保证任何历史脏数据都能被安全渲染。
        fun bindSlider(
            slider: com.google.android.material.slider.Slider,
            tv: android.widget.TextView, suffix: String,
            get: () -> Int, set: (Int) -> Unit
        ) {
            val raw = get().toFloat()
            val from = slider.valueFrom
            val to = slider.valueTo
            val step = slider.stepSize
            val clamped = raw.coerceIn(from, to)
            val v = if (step > 0f) {
                val idx = Math.round((clamped - from) / step)
                (from + idx * step).coerceIn(from, to)
            } else clamped
            if (v != raw) set(v.toInt())          // 自愈历史脏数据
            slider.value = v
            tv.text = "${v.toInt()} $suffix"
            slider.addOnChangeListener { _, value, _ ->
                val i = value.toInt()
                set(i)
                tv.text = "$i $suffix"
            }
        }
        bindSlider(b.sliderRadius, b.tvRadius, "m", { prefs.defaultRadius }, { prefs.defaultRadius = it })
        bindSlider(b.sliderApproach, b.tvApproach, "m", { prefs.approachDist }, { prefs.approachDist = it })
        bindSlider(b.sliderDelta, b.tvDelta, "m", { prefs.confirmDelta }, { prefs.confirmDelta = it })
        bindSlider(b.sliderPoll, b.tvPoll, "s", { prefs.pollInterval }, { prefs.pollInterval = it })
        bindSlider(b.sliderGrace, b.tvGrace, "s", { prefs.graceSec }, { prefs.graceSec = it })

        // 提醒
        b.swTts.isChecked = prefs.ttsEnabled
        b.swTts.setOnCheckedChangeListener { _, c -> prefs.ttsEnabled = c }
        b.swChime.isChecked = prefs.chimeEnabled
        b.swChime.setOnCheckedChangeListener { _, c -> prefs.chimeEnabled = c }
        b.swVibrate.isChecked = prefs.vibrateEnabled
        b.swVibrate.setOnCheckedChangeListener { _, c -> prefs.vibrateEnabled = c }

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
     * v1.8.13：分场景提醒开关矩阵——9 种提醒场景 ×「语音」「震动」两个可勾选
     * Chip，即点即存（SharedPreferences）。与总开关（语音/震动）叠加：两层都
     * 开才触发。提示音保持全局开关不分场景（提醒主通道是语音+震动）。
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
            com.dut.runmate.alert.AlertManager.Voice.END to getString(R.string.scn_end)
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
                setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Body1)
                layoutParams = android.widget.LinearLayout.LayoutParams(0,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val chipV = com.google.android.material.chip.Chip(act).apply {
                text = getString(R.string.scn_voice)
                isCheckable = true
                isChecked = prefs.scnVoiceOn(v.scn)
                setOnCheckedChangeListener { _, c -> prefs.setScnVoiceOn(v.scn, c) }
            }
            val chipB = com.google.android.material.chip.Chip(act).apply {
                text = getString(R.string.scn_vib)
                isCheckable = true
                isChecked = prefs.scnVibOn(v.scn)
                setOnCheckedChangeListener { _, c -> prefs.setScnVibOn(v.scn, c) }
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = pad / 2 }
            }
            row.addView(chipV)
            row.addView(chipB)
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

    override fun onPause() {
        // v1.5.0：更新地址已内置，离开设置页无需再保存
        super.onPause()
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }
}
