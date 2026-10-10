package com.dut.runmate

import android.app.Application
import org.osmdroid.config.Configuration
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class App : Application() {

    /** v1.5.0：未捕获异常落盘（filesDir/crash_log.txt），保留最近一次，便于用户反馈定位 */
    private fun installCrashLogger() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val sb = StringBuilder()
                sb.append("time=").append(System.currentTimeMillis()).append('\n')
                sb.append("thread=").append(t.name).append('\n')
                sb.append(sw.toString())
                File(filesDir, "crash_log.txt").writeText(sb.toString())
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        val cfg = Configuration.getInstance()
        cfg.userAgentValue = packageName
        // 独立缓存目录，避免与其他应用冲突
        cfg.osmdroidBasePath = File(filesDir, "osmdroid")
        cfg.osmdroidTileCache = File(cacheDir, "tiles")
        cfg.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
    }
}
