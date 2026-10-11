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
 * 应用内更新：双通道检查（GitHub Release 优先 → 发布中心服务器兜底）+ 下载 + 安装。
 *
 * v1.8.5：检查顺序反转。发布流程先出 GitHub Release，网站（独立部署快照）
 * 可能滞后数小时才同步；旧顺序（服务器优先）在网站未同步时会误判「已是最新」，
 * 漏掉 GitHub 上的新版。现改为 GitHub 先查，再查服务器，双通道结果合并：
 * 任一发现更新取版本更高者（同版本优先服务器——国内下载更稳且带 sha256）。
 *
 * v1.8.5 附带修复：GitHub 对仓库名大小写不敏感，但实测该仓库精确大小写形式
 * （DUT-RunMate）的名称路由（API/网页/资产下载）一度整体 404，小写形式始终
 * 正常——所有出站 GitHub URL 一律使用小写仓库名，并对资产下载 URL 里的
 * 仓库段做小写归一，规避同类风险。
 *
 * v1.5.0：服务器地址为内置常量（用户指定的发布中心），不再支持自定义。
 *
 * 服务器清单格式（配套发布中心 /api/latest）：
 * { ok, latest: { versionName, versionCode, apkUrl|apkPath, sha256, sizeBytes, notes, ... } }
 *
 * GitHub：GET /repos/{repo}/releases/latest，tag_name 形如 v1.1.0，
 * 通过语义化版本与当前 VERSION_NAME 比较；资产取第一个 .apk。
 */
object Updater {

    /** v1.8.5：仓库标识（出站 URL 用，一律小写，见对象头注释） */
    const val GITHUB_REPO = "43aquarius/dut-runmate"

    /** 仓库名（仅界面显示用，如设置页副标题、复制链接提示） */
    const val GITHUB_REPO_DISPLAY = "43aquarius/DUT-RunMate"

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
     * 检查更新（v1.8.5 顺序反转）：GitHub Release 优先 → 发布中心服务器其次。
     *
     * 为什么 GitHub 优先：发布源头在 GitHub，网站是后续同步的独立部署，
     * 存在「GitHub 已发新版、网站还没同步」的窗口期；旧顺序此时会误报最新。
     *
     * 合并规则：
     * - 任一通道发现更新 → 取 versionName 更高者；同版本优先服务器
     *   （国内下载更稳、清单带 sha256 校验）
     * - 双通道均无更新（任一返回 UpToDate 即可信）→ UpToDate
     * - 双通道均失败 → Failed（两通道错误合并展示，v1.6.1 起不掩盖主因）
     *
     * 两通道各重试 2 次（间隔 1.5s，抗瞬时网络抖动）；检查在后台协程进行，
     * 全部失败的最坏耗时也不影响启动体验。
     */
    suspend fun check(ctx: Context): CheckResult = withContext(Dispatchers.IO) {
        // 通道 1：GitHub Release（发布源头，版本最新）
        var ghResult: CheckResult? = null
        var ghErr = ""
        for (attempt in 1..2) {
            try {
                ghResult = checkGithub()
                break
            } catch (e: Exception) {
                ghErr = e.message ?: e.javaClass.simpleName
                if (attempt == 1) delay(1500)
            }
        }

        // 通道 2：发布中心服务器（下载更稳，带 sha256；GitHub 不可达时的兜底）
        var svResult: CheckResult? = null
        var svErr = ""
        for (attempt in 1..2) {
            try {
                svResult = checkServer(SERVER_BASE)
                break
            } catch (e: Exception) {
                svErr = e.message ?: e.javaClass.simpleName
                if (attempt == 1) delay(1500)
            }
        }

        val ghUpd = ghResult as? CheckResult.HasUpdate
        val svUpd = svResult as? CheckResult.HasUpdate
        when {
            ghUpd != null && svUpd != null ->
                // 双通道都有更新：版本不同取更高者；同版本优先服务器（下载更稳+带校验）
                if (semverCompare(svUpd.info.versionName, ghUpd.info.versionName) >= 0) svUpd else ghUpd
            ghUpd != null -> ghUpd
            svUpd != null -> svUpd
            ghResult is CheckResult.UpToDate || svResult is CheckResult.UpToDate -> CheckResult.UpToDate
            else -> CheckResult.Failed(
                "GitHub 通道（重试2次）: ${ghErr.ifBlank { "未知" }}；" +
                    "服务器通道（重试2次）: ${svErr.ifBlank { "未知" }}"
            )
        }
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

    /** GitHub Release 通道（发布源头，v1.8.5 起优先；国内可能不可达，失败自动落服务器通道） */
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
