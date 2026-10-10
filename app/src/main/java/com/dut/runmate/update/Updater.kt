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
 * 应用内更新：双通道检查（发布中心服务器 → GitHub Release 兜底）+ 下载 + 安装。
 *
 * v1.5.0：服务器地址为内置常量（用户指定的发布中心），不再支持自定义；
 * 服务器不可达时自动改走 GitHub Release。
 *
 * 服务器清单格式（配套发布中心 /api/latest）：
 * { ok, latest: { versionName, versionCode, apkUrl|apkPath, sha256, sizeBytes, notes, ... } }
 *
 * GitHub：GET /repos/{repo}/releases/latest，tag_name 形如 v1.1.0，
 * 通过语义化版本与当前 VERSION_NAME 比较；资产取第一个 .apk。
 */
object Updater {

    const val GITHUB_REPO = "43aquarius/DUT-RunMate"

    /** v1.5.0：发布中心固定地址（用户指定），不再从设置读取 */
    const val SERVER_BASE = "https://dut-runmate.space-z.ai"

    private const val GITHUB_API = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    data class UpdateInfo(
        val versionName: String,
        val apkUrl: String,
        val sha256: String?,
        val sizeBytes: Long,
        val notes: String,
        val source: String          // "server" / "github"
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
     * 检查更新：发布中心服务器优先，GitHub 兜底。
     * v1.6.1：服务器通道共尝试 2 次（间隔 1.5s，抗瞬时网络抖动）；双通道都
     * 失败时把两个通道的错误合并展示（旧版只显示兜底通道错误，主因被掩盖）。
     */
    suspend fun check(ctx: Context): CheckResult = withContext(Dispatchers.IO) {
        var serverResult: CheckResult? = null
        var serverErr = ""
        for (attempt in 1..2) {
            try {
                serverResult = checkServer(SERVER_BASE)
                break
            } catch (e: Exception) {
                serverErr = e.message ?: e.javaClass.simpleName
                if (attempt == 1) delay(1500)
            }
        }
        if (serverResult is CheckResult.HasUpdate) return@withContext serverResult
        if (serverResult is CheckResult.UpToDate) return@withContext serverResult

        var ghErr = ""
        val ghResult = try {
            checkGithub()
        } catch (e: Exception) {
            ghErr = e.message ?: e.javaClass.simpleName
            null
        }
        if (ghResult is CheckResult.HasUpdate) return@withContext ghResult
        if (ghResult is CheckResult.UpToDate) return@withContext ghResult

        CheckResult.Failed(
            "服务器通道（重试2次）: ${serverErr.ifBlank { "未知" }}；" +
                "GitHub 通道: ${ghErr.ifBlank { "不可用" }}"
        )
    }

    /** 发布中心服务器通道：SERVER_BASE 自动补全 /api/latest */
    private fun checkServer(rawUrl: String): CheckResult {
        var url = rawUrl.trim().trimEnd('/')
        if (!url.contains("/api/latest") && !url.endsWith(".json")) url = "$url/api/latest"

        val req = Request.Builder().url(url)
            .header("User-Agent", "DUT-RunMate/${BuildConfig.VERSION_NAME}")
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
            val body = r.body?.string() ?: throw RuntimeException("空响应")
            val root = JSONObject(body)
            val latest = root.optJSONObject("latest") ?: root   // 兼容扁平结构
            val remoteCode = latest.getInt("versionCode")
            if (remoteCode <= BuildConfig.VERSION_CODE) return CheckResult.UpToDate

            // v1.6.1 修复「更新连接失败」：线上清单的 apkUrl 曾被平台内部转发域名
            // （*.fcapp.run，公网不可达）污染。一律改用 apkPath + 内置服务器域名
            // 重建下载地址，不再信任清单里的绝对 apkUrl。
            val path = latest.optString("apkPath", "")
            val apkUrl = if (path.isNotBlank()) {
                SERVER_BASE.trimEnd('/') + (if (path.startsWith("/")) path else "/$path")
            } else {
                val raw = latest.optString("apkUrl", "")
                if (raw.startsWith("http")) raw
                else throw RuntimeException("清单缺少 apkUrl/apkPath")
            }
            return CheckResult.HasUpdate(
                UpdateInfo(
                    versionName = latest.optString("versionName", "?"),
                    apkUrl = apkUrl,
                    sha256 = latest.optString("sha256").ifBlank { null },
                    sizeBytes = latest.optLong("sizeBytes", 0L),
                    notes = latest.optString("notes", ""),
                    source = "server"
                )
            )
        }
    }

    /** GitHub Release 通道（国内网络可能不可达，作为兜底） */
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
                    apkUrl = asset.getString("browser_download_url"),
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
