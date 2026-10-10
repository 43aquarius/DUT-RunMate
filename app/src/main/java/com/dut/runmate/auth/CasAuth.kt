package com.dut.runmate.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.FormBody
import java.util.concurrent.TimeUnit

/**
 * 大连理工大学统一身份认证（CAS）登录 —— 复刻网页端 sso.dlut.edu.cn/cas/login 协议。
 *
 * v1.5.1 新增。逆向自 /cas/comm/js/login12.js + des.js，并在沙箱实测验证：
 *  1) GET /cas/login → 拿 JSESSIONIDCAS Cookie + 表单隐藏域 lt(LT-xxx) / execution(e1s1)
 *  2) POST <form action>（含 ;JSESSIONIDCAS= 路径参数）：
 *       lt, execution, _eventId=submit, ul=len(学号), pl=len(密码), sl=0,
 *       rsa = strEnc(学号+密码+lt, "1","2","3")   ← des.js 三重 DES（16bit/字符）
 *     （#un/#pd 输入框 disabled，凭据只藏在 rsa 字段里）
 *  3) 成功 → 302 + Set-Cookie: CASTGC=TGT-xxx（CAS 全局会话）
 *     失败 → 200 登录页，错误文本在 <span id="errormsghide">（如「用户名密码错误」）
 *
 * 为什么加这条通道：实测微哨网关 401 拒绝携带 app_version=3.3.12.75026 的 userLoginCas
 * （旧版 i大工 客户端疑似已被服务端下线），无 app_version 的请求虽可达但真实凭据
 * 校验不可靠（用户反馈正确学号密码仍报「学号或密码错误」）。而网页端 CAS 协议
 * 是学校现行标准（用户可正常网页登录），故以其为主通道；微哨通道保留为辅。
 *
 * CASTGC 用途：注入 H5 捕获页 WebView（sso.dlut.edu.cn / .dlut.edu.cn 域），
 * 健康长跑 H5 如走 CAS 鉴权即可免密通过；配合 CAS 页自动填表兜底。
 */
object CasAuth {

