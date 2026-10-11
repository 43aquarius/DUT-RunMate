package com.dut.runmate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.dut.runmate.databinding.ActivityMainBinding
import com.dut.runmate.run.RunBus
import com.dut.runmate.ui.ApiFragment
import com.dut.runmate.ui.CalibrateFragment
import com.dut.runmate.ui.MapFragment
import com.dut.runmate.ui.RunFragment
import com.dut.runmate.ui.SettingsFragment
import com.dut.runmate.update.UpdateFlow
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val runFrag by lazy { RunFragment() }
    private val mapFrag by lazy { MapFragment() }
    private val calibFrag by lazy { CalibrateFragment() }
    private val apiFrag by lazy { ApiFragment() }
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
                R.id.nav_api -> swap(apiFrag)
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

        // v1.8.4 锁屏显示：跑步进行中允许窗口显示在锁屏之上并随亮屏点亮。
        // v1.8.3 已把跑步通知设为锁屏公开（渠道级），但用户实测锁屏上仍只能
        // 看到通知入口、无法直接看到应用——真正的「应用显示在锁屏界面」需要
        // Activity 声明 SHOW_WHEN_LOCKED：跑步中锁屏后再亮屏，直接显示跑步
        // 界面，无需解锁。停止跑步立即恢复，避免非跑步场景绕过锁屏。
        lifecycleScope.launch {
            RunBus.state.collect { st ->
                val on = st.running
                try {
                    if (Build.VERSION.SDK_INT >= 27) {
                        setShowWhenLocked(on)
                        setTurnScreenOn(on)
                    } else {
                        @Suppress("DEPRECATION")
                        if (on) {
                            window.addFlags(
                                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            )
                        } else {
                            window.clearFlags(
                                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            )
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        }
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
