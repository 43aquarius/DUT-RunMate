package com.dut.runmate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dut.runmate.data.CheckpointStore
import com.dut.runmate.data.Prefs
import com.dut.runmate.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private val prefs by lazy { Prefs.get(this) }
    private val store by lazy { CheckpointStore.get(filesDir) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.toolbar.setNavigationOnClickListener { finish() }

        // 检测参数
        fun bindSlider(
            slider: com.google.android.material.slider.Slider,
            tv: android.widget.TextView, suffix: String,
            get: () -> Int, set: (Int) -> Unit
        ) {
            slider.value = get().toFloat()
            tv.text = "${get()} $suffix"
            slider.addOnChangeListener { _, v, _ ->
                val i = v.toInt()
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

        // 系统
        b.btnBattery.setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
                Toast.makeText(this, "请手动在系统设置中允许后台运行", Toast.LENGTH_LONG).show()
            }
        }
        b.btnExport.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("runmate", store.exportJson()))
            Toast.makeText(this, getString(R.string.pref_export_done, store.count), Toast.LENGTH_SHORT).show()
        }
    }
}