    private const val HOST = "sso.dlut.edu.cn"
    private const val LOGIN_PAGE = "https://$HOST/cas/login"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) Mobile Safari/537.36 weishao(3.3.12.75026)"

    /** 登录结果 */
    sealed class Result {
        /** castgc：CAS 全局会话 Cookie 值（TGT-xxx） */
        data class Ok(val castgc: String) : Result()
        data class Err(val msg: String) : Result()
    }

    private val cookieStore = mutableListOf<Cookie>()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
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

    /** 清空会话（登出/重登前调用，避免旧 CASTGC 干扰） */
    fun reset() {
        synchronized(cookieStore) { cookieStore.clear() }
    }

    /** 登录（IO 线程）。成功返回 CASTGC，失败返回网页端一致的具体错误文本。 */
    suspend fun login(number: String, password: String): Result = withContext(Dispatchers.IO) {
        try {
            if (number.isBlank() || password.isBlank())
                return@withContext Result.Err("请输入学号和密码")

            reset()

            // 1) 登录页：lt / execution / form action
            val pageBody = client.newCall(
                Request.Builder().url(LOGIN_PAGE).header("User-Agent", UA).build()
            ).execute().use { it.body?.string() ?: "" }

            val lt = Regex("""name="lt"\s+value="([^"]+)"""").find(pageBody)?.groupValues?.get(1)
            val execution =
                Regex("""name="execution"\s+value="([^"]+)"""").find(pageBody)?.groupValues?.get(1)
            val action =
                Regex("""<form id="loginForm" action="([^"]+)"""").find(pageBody)?.groupValues?.get(1)
            if (lt.isNullOrBlank() || execution.isNullOrBlank() || action.isNullOrBlank()) {
                return@withContext Result.Err("CAS 页面解析失败（学校服务可能升级中），请改用 H5 捕获页手动登录")
            }
            val postUrl = if (action.startsWith("http")) action else "https://$HOST$action"

            // 2) 提交（凭据经 des.js 三重 DES 藏进 rsa 字段）
            val form = FormBody.Builder()
                .add("lt", lt)
                .add("execution", execution)
                .add("_eventId", "submit")
                .add("ul", number.length.toString())
                .add("pl", password.length.toString())
                .add("sl", "0")
                .add("rsa", DesCore.strEnc("$number$password$lt", "1", "2", "3"))
                .build()

            client.newCall(
                Request.Builder().url(postUrl)
                    .header("User-Agent", UA)
                    .header("Referer", LOGIN_PAGE)
                    .post(form)
                    .build()
            ).execute().use { r ->
                val body = r.body?.string() ?: ""
                val castgc = synchronized(cookieStore) {
                    cookieStore.firstOrNull { it.name == "CASTGC" && it.value.isNotBlank() }?.value
                }
                if (!castgc.isNullOrBlank()) {
                    return@withContext Result.Ok(castgc)
                }
                // 失败：解析网页端同款错误文本
                val err = Regex(
                    """id="errormsghide"[^>]*>\s*([^<]*)"""
                ).find(body)?.groupValues?.get(1)?.trim()
                    ?.replace("<br/>", "")?.replace("<br>", "")
                Result.Err(err?.takeIf { it.isNotBlank() } ?: "用户名或密码错误（CAS 统一认证）")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Err("网络错误：${e.message ?: "无法连接学校认证服务"}")
        }
    }
}

/**
 * sso.dlut.edu.cn /cas/comm/js/des.js 的逐行 Kotlin 移植（DEScore by Guapo）。
 *
 * ⚠ 这不是标准 DES——自定义置换 + 「每字符占 16bit」的打包方式，与
 * pycryptodome/JCE 的 DES 输出均不同，只能逐行照抄。本移植已用
 * node 运行原始 des.js 的基准向量逐一校验（1234→C1BB5938DF9F2190 等）。
 */
object DesCore {

    private val S1 = arrayOf(
        intArrayOf(14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7),
        intArrayOf(0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8),
        intArrayOf(4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0),
        intArrayOf(15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13)
    )
    private val S2 = arrayOf(
        intArrayOf(15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10),
        intArrayOf(3, 13, 4, 7, 15, 2, 8, 14, 12, 0, 1, 10, 6, 9, 11, 5),
        intArrayOf(0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15),
        intArrayOf(13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9)
    )
    private val S3 = arrayOf(
        intArrayOf(10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8),
        intArrayOf(13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1),
        intArrayOf(13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7),
        intArrayOf(1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12)
    )
    private val S4 = arrayOf(
        intArrayOf(7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15),
        intArrayOf(13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9),
        intArrayOf(10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4),
        intArrayOf(3, 15, 0, 6, 10, 1, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14)
    )
    private val S5 = arrayOf(
        intArrayOf(2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9),
        intArrayOf(14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6),
        intArrayOf(4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14),
        intArrayOf(11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3)
    )
    private val S6 = arrayOf(
        intArrayOf(12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11),
        intArrayOf(10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8),
        intArrayOf(9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6),
        intArrayOf(4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13)
    )
    private val S7 = arrayOf(
        intArrayOf(4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1),
        intArrayOf(13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6),
        intArrayOf(1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2),
        intArrayOf(6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12)
    )
    private val S8 = arrayOf(
        intArrayOf(13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7),
        intArrayOf(1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2),
        intArrayOf(7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8),
        intArrayOf(2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11)
    )
    private val SBOX = arrayOf(S1, S2, S3, S4, S5, S6, S7, S8)

    private val P = intArrayOf(
        15, 6, 19, 20, 28, 11, 27, 16, 0, 14, 22, 25, 4, 17, 30, 9,
        1, 7, 23, 13, 31, 26, 2, 8, 18, 12, 29, 5, 21, 10, 3, 24
    )
    private val FP = intArrayOf(
        39, 7, 47, 15, 55, 23, 63, 31, 38, 6, 46, 14, 54, 22, 62, 30,
        37, 5, 45, 13, 53, 21, 61, 29, 36, 4, 44, 12, 52, 20, 60, 28,
        35, 3, 43, 11, 51, 19, 59, 27, 34, 2, 42, 10, 50, 18, 58, 26,
        33, 1, 41, 9, 49, 17, 57, 25, 32, 0, 40, 8, 48, 16, 56, 24
    )
    private val PC2 = intArrayOf(
        13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3,
        25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36, 46, 54, 29, 39,
        50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31
    )
    private val LOOP = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)

    /** JS strToBt：≤4 字符 → 64bit（每字符 16bit 大端，不足补 0x0000） */
    private fun strToBt(s: String): IntArray {
        val bt = IntArray(64)
        val len = minOf(s.length, 4)
        for (i in 0 until len) {
            val k = s[i].code
            for (j in 0 until 16) bt[16 * i + j] = (k shr (15 - j)) and 1
        }
        // 余下字符位保持 0（已初始化）
        return bt
    }

    /** JS getKeyBytes：密钥串按 ≤4 字符分块 */
    private fun getKeyBytes(key: String): List<IntArray> {
        val out = ArrayList<IntArray>()
        var i = 0
        while (i < key.length) {
            out.add(strToBt(key.substring(i, minOf(i + 4, key.length))))
            i += 4
        }
        return out
    }

    private fun initPermute(d: IntArray): IntArray {
        val ip = IntArray(64)
        var m = 1
        var n = 0
        for (i in 0 until 4) {
            var j = 7
            var k = 0
            while (j >= 0) {
                ip[i * 8 + k] = d[j * 8 + m]
                ip[i * 8 + k + 32] = d[j * 8 + n]
                j--; k++
            }
            m += 2; n += 2
        }
        return ip
    }

    private fun expandPermute(r: IntArray): IntArray {
        val ep = IntArray(48)
        for (i in 0 until 8) {
            ep[i * 6] = if (i == 0) r[31] else r[i * 4 - 1]
            ep[i * 6 + 1] = r[i * 4]
            ep[i * 6 + 2] = r[i * 4 + 1]
            ep[i * 6 + 3] = r[i * 4 + 2]
            ep[i * 6 + 4] = r[i * 4 + 3]
            ep[i * 6 + 5] = if (i == 7) r[0] else r[i * 4 + 4]
        }
        return ep
    }

    private fun xor(a: IntArray, b: IntArray): IntArray {
        val o = IntArray(a.size)
        for (i in a.indices) o[i] = a[i] xor b[i]
        return o
    }

    private fun sBoxPermute(e: IntArray): IntArray {
        val out = IntArray(32)
        for (m in 0 until 8) {
            val i = e[m * 6] * 2 + e[m * 6 + 5]
            val j = e[m * 6 + 1] * 8 + e[m * 6 + 2] * 4 + e[m * 6 + 3] * 2 + e[m * 6 + 4]
            val v = SBOX[m][i][j]
            for (b in 0 until 4) out[m * 4 + b] = (v shr (3 - b)) and 1
        }
        return out
    }

    private fun pPermute(s: IntArray): IntArray {
        val o = IntArray(32)
        for (i in 0 until 32) o[i] = s[P[i]]
        return o
    }

    private fun finallyPermute(e: IntArray): IntArray {
        val o = IntArray(64)
        for (i in 0 until 64) o[i] = e[FP[i]]
        return o
    }

    private fun generateKeys(keyByte: IntArray): Array<IntArray> {
        val key = IntArray(56)
        for (i in 0 until 7) {
            for (j in 0 until 8) {
                key[i * 8 + j] = keyByte[8 * (7 - j) + i]
            }
        }
        val keys = Array(16) { IntArray(48) }
        for (rnd in 0 until 16) {
            repeat(LOOP[rnd]) {
                val tl = key[0]
                val tr = key[28]
                for (k in 0 until 27) {
                    key[k] = key[k + 1]
                    key[28 + k] = key[29 + k]
                }
                key[27] = tl
                key[55] = tr
            }
            for (m in 0 until 48) keys[rnd][m] = key[PC2[m]]
        }
        return keys
    }

    private fun enc(data: IntArray, keyByte: IntArray): IntArray {
        val keys = generateKeys(keyByte)
        val ip = initPermute(data)
        var left = ip.copyOfRange(0, 32)
        var right = ip.copyOfRange(32, 64)
        for (rnd in 0 until 16) {
            val tempLeft = left.copyOf()
            left = right.copyOf()
            val f = pPermute(sBoxPermute(xor(expandPermute(right), keys[rnd])))
            val newRight = IntArray(32)
            for (i in 0 until 32) newRight[i] = f[i] xor tempLeft[i]
            right = newRight
        }
        val fin = right + left
        return finallyPermute(fin)
    }

    private fun bt64ToHex(bt: IntArray): String {
        val sb = StringBuilder(16)
        for (i in 0 until 16) {
            var v = 0
            for (j in 0 until 4) v = v * 2 + bt[i * 4 + j]
            sb.append("0123456789ABCDEF"[v])
        }
        return sb.toString()
    }

    /**
     * JS strEnc(data, firstKey, secondKey, thirdKey)：
     * 数据按 4 字符分块 ECB；每块依次用 firstKey 各块、secondKey 各块、thirdKey 各块 enc()。
     * 网页端固定调用 strEnc(u+p+lt, '1','2','3')。
     */
    fun strEnc(data: String, firstKey: String, secondKey: String, thirdKey: String): String {
        if (data.isEmpty()) return ""
        val fk = if (firstKey.isNotEmpty()) getKeyBytes(firstKey) else emptyList()
        val sk = if (secondKey.isNotEmpty()) getKeyBytes(secondKey) else emptyList()
        val tk = if (thirdKey.isNotEmpty()) getKeyBytes(thirdKey) else emptyList()
        val haveAll = firstKey.isNotEmpty() && secondKey.isNotEmpty() && thirdKey.isNotEmpty()
        val haveTwo = firstKey.isNotEmpty() && secondKey.isNotEmpty() && thirdKey.isEmpty()
        val haveOne = firstKey.isNotEmpty() && secondKey.isEmpty()

        fun process(bt: IntArray): IntArray {
            var t = bt
            if (haveAll) {
                for (k in fk) t = enc(t, k)
                for (k in sk) t = enc(t, k)
                for (k in tk) t = enc(t, k)
            } else if (haveTwo) {
                for (k in fk) t = enc(t, k)
                for (k in sk) t = enc(t, k)
            } else if (haveOne) {
                for (k in fk) t = enc(t, k)
            }
            return t
        }

        val sb = StringBuilder()
        var i = 0
        while (i < data.length) {
            val block = data.substring(i, minOf(i + 4, data.length))
            sb.append(bt64ToHex(process(strToBt(block))))
            i += 4
        }
        return sb.toString()
    }
}
