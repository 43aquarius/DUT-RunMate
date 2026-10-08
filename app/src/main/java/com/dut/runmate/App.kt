package com.dut.runmate

import android.app.Application
import org.osmdroid.config.Configuration
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val cfg = Configuration.getInstance()
        cfg.userAgentValue = packageName
        // 独立缓存目录，避免与其他应用冲突
        cfg.osmdroidBasePath = File(filesDir, "osmdroid")
        cfg.osmdroidTileCache = File(cacheDir, "tiles")
        cfg.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
    }
}
