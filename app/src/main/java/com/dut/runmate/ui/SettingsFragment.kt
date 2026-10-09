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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.dut.runmate.BuildConfig
import com.dut.runmate.R
import com.dut.runmate.data.CheckpointStore
import com.dut.runmate.data.Prefs
import com.dut.runmate.databinding.FragmentSettingsBinding
import com.dut.runmate.update.UpdateFlow

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
        fun bindSlider(
            slider: com.google.android.material.slider.Slider,
            tv: android.widget.TextView, suffix: String,
            get: () -> Int, set: (Int) -> Unit
        ) {
            slider.value = get().toFloat()
            tv.text = "${get()} $suffix"
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

        // 更新
        b.swUpdAuto.isChecked = prefs.updAutoCheck
        b.swUpdAuto.setOnCheckedChangeListener { _, c -> prefs.updAutoCheck = c }
        b.etUpdServer.setText(prefs.updServerUrl)
        b.etUpdServer.setOnEditorActionListener { et, _, _ ->
            prefs.updServerUrl = et.text.toString().trim(); true
        }
        b.btnUpdCheck.setOnClickListener {
            prefs.updServerUrl = b.etUpdServer.text.toString().trim()
            (requireActivity() as? AppCompatActivity)?.let { UpdateFlow.manualCheck(it) }
        }

        // 系统
        b.btnBattery.setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${requireContext().packageName}"))
                )
            } catch (_: Exception) {
                Toast.makeText(requireContext(), "请手动在系统设置中允许后台运行", Toast.LENGTH_LONG).show()
            }
        }
        b.btnExport.setOnClickListener {
            val cm = requireActivity().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("runmate", store.exportJson()))
            Toast.makeText(requireContext(), getString(R.string.pref_export_done, store.count), Toast.LENGTH_SHORT).show()
        }

        // 关于（动态版本号）
        b.tvAbout.text = getString(R.string.pref_about_body, BuildConfig.VERSION_NAME)
    }

    override fun onPause() {
        // 离开设置页时保存更新地址，避免只点返回未触发 EditorAction
        if (_b != null) prefs.updServerUrl = b.etUpdServer.text.toString().trim()
        super.onPause()
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }
}
