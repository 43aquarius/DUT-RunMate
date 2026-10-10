package com.dut.runmate.auth

import android.os.Build
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher

/**
 * 微哨（i大工）账号登录 —— 复刻 i大工 App 原生登录协议（逆向 + 实测验证 2026-10-09）。
 *
 * 协议（详见 docs/i大工-健康长跑_API接口详细文档.md §9，对照 jadx 反编译
 *  ViewOnClickListenerC1102i + C1093z2）：
 *  1) 先访问一次 whistlenew/index.php 建立 PHPSESSID 会话
 *  2) GET /whistlenew/index.php?m=user&a=userLoginCas
 *       &student_number=<学号> &password=<RSA-1024/ECB/PKCS1 加密后 Base64，URL 编码>
 *       &client_id=<任意客户端id> &device_type=android &verfiy_image_code= &identity=
 *       &equipment_type=phone &os_version=<系统版本> &phone_type=<机型> &school=dlut
 *  3) 响应 {ret, errcode, errmsg, data:{my_info:{user_id, skey, name, student_number, ...}}}
 *     errcode==0 即成功；94003=学号或密码错误；60061=需要图形验证码；60057=账号禁用…
 *
 * v1.5.0 修复「登录失败：a is empty」（errcode 8011002）：
 *  实测定位——网关会拒绝携带 app_version 参数的请求（"3.3.12"/"3.3.12.75026"
 *  均返回 a is empty，"1.0" 却正常）。旧版 WhistleAuth 多传了
 *  app_version/uid/platform/city_id，已全部移除，参数集对齐 i大工 原生登录
 *  （ViewOnClickListenerC1102i.m360a + C1093z2.m356a：equipment_type/os_version/phone_type）。
 *  PHPSESSID 会话保留（首次建连行为与官方 App 一致）。
 *
 * 密码只用于本次登录，不落盘。skey 即微哨会话凭据（whistlekey Cookie 值）。
 */
object WhistleAuth {

