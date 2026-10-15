package com.dut.runmate.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.dut.runmate.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 应用内更新：GitHub Release 检查 + 下载 + 安装。
 *
 * v1.8.13：用户拍板移除发布中心服务器通道（dut-runmate.space-z.ai），
 * 更新只从 GitHub Release 拉取——单一发布源，链路简单可预期。GitHub
 * 不可达时检查失败弹窗提供「浏览器下载 / 复制链接」兜底。
 *
 * v1.8.5 历史结论保留（GitHub 资产 URL 小写归一仍生效）：GitHub 对仓库名
 * 大小写不敏感，但实测该仓库精确大小写形式（DUT-RunMate）的名称路由
 * （API/网页/资产下载）一度整体 404，小写形式始终正常——所有出站
 * GitHub URL 一律使用小写仓库名，并对资产下载 URL 里的仓库段做小写归一。
 *
 * GitHub：GET /repos/{repo}/releases/latest，tag_name 形如 v1.1.0，
 * 通过语义化版本与当前 VERSION_NAME 比较；资产取第一个 .apk。
 */
object Updater {

    /** v1.8.5：仓库标识（出站 URL 用，一律小写，见对象头注释） */
    const val GITHUB_REPO = "43aquarius/dut-runmate"

    /** 仓库名（仅界面显示用，如设置页副标题、复制链接提示） */
    const val GITHUB_REPO_DISPLAY = "43aquarius/DUT-RunMate"

    /** v1.8.13：GitHub Release 页（检查失败时的浏览器兜底入口） */
    const val GITHUB_RELEASES_PAGE = "https://github.com/$GITHUB_REPO/releases"

    private const val GITHUB_API = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    data class UpdateInfo(
        val versionName: String,
        val apkUrl: String,
        val sha256: String?,
        val sizeBytes: Long,
        val notes: String,
        val source: String          // 恒为 "github"（v1.8.13 起唯一通道，保留字段兼容 UI）
    )

    sealed class CheckResult {
        data class HasUpdate(val info: UpdateInfo) : CheckResult()
        data object UpToDate : CheckResult()
        data class Failed(val reason: String) : CheckResult()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val dlClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 检查更新（v1.8.13：仅 GitHub Release 通道，重试 2 次抗抖动，
     * 间隔 1.5s）。检查在后台协程进行；失败如实抛给调用方展示并
     * 引导浏览器兜底。
     */
    suspend fun check(ctx: Context): CheckResult = withContext(Dispatchers.IO) {
        var err = ""
        for (attempt in 1..2) {
            try {
                return@withContext checkGithub()
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
                if (attempt == 1) delay(1500)
            }
        }
        CheckResult.Failed("GitHub Release 通道（重试 2 次）: ${err.ifBlank { "未知" }}")
    }

    /** GitHub Release 通道（v1.8.13 起唯一通道；国内偶发不可达，失败弹窗引导浏览器兜底） */
    private fun checkGithub(): CheckResult {
        val req = Request.Builder().url(GITHUB_API)
            .header("User-Agent", "DUT-RunMate-Updater")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
            val root = JSONObject(r.body?.string() ?: throw RuntimeException("空响应"))
            val tag = root.optString("tag_name", "").removePrefix("v")
            if (tag.isBlank() || semverCompare(tag, BuildConfig.VERSION_NAME) <= 0)
                return CheckResult.UpToDate
            val assets = root.optJSONArray("assets") ?: throw RuntimeException("Release 无资产")
            var asset: JSONObject? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { asset = a; break }
            }
            asset ?: throw RuntimeException("Release 无 APK 资产")
            return CheckResult.HasUpdate(
                UpdateInfo(
                    versionName = tag,
                    // v1.8.5：下载 URL 里的仓库段小写归一——GitHub 返回的
                    // browser_download_url 携带精确大小写仓库名，该形式的资产
                    // 路由曾整体 404；小写形式始终可用（GitHub 大小写不敏感）。
                    apkUrl = asset.getString("browser_download_url")
                        .replace("/$GITHUB_REPO_DISPLAY/", "/$GITHUB_REPO/", ignoreCase = true),
                    sha256 = null,           // GitHub 资产 API 不直接给 sha256
                    sizeBytes = asset.optLong("size", 0L),
                    notes = root.optString("body", ""),
                    source = "github"
                )
            )
        }
    }

    /** "1.2.3" 比较：>0 表示 a 更新 */
    fun semverCompare(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    /**
     * 下载 APK 到应用外部私有目录（update/runmate_update.apk）。
     * @param onProgress (已读字节, 总字节[未知为0]) 主线程回调
     * @throws RuntimeException 校验失败/网络失败时抛出（文件已清理）
     */
    suspend fun download(
        ctx: Context, url: String, sha256: String?,
        onProgress: (Long, Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.getExternalFilesDir(null), "update").apply { mkdirs() }
        val dest = File(dir, "runmate_update.apk")
        if (dest.exists()) dest.delete()

        val req = Request.Builder().url(url)
            .header("User-Agent", "DUT-RunMate-Updater")
            .build()
        dlClient.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("下载失败 HTTP ${r.code}")
            val body = r.body ?: throw RuntimeException("下载失败：空响应")
            val total = body.contentLength()
            val digest = if (sha256 != null) MessageDigest.getInstance("SHA-256") else null
            var read = 0L
            var lastCb = 0L
            body.byteStream().use { ins ->
                FileOutputStream(dest).use { fos ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        fos.write(buf, 0, n)
                        digest?.update(buf, 0, n)
                        read += n
                        if (read - lastCb > 128 * 1024 || (total > 0 && read == total)) {
                            lastCb = read
                            withContext(Dispatchers.Main) { onProgress(read, total) }
                        }
                    }
                }
            }
            if (total > 0 && read != total) {
                dest.delete(); throw RuntimeException("下载不完整（$read/$total）")
            }
            if (digest != null) {
                val got = digest.digest().joinToString("") { "%02x".format(it) }
                if (!got.equals(sha256, ignoreCase = true)) {
                    dest.delete(); throw RuntimeException("SHA256 校验失败，已删除下载文件")
                }
            }
            dest
        }
    }

    /**
     * 拉起安装。Android 8+ 需「安装未知应用」授权，未授权时返回 false（调用方引导去系统设置）。
     */
    fun install(ctx: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 &&
            !ctx.packageManager.canRequestPackageInstalls()) return false
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(i)
        return true
    }
}
