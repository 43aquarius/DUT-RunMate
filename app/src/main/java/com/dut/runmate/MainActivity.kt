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
import com.dut.runmate.ui.ApiFragment
import com.dut.runmate.ui.CalibrateFragment
import com.dut.runmate.ui.MapFragment
import com.dut.runmate.ui.RunFragment

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val runFrag by lazy { RunFragment() }
    private val mapFrag by lazy { MapFragment() }
    private val calibFrag by lazy { CalibrateFragment() }
    private val apiFrag by lazy { ApiFragment() }

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
                    startActivity(Intent(this, SettingsActivity::class.java)); true
                }
                else -> false
            }
        }
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
