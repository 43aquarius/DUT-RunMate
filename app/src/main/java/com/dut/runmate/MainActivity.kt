package com.dut.runmate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.dut.runmate.databinding.ActivityMainBinding
import com.dut.runmate.ui.CalibrateFragment
import com.dut.runmate.ui.MapFragment
import com.dut.runmate.ui.RunFragment
import com.dut.runmate.ui.SettingsFragment
import com.dut.runmate.update.UpdateFlow

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val runFrag by lazy { RunFragment() }
    private val mapFrag by lazy { MapFragment() }
    private val calibFrag by lazy { CalibrateFragment() }
    private val settingsFrag by lazy { SettingsFragment() }

    private var cur: Fragment? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_run -> swap(runFrag)
                R.id.nav_map -> swap(mapFrag)
                R.id.nav_calib -> swap(calibFrag)
                R.id.nav_settings -> swap(settingsFrag)
            }
            true
        }
        if (savedInstanceState == null) {
            b.bottomNav.selectedItemId = R.id.nav_run
            askPermissions()
        }

        b.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_settings -> {
                    // v1.3.0：齿轮同样切到设置 Tab（设置已是底部导航一级页面）
                    b.bottomNav.selectedItemId = R.id.nav_settings
                    true
                }
                else -> false
            }
        }

        // v1.8.6：锁屏显示改造说明——
        // v1.8.4 曾在此处随跑步状态 setShowWhenLocked/setTurnScreenOn（把整个主页面
        // 显示在锁屏上）。用户澄清：想要的是「音乐软件式的锁屏卡片，只显示关键信息
        // （当前打卡距离、有没有漏卡）」，不是整页显示。故移除本段，锁屏显示改由
        // RunTrackerService 的 MediaSession + MediaStyle 前台通知（媒体卡片）承担，
        // 见 RunTrackerService.setupMediaSession/pushMediaMeta。
    }

    override fun onResume() {
        super.onResume()
        // v1.8.5：每次进入 App（冷启动/后台回前台）都静默检查更新，
        // GitHub Release 优先——发布源头第一时间可见；详见 UpdateFlow.maybeAutoCheck。
        // 放在 resumePendingInstall 之前：下载待安装期间跳过检查，不重复弹窗。
        UpdateFlow.maybeAutoCheck(this)
        UpdateFlow.resumePendingInstall(this)
    }

    private fun swap(f: Fragment) {
        if (cur == f) return
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, f)
            .commitAllowingStateLoss()
        cur = f
    }

    private fun askPermissions() {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.ACCESS_FINE_LOCATION
            need += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.ACCESS_BACKGROUND_LOCATION
        }
        if (need.isNotEmpty()) permLauncher.launch(need.toTypedArray())
    }
}