    private const val BASE = "https://service.m.dlut.edu.cn"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) Mobile Safari/537.36 weishao(3.3.12.75026)"

    /** i大工 3.3.12.75026 内置 RSA-1024 公钥（jadx: ViewOnClickListenerC1102i / b.a.a.b.f.i） */
    private const val PUB_KEY_B64 =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQC56WdUBaNlw4hSWqmccMHk/1u1L4KGch60" +
                "ewyB0TdLD80hNfuoP8ddXv+Ql5de5fmMrlHoX9+fSlCsO7J0/fcDl2GjpgpgBT1wdEPBis4" +
                "sGmAGzAJ+prIJxjXVmpJFl3ldzqzqA2LGGS/nASPZP/z9zhKDv1Z2BFj62RjNSgXIkQIDAQAB"

    data class Session(
        val userId: String,      // 微哨 user_id（32 位 hex）
        val skey: String,        // 微哨会话凭据（whistlekey Cookie）
        val name: String,        // 姓名
        val number: String       // 学号
    )

    sealed class Result {
        data class Ok(val s: Session) : Result()
        data class Err(val code: Int, val msg: String) : Result()
    }

    /** 进程内会话（登录成功后由调用方存入 Prefs 持久化） */
    @Volatile var last: Session? = null

    private val cookieStore = mutableListOf<Cookie>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                synchronized(cookieStore) {
                    cookies.forEach { c ->
                        cookieStore.removeAll { it.name == c.name && it.domain == c.domain }
                        cookieStore.add(c)
                    }
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                synchronized(cookieStore) { cookieStore.toList() }
        })
        .build()

    /** RSA/ECB/PKCS1 加密 → Base64（与 i大工 Cipher.getInstance("RSA/ECB/PKCS1Padding") 一致） */
    fun rsaEncrypt(plain: String): String = try {
        val spec = X509EncodedKeySpec(Base64.decode(PUB_KEY_B64, Base64.DEFAULT))
        val key: PublicKey = KeyFactory.getInstance("RSA").generatePublic(spec)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        Base64.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    } catch (e: Exception) {
        ""
    }

    /** 登录（IO 线程）。失败返回友好错误信息。 */
    suspend fun login(number: String, password: String): Result = withContext(Dispatchers.IO) {
        try {
            if (number.isBlank() || password.isBlank())
                return@withContext Result.Err(-1, "请输入学号和密码")

            // 1) 建立会话（拿 PHPSESSID，行为对齐官方 App 首次建连）
            client.newCall(
                Request.Builder()
                    .url("$BASE/whistlenew/index.php?m=confInfo&a=getDlutAddress&stage=")
                    .header("User-Agent", UA)
                    .build()
            ).execute().use { }

            // 2) 登录（参数集与 i大工 原生一致：ViewOnClickListenerC1102i + C1093z2）
            //    ⚠ 不可携带 app_version/uid/platform/city_id —— 实测网关对 app_version
            //    返回 8011002 "a is empty"（v1.4.0 登录失败根因）。
            val pwdEnc = rsaEncrypt(password)
            if (pwdEnc.isBlank()) return@withContext Result.Err(-1, "密码加密失败")
            val url = HttpUrl.Builder().scheme("https").host("service.m.dlut.edu.cn")
                .addPathSegment("whistlenew").addPathSegment("index.php")
                .addQueryParameter("m", "user")
                .addQueryParameter("a", "userLoginCas")
                .addQueryParameter("student_number", number.trim())
                .addQueryParameter("password", pwdEnc)
                .addQueryParameter("client_id", "runmate-" + number.takeLast(6))
                .addQueryParameter("device_type", "android")
                .addQueryParameter("verfiy_image_code", "")
                .addQueryParameter("identity", "")
                .addQueryParameter("equipment_type", "phone")
                .addQueryParameter("os_version", Build.VERSION.RELEASE ?: "")
                .addQueryParameter("phone_type", Build.MODEL ?: "")
                .addQueryParameter("school", "dlut")
                .build()

            val body = client.newCall(
                Request.Builder().url(url).header("User-Agent", UA).build()
            ).execute().use { it.body?.string() ?: "" }

            parseLogin(body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Err(-1, "网络错误：${e.message ?: "无法连接服务"}")
        }
    }

    fun parseLogin(body: String): Result {
        return try {
            val root = JSONObject(body)
            val errcode = root.optInt("errcode", -1)
            val errmsg = root.optString("errmsg", "")
            if (errcode != 0) {
                return Result.Err(errcode, friendly(errcode, errmsg))
            }
            val myInfo = root.optJSONObject("data")?.optJSONObject("my_info")
                ?: return Result.Err(errcode, "服务端未返回用户信息")
            val userId = myInfo.optString("user_id")
            val skey = myInfo.optString("skey")
            if (userId.isBlank()) return Result.Err(errcode, "服务端未返回 userId")
            Result.Ok(
                Session(
                    userId = userId,
                    skey = skey,
                    name = myInfo.optString("name").ifBlank { "同学" },
                    number = myInfo.optString("student_number").ifBlank { "" }
                )
            )
        } catch (e: Exception) {
            Result.Err(-1, "响应解析失败：${e.message}")
        }
    }

    /** 错误码 → 友好提示（对照 LoginBaseActivity 的处理 + 实测 94003 / 8011002） */
    private fun friendly(code: Int, raw: String): String = when (code) {
        94003, 60002, 60056 -> "学号或密码错误"
        60057 -> "账号已被禁用"
        60061 -> "需要图形验证码，请稍后再试或改用网页登录"
        60026 -> "设备参数不被接受"
        60080 -> "学校信息校验失败，请稍后重试"
        60092 -> "统一身份认证失败（密码可能已修改）"
        8011002 -> "网关拒绝请求参数（app_version 等），请升级 App 后重试"
        else -> (raw.ifBlank { "登录失败" }) + "（code $code）"
    }

    /** 供 H5 捕获页判断校内直连是否可达（快速 TCP 探测） */
    suspend fun probeDirect(): Boolean = withContext(Dispatchers.IO) {
        try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("202.118.65.138", 8081), 1500)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
