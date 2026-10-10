package com.dut.runmate.update

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.dut.runmate.R
import com.dut.runmate.data.Prefs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.io.File

/**
 * 更新流程编排：自动检查（每天一次）/ 手动检查 → 弹窗 → 进度下载 → 安装。
 *
 * v1.6.1 反馈改造：检查失败 / 下载失败不再用一闪而过的 Toast，改用持久对话框，
 * 完整展示错误详情，并提供「重试 / 浏览器下载 / 复制链接」兜底——应用内下载
 * 被网络策略拦截时，浏览器直下往往仍可用。
 */
object UpdateFlow {

    private const val CHECK_INTERVAL_MS = 24 * 3600_000L
    private var pendingApk: File? = null     // 授权「安装未知应用」回来后继续安装

    /** 启动时自动检查（静默失败） */
    fun maybeAutoCheck(activity: AppCompatActivity) {
        val prefs = Prefs.get(activity)
        if (!prefs.updAutoCheck) return
        val now = System.currentTimeMillis()
        if (now - prefs.updLastCheckMs < CHECK_INTERVAL_MS) return
        prefs.updLastCheckMs = now
        runCheck(activity, silent = true)
    }

    /** 手动检查（设置页按钮）：始终执行并给出反馈 */
    fun manualCheck(activity: AppCompatActivity) {
        Prefs.get(activity).updLastCheckMs = System.currentTimeMillis()
        runCheck(activity, silent = false)
    }

    /** 从系统设置（安装未知应用授权页）返回后，继续未完成的安装 */
    fun resumePendingInstall(activity: Activity) {
        val apk = pendingApk ?: return
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) return
        pendingApk = null
        Updater.install(activity, apk)
    }

    private fun runCheck(activity: AppCompatActivity, silent: Boolean) {
        activity.lifecycleScope.launch {
            when (val r = Updater.check(activity)) {
                is Updater.CheckResult.HasUpdate -> showUpdateDialog(activity, r.info)
                is Updater.CheckResult.UpToDate -> if (!silent)
                    Toast.makeText(activity, R.string.upd_uptodate, Toast.LENGTH_SHORT).show()
                is Updater.CheckResult.Failed -> if (!silent)
                    showCheckFailedDialog(activity, r.reason)
            }
        }
    }

    /** v1.6.1：检查失败 → 持久对话框（完整错误 + 打开发布中心 + 复制） */
    private fun showCheckFailedDialog(activity: AppCompatActivity, reason: String) {
        val msg = activity.getString(R.string.upd_check_fail_body, reason, Updater.SERVER_BASE)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.upd_check_fail_title)
            .setMessage(msg)
            .setPositiveButton(R.string.upd_open_site) { _, _ -> openBrowser(activity, Updater.SERVER_BASE) }
            .setNeutralButton(R.string.pref_crash_copy) { _, _ -> copyText(activity, msg) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showUpdateDialog(activity: AppCompatActivity, info: Updater.UpdateInfo) {
        val src = activity.getString(if (info.source == "server") R.string.upd_src_server else R.string.upd_src_github)
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.upd_found_title, info.versionName))
            .setMessage(activity.getString(R.string.upd_found_msg, src, info.notes.ifBlank { "-" }))
            .setPositiveButton(R.string.upd_now) { _, _ -> startDownload(activity, info) }
            .setNegativeButton(R.string.upd_later, null)
            .show()
    }

    private fun startDownload(activity: AppCompatActivity, info: Updater.UpdateInfo) {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
        }
        val tv = TextView(activity).apply {
            text = activity.getString(R.string.upd_dl_connecting)
            gravity = Gravity.CENTER
        }
        layout.addView(bar)
        layout.addView(tv)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.upd_dl_title, info.versionName))
            .setView(layout)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel, null)
            .show()

        activity.lifecycleScope.launch {
            try {
                val apk = Updater.download(activity, info.apkUrl, info.sha256) { read, total ->
                    if (total > 0) {
                        bar.progress = (read * 1000 / total).toInt()
                        tv.text = activity.getString(
                            R.string.upd_dl_progress,
                            read / 1024 / 1024, total / 1024 / 1024, read * 100 / total
                        )
                    } else {
                        tv.text = activity.getString(R.string.upd_dl_bytes, read / 1024 / 1024)
                    }
                }
                dialog.dismiss()
                tryInstall(activity, apk)
            } catch (e: Exception) {
                dialog.dismiss()
                // v1.6.1：下载失败 → 持久对话框（重试 / 浏览器下载 / 复制链接）
                val detail = "${e.message ?: e.javaClass.simpleName}\n\n${activity.getString(R.string.upd_dl_url_label)}\n${info.apkUrl}"
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.upd_dl_err_title)
                    .setMessage(detail)
                    .setPositiveButton(R.string.upd_retry) { _, _ -> startDownload(activity, info) }
                    .setNeutralButton(R.string.upd_browser_dl) { _, _ -> openBrowser(activity, info.apkUrl) }
                    .setNegativeButton(R.string.upd_copy_link) { _, _ -> copyText(activity, info.apkUrl) }
                    .show()
            }
        }
    }

    private fun openBrowser(activity: Activity, url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            // 无浏览器可用时退回复制
            copyText(activity, url)
        }
    }

    private fun copyText(activity: Activity, text: String) {
        try {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("runmate", text))
            Toast.makeText(activity, R.string.pref_crash_copied, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }

    private fun tryInstall(activity: AppCompatActivity, apk: File) {
        if (Updater.install(activity, apk)) return
        // Android 8+ 未授予「安装未知应用」→ 引导去系统设置，回来后继续
        pendingApk = apk
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.upd_perm_title)
            .setMessage(R.string.upd_perm_msg)
            .setPositiveButton(R.string.upd_perm_go) { _, _ ->
                try {
                    activity.startActivity(
                        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}"))
                    )
                } catch (_: Exception) {
                    Toast.makeText(activity, R.string.upd_perm_manual, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
