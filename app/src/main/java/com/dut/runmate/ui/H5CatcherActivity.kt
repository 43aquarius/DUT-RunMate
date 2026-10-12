package com.dut.runmate.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.dut.runmate.R
import com.dut.runmate.data.Prefs
import com.dut.runmate.data.api.ApiStore
import com.dut.runmate.databinding.ActivityH5CatcherBinding
import com.dut.runmate.net.Http
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * v1.4.0 · H5 捕获配置页（一键登录配置的第二步）。
 *
 * 背景：健康长跑 H5（202.118.65.138:8081/mobilenew/）自行向服务端换取
 * userId/amId/pmId/sign + Authorization 令牌，算法在 H5 JS 内部（含 sign 签名），
 * 原生无法复刻。本页用内嵌 WebView 完整复刻 i大工 的 H5 运行环境：
 *
 *   1. 注入会话 Cookie：whistlekey（微哨）+ CASTGC（v1.5.1 CAS 统一认证，对齐
 *      i大工 synCookies 同时注入 skey+TGT 的行为——H5 若走 CAS 鉴权即可免密通过）
 *   2. v1.8.0：默认一律走 WebVPN 隧道（webvpn.dlut.edu.cn 公网可达，校内校外
 *      通用，不依赖校园网——对齐 i大工 官方 App 校外行为）；「校内直连」降级为
 *      手动入口（仅校园网内可用）
 *   3. v1.5.1：sso.dlut.edu.cn/cas/login 出现时自动填表提交（页面自带 des.js 算 rsa）
 *   4. 每个页面注入 JS 嗅探器（monkey-patch XHR/fetch）
 *   5. 捕获到 findExtExercise 请求 → 自动写入「接口」页全部配置并开启 API 判定
 *
 * 相当于把「HttpCanary 抓包 + 手工誊抄」变成全自动。
 */
class H5CatcherActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NUMBER = "number"
        const val EXTRA_PASSWORD = "password"   // 仅内存传递用于自动填表，不落盘

        /** 仅校园网内可达（教育网 202.118.0.0/16）；v1.8.0 起降级为手动入口 */
        const val H5_DIRECT = "http://202.118.65.138:8081/mobilenew/"

        /**
         * v1.6.0 · WebVPN 隧道直达 H5 的 URL（实测验证）：
         * wengine（网瑞）门户的路由由查询参数 vpn-12-o1-<host:port> 决定，
         * 前缀段不参与路由（未登录时固定 302 → /login，登录后直达目标）。
         * 实抓包的真实请求形如
         *   /http-8081/<用户加密前缀>/service/mobile/extExercise/findExtExercise?vpn-12-o1-202.118.65.138:8081
         * （见 docs API 文档 §3.4），H5 内部请求由门户改写自动携带正确前缀。
         *
         * ⚠ v1.8.1 勘误：上面的「前缀段不参与校验」只在【未登录】阶段成立。
         * 登录会话建立后前缀段与登录用户绑定——占位段 "0" 会被 wengine 当作
         * 目标 host 解析，代理 http://0/mobilenew/ → 不可达（用户实报错误页
         * 「对不起，无法访问此网站 http://0/mobilenew/」）。已登录场景必须使用
         * 用户真实前缀（见 tunnelH5Url() / Prefs.tunnelPrefix / adoptTunnelPrefix）。
         */
        const val H5_TUNNEL =
            "https://webvpn.dlut.edu.cn/http-8081/0/mobilenew/?vpn-12-o1-202.118.65.138:8081"

        /** v1.8.1：WebVPN 门户登录页（未登录显示认证入口；已登录 302 门户首页） */
        const val WEBVPN_LOGIN = "https://webvpn.dlut.edu.cn/login"

        /** v1.8.1：带用户真实前缀的隧道 H5 直达 URL；v1.8.3 起无前缀时用本地计算的
         * 确定性前缀（AES-CFB-128 固定密钥，与官方 App 抓包一致）——彻底消除 0 占位
         * 版（已登录会话下必被网关当 host 解析为 http://0/ 不可达错误页） */
        fun tunnelH5Url(prefix: String): String {
            val p = if (prefix.isNotBlank() && prefix != "0") prefix
            else com.dut.runmate.auth.SportAuth.tunnelPrefix("202.118.65.138")
            return "https://webvpn.dlut.edu.cn/http-8081/$p/mobilenew/?vpn-12-o1-202.118.65.138:8081"
        }

        /**
         * v1.6.2 · 嗅探器升级：响应完成后再上报（附 HTTP 状态码），
         * 只有 200 的 findExtExercise 才会被采纳——避免抓到过期令牌的 401 请求后
         * 把 captured 标记一锤定音，后续有效请求反而不入库。
         */
        private const val SNIFFER_JS = """
(function(){
  if (window.__rmSniff) return; window.__rmSniff = 1;
  function send(o){ try { o.ts = Date.now(); RunMate.onCapture(JSON.stringify(o)); } catch(e){} }
  var oo = XMLHttpRequest.prototype.open;
  var osr = XMLHttpRequest.prototype.setRequestHeader;
  var os = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function(m,u){
    try { this.__rm = {method:String(m), url:String(u), headers:{}}; } catch(e){}
    return oo.apply(this, arguments);
  };
  XMLHttpRequest.prototype.setRequestHeader = function(k,v){
    try { if (this.__rm) this.__rm.headers[k] = v; } catch(e){}
    return osr.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function(b){
    try {
      if (this.__rm) {
        var r = this.__rm;
        var x = this;
        r.body = (b && typeof b === 'string') ? b : '';
        x.addEventListener('load', function(){
          try { r.status = x.status; send(r); } catch(e){}
        });
      }
    } catch(e){}
    return os.apply(this, arguments);
  };
  if (window.fetch) {
    var of = window.fetch;
    window.fetch = function(input, init){
      var meta = null;
      try {
        var u = (typeof input === 'string') ? input : (input && input.url);
        var h = {};
        if (init && init.headers) {
          if (init.headers.forEach) { init.headers.forEach(function(v,k){ h[k]=v; }); }
          else { h = init.headers; }
        }
        meta = {method:(init&&init.method)||'GET', url:String(u), headers:h,
                body:(init && typeof init.body==='string') ? init.body : ''};
      } catch(e){}
      var p = of.apply(this, arguments);
      if (meta) {
        p.then(function(resp){ try { meta.status = resp.status; send(meta); } catch(e){} },
               function(){});
      }
      return p;
    };
  }
})();
"""
    }

    private lateinit var b: ActivityH5CatcherBinding
    private val prefs by lazy { Prefs.get(this) }
    private val store by lazy { ApiStore.get(filesDir) }

    private var number = ""
    private var password = ""
    private var captured = false
    private var webvpnTriedFill = false
    private var casTriedFill = false

    /**
     * v1.6.2：门户资源列表里拿到的用户真实隧道前缀（H5_TUNNEL 常量用的 "0" 是占位段）。
     * v1.8.1：随 Prefs 持久化（onCreate 恢复）——已登录场景的隧道直达必须用它。
     */
    private var tunnelPrefix = ""

    /** v1.8.1：0 占位前缀被网关拒绝后的自动重试次数（防 /login ↔ 隧道死循环） */
    private var prefixFallbackCount = 0

    /** v1.6.2：拦截主文档用的独立 HTTP 客户端（不跟随重定向，3xx 原样交回 WebView） */
    private val fetchClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    // v1.6.0 WebVPN 自动流程状态：
    //   0 = 未走隧道；1 = 已加载隧道 H5（未登录被重定向到 /login）；
    //   2 = 已自动点击「统一身份认证登录」；3 = 登录完成（回到门户）已自动重进 H5
    private var webvpnStage = 0
    private var portalScanned = false

    /**
     * v1.8.6：/login 弹回自动重试计数。
     * 学校开启短信二次认证后，「H5 弹回 /login → 自动重点 cas-login」的循环只会反复轮换
     * JSESSIONIDCAS 会话，把用户正在输入的图形验证码/短信会话全部作废（用户实报：
     * 「验证码过期或无效」「第二次发送 get_session_error」「页面一直在自动刷新」）。
     * 满 2 轮后停止自动重试，等用户手动登录；手动登录成功落回门户时仍会自动进 H5。
     */
    private var webvpnRetryCount = 0

    /** v1.8.6：自动重试已停止的提示只记一次 */
    private var webvpnHaltNotified = false

    /**
     * v1.8.6：人工验证模式——页面出现短信二次认证/双重认证/图形验证码 UI 时置位，
     * 冻结自动导航（不再自动点 cas-login）；落到非认证页时同步复位。
     */
    private var manualAuthMode = false

    /** v1.8.6：人工验证引导只提示一次 */
    private var manualAuthHinted = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityH5CatcherBinding.inflate(layoutInflater)
        setContentView(b.root)

        number = intent.getStringExtra(EXTRA_NUMBER) ?: prefs.wsNumber
        password = intent.getStringExtra(EXTRA_PASSWORD) ?: ""
        // v1.8.1：恢复持久化的用户真实隧道前缀（上次门户扫描 / H5 捕获时记录）
        if (tunnelPrefix.isBlank()) tunnelPrefix = prefs.tunnelPrefix
        // v1.8.3：前缀是确定性值（固定密钥加密目标 host，见 SportAuth）——
        // 没有存档时直接本地计算，不再依赖门户扫描提取，从根上消除 0 占位不可达
        if (tunnelPrefix.isBlank() || tunnelPrefix == "0") {
            tunnelPrefix = com.dut.runmate.auth.SportAuth.tunnelPrefix("202.118.65.138")
            prefs.tunnelPrefix = tunnelPrefix
            Http.tunnelPrefixHint = tunnelPrefix
        }

        // v1.5.1：不再强制要求 whistlekey——CAS 会话（CASTGC）同样可以完成
        // H5 鉴权（对齐官方 synCookies 注入 skey+TGT 的行为），两者至少有其一即可。
        // v1.8.6：学校开启短信二次认证后，原生登录（CAS/微哨）拿不到会话，本页的
        // 「自动填表 + 手动短信验证」成为唯一登录路径——因此只要学号+密码齐备，
        // 无已保存会话也允许进入（跳过 Cookie 注入，页面内照样自动填表）。
        val hasCredential = prefs.wsSkey.isNotBlank() || prefs.casTgt.isNotBlank()
        val hasFormCredential = number.isNotBlank() && password.isNotBlank()
        if (!hasCredential && !hasFormCredential) {
            // v1.8.11：Toast 改需手动确认对话框；用户点「确定」后才 finish()
            //（旧版 Toast 一闪而过 + 立即退出，用户根本来不及看原因）
            try {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.notice_title)
                    .setMessage(R.string.h5_no_session)
                    .setCancelable(false)
                    .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                    .show()
            } catch (_: Throwable) {
                finish()
            }
            return
        }

        // WebView 基础设置：复刻 i大工 InnerBrowser（JS + DOM + Cookie + 混合内容）
        b.web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            userAgentString = userAgentString.replace("; wv", "") + " weishao(3.3.12.75026)"
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        b.web.webChromeClient = WebChromeClient()
        b.web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                // 站内跳转一律在 WebView 内打开（隧道会话/票据不能丢）
                val u = req.url.toString()
                return !(u.startsWith("http://") || u.startsWith("https://"))
            }

            /**
             * v1.6.2 · 核心修复：拦截 H5 主文档并预注入嗅探器（document-start）。
             *
             * 旧版只在 onPageStarted/onPageFinished 注入——onPageStarted 时 evaluateJavascript
             * 落在【旧】文档里（导航后即丢失），onPageFinished 时 H5 的 findExtExercise
             * 早已发出（实测抓包：H5 加载即查询），两次注入全都错过请求 →
             * 「登录成功但令牌不自动填充」的根因。
             * 现改为：主框架 HTML 响应在返回 WebView 前由 OkHttp 取回，把嗅探器
             * <script> 插到 <head> 最前面——任何页面脚本运行前 XHR/fetch 已被接管。
             *
             * v1.8.3 新增两类拦截（校外 H5 捕获修复，SportAuth 原生通道的兜底路径）：
             *  A) 主框架原始内网地址（H5 的 mobileCheckJump 落地页 location.href 指向
             *     http://202.118.65.138:8081/…，校外不可达）→ 302 到隧道等价 URL；
             *  B) 隧道内的 JS/JSON 资源 → 内容改写：响应体里的原始内网地址替换为隧道
             *     地址。健康长跑 H5 的 axios 用 mobile-server-config.js 里写死的内网
             *     BASE_URL 发请求，wengine 网关不改写 JS 内容——校外时这些 XHR 全部
             *     超时，令牌交换永远失败，即用户实报「已登录却无法获取 token /
             *     账号未捕获」的根因（官方 App 的 WebView 自带此改写所以校外能用）。
             */
            override fun shouldInterceptRequest(
                v: WebView, req: WebResourceRequest
            ): WebResourceResponse? {
                val u = req.url.toString()
                if (req.isForMainFrame) {
                    if (u.contains("mobilenew", ignoreCase = true)) {
                        return try {
                            interceptH5Document(v, u, req.requestHeaders)
                        } catch (_: Exception) {
                            null      // 拦截失败 → 交回 WebView 默认加载（回到旧行为）
                        }
                    }
                    // v1.8.3 A：主框架内网直达 → 302 到隧道等价地址
                    if (u.startsWith(com.dut.runmate.auth.SportAuth.SPORT_BASE)) {
                        return try {
                            redirectInternalToTunnel(u)
                        } catch (_: Exception) {
                            null
                        }
                    }
                    return null
                }
                // v1.8.3 B：隧道 JS/JSON 资源内容改写（体内含内网地址才有必要动）
                val bare = u.substringBefore('?')
                if (u.contains("/http-8081/") && (bare.endsWith(".js") || bare.endsWith(".json"))) {
                    return try {
                        interceptAndRewrite(v, u, req.requestHeaders)
                    } catch (_: Exception) {
                        null
                    }
                }
                return null
            }

            override fun onPageStarted(v: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(v, url, favicon)
                injectSniffer()
            }

            override fun onPageCommitVisible(v: WebView, url: String) {
                super.onPageCommitVisible(v, url)
                injectSniffer()   // v1.6.2：比 onPageFinished 更早的兜底注入时机
            }

            override fun onPageFinished(v: WebView, url: String) {
                super.onPageFinished(v, url)
                injectSniffer()
                // v1.8.6：先同步维护人工验证模式（非认证页立即复位，
                // 保证用户完成验证后的落地页能正常走自动进 H5），再做各自动流程
                detectManualAuthPage(url)
                maybeAutoFillCas(url)
                maybeAutoFillWebvpn(url)
                maybeWebvpnPostLogin(v, url)
                syncCookies()
            }

            /**
             * v1.8.1 自愈：0 占位前缀在 wengine 已登录会话下被网关拒绝——门户把 0
             * 当作目标 host 解析并重定向到 http://0/mobilenew/ → 不可达（即用户
             * 实报的错误页）。检测到该失败时自动转回门户 /login 重新走登录流程
             * 拿真实前缀，最多重试 2 次防循环。
             * v1.8.6：manualAuthMode（用户正在页面上做短信/图形验证）时不再自动跳转，
             * 避免把用户正在填的验证页冲掉。
             */
            override fun onReceivedError(
                v: WebView, req: WebResourceRequest, err: android.webkit.WebResourceError
            ) {
                super.onReceivedError(v, req, err)
                if (!req.isForMainFrame) return
                val u = req.url.toString()
                val badPrefix = u.startsWith("http://0/") || u.contains("/http-8081/0/")
                if (badPrefix && prefixFallbackCount < 2 && !manualAuthMode) {
                    prefixFallbackCount++
                    log(getString(R.string.h5_log_prefix_rejected))
                    webvpnStage = 1
                    portalScanned = false
                    // v1.8.2：同时复位两个一次性标记（旧版只重置 stage/portalScanned——
                    // 转回 /login 后若会话未建立，maybeAutoFillWebvpn 因 triedFill=true
                    // 且 stage<3 直接 return，不会自动点「统一身份认证登录」，用户卡在登录页）
                    webvpnTriedFill = false
                    casTriedFill = false
                    load(WEBVPN_LOGIN)
                }
            }
        }
        // JS 桥（嗅探器回传捕获的请求）
        b.web.addJavascriptInterface(SniffBridge(), "RunMate")

        b.btnH5Direct.setOnClickListener {
            // v1.8.0：校内直连降级为手动入口（仅校园网内可达；隧道才是默认）
            setStatus(getString(R.string.h5_status_direct))
            load(H5_DIRECT)
        }
        b.btnWebvpn.setOnClickListener {
            // v1.8.1：有真实前缀直达隧道 H5；无前缀走门户 /login。
            // （v1.8.0 直接载 0 占位版 H5_TUNNEL——已登录会话下占位段被网关当
            //   host 解析 → http://0/mobilenew/ 不可达，即用户实报的错误页，本次修复）
            setStatus(getString(R.string.h5_status_webvpn))
            enterTunnel()
        }
        b.btnSniffAgain.setOnClickListener { injectSniffer(); toast(R.string.h5_sniff_reinjected) }
        // v1.6.1：入口用途说明（健康长跑直连 / WebVPN / 重新注入嗅探都是干什么的）
        b.btnHelp.setOnClickListener { HelpDialog.show(this) }
        b.btnGo.setOnClickListener {
            val u = b.etUrl.text.toString().trim()
            if (u.startsWith("http")) load(u)
        }
        b.btnClose.setOnClickListener { finish() }

        log(getString(R.string.h5_log_start))
        start()
    }

    /**
     * 入口：注入会话 Cookie → v1.8.0 起默认一律走 WebVPN 隧道（校内外通用）。
     *
     * 不再探测校内直连：202.118.65.138:8081 属教育网内网地址，校园网外不可达
     * （用户实测连接超时）；而 webvpn.dlut.edu.cn 为公网域名，校内校外都能访问，
     * 隧道才是唯一「到哪里都能用」的路径——i大工 官方 App 校外也正是这么做的。
     * 需要校内直连（更快）时，可手动点「校内直连」入口。
     */
    private fun start() {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        // i大工 synCookies 行为：whistlekey 注入 .dlut.edu.cn（覆盖 webvpn 与 service 子域）
        if (prefs.wsSkey.isNotBlank()) {
            cm.setCookie(".dlut.edu.cn", "whistlekey=${prefs.wsSkey}")
            cm.setCookie("202.118.65.138", "whistlekey=${prefs.wsSkey}")
        }
        // v1.5.1：CASTGC 注入 sso.dlut.edu.cn（CAS 全局会话；官方 synCookies 同样注入 TGT）
        if (prefs.casTgt.isNotBlank()) {
            cm.setCookie("https://sso.dlut.edu.cn", "CASTGC=${prefs.casTgt}; Path=/; Secure")
            // .dlut.edu.cn 域的 H5 若读 CAS 会话也能拿到
            cm.setCookie(".dlut.edu.cn", "CASTGC=${prefs.casTgt}")
        }
        cm.flush()
        log(when {
            prefs.wsSkey.isNotBlank() -> getString(R.string.h5_log_cookie)
            prefs.casTgt.isNotBlank() -> getString(R.string.h5_log_cookie_cas)
            // v1.8.6：无已保存会话（首次登录/二次认证场景）——页面内自动填表登录
            else -> getString(R.string.h5_log_no_cookie)
        })

        // v1.8.1：默认 WebVPN 隧道。已记录真实前缀 → 直达 H5（会话过期则 302 /login
        // 自动重登）；无前缀 → 门户 /login（未登录自动走统一认证；已登录 302 门户首页）。
        // 两种落地均由 maybeWebvpnPostLogin 扫描资源列表接管，不再用 0 占位直达。
        setStatus(getString(R.string.h5_status_webvpn))
        log(getString(R.string.h5_log_tunnel_default))
        webvpnStage = 1
        enterTunnel()
    }

    /**
     * v1.8.1：进入隧道的统一入口（start() 与「WebVPN 隧道」按钮共用）。
     * 有用户真实前缀 → 前缀版直达；无前缀 → 门户 /login（v1.8.0 起 0 占位版
     * 直达被移除：已登录会话下占位段被 wengine 当 host 解析 → 不可达错误页）。
     */
    private fun enterTunnel() {
        load(tunnelH5Url(tunnelPrefix))
    }

    private fun load(url: String) {
        b.etUrl.setText(url)
        b.web.loadUrl(url)
    }

    private fun injectSniffer() {
        b.web.evaluateJavascript(SNIFFER_JS, null)
    }

    /** v1.6.2：从 Content-Type 提取字符集（默认 UTF-8），非法名回退 */
    private fun charsetOf(contentType: String): String {
        val m = Regex("charset=([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE).find(contentType)
        val name = m?.groupValues?.get(1) ?: "UTF-8"
        return try { Charset.forName(name).name() } catch (_: Exception) { "UTF-8" }
    }

    /** v1.6.2：把嗅探器 <script> 插到 <head> 之后（无 head 则 <html> 后/文首） */
    private fun injectIntoHtml(html: String): String {
        val tag = "<script>" + SNIFFER_JS + "</script>"
        Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)?.let {
            return html.substring(0, it.range.last + 1) + tag + html.substring(it.range.last + 1)
        }
        Regex("<html[^>]*>", RegexOption.IGNORE_CASE).find(html)?.let {
            return html.substring(0, it.range.last + 1) + tag + html.substring(it.range.last + 1)
        }
        return tag + html
    }

    /**
     * v1.6.2：取回 H5 主文档并预注入嗅探器（在 WebViewClient.shouldInterceptRequest
     * 的后台线程里执行，同步返回改造后的响应）。
     * - Cookie（whistlekey / wengine_vpn_ticket 等）从 CookieManager 原样转发
     * - 3xx/非 200：状态码+响应头+字节流原样交回 WebView 自行处理
     * - 200 HTML：嗅探器 <script> 插入 <head> 顶部后返回
     * - 任何异常由调用方兜底返回 null → WebView 默认加载
     */
    private fun interceptH5Document(
        v: WebView, url: String, reqHeaders: Map<String, String>
    ): WebResourceResponse {
        val cookie = try { CookieManager.getInstance().getCookie(url) } catch (_: Exception) { null }
        val rb = Request.Builder().url(url)
            .header("User-Agent", v.settings.userAgentString)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
        reqHeaders["Referer"]?.takeIf { it.isNotBlank() }?.let { rb.header("Referer", it) }
        if (!cookie.isNullOrBlank()) rb.header("Cookie", cookie)

        fetchClient.newCall(rb.build()).execute().use { r ->
            val ct = r.header("Content-Type") ?: ""
            // 转发原始响应头（剔除随体长度/编码变化的项与 CSP，避免拦截注入的内联脚本被拦）
            val fwd = linkedMapOf<String, String>()
            val skip = setOf("content-encoding", "content-length", "content-type",
                "content-security-policy", "content-security-policy-report-only")
            r.headers.forEach { (k, vv) ->
                if (k.lowercase() !in skip)
                    fwd[k] = if (fwd.containsKey(k)) fwd[k] + ", " + vv else vv
            }
            val bytes = r.body?.bytes() ?: ByteArray(0)
            val reason = r.message.ifBlank { "HTTP ${r.code}" }
            if (r.code != 200 || !ct.contains("html", ignoreCase = true)) {
                // 非成功/非 HTML：字节流透传，跳转由 WebView 自行跟随（会再次进入拦截器）
                return WebResourceResponse(
                    ct.substringBefore(';').trim().ifBlank { "text/html" },
                    charsetOf(ct), r.code, reason, fwd, java.io.ByteArrayInputStream(bytes)
                )
            }
            val enc = charsetOf(ct)
            val patched = injectIntoHtml(String(bytes, Charset.forName(enc)))
            runOnUiThread {
                log(getString(R.string.h5_log_intercept, patched.length))
            }
            return WebResourceResponse(
                "text/html", enc, 200, "OK", fwd,
                java.io.ByteArrayInputStream(patched.toByteArray(Charset.forName(enc)))
            )
        }
    }

    private fun syncCookies() {
        try {
            CookieManager.getInstance().flush()
        } catch (_: Exception) {
        }
    }

    /** v1.8.3 A：主框架原始内网 URL → 302 到隧道等价地址（校外不可达的内网导航自愈） */
    private fun redirectInternalToTunnel(internalUrl: String): WebResourceResponse {
        val pref = tunnelPrefix.ifBlank {
            com.dut.runmate.auth.SportAuth.tunnelPrefix("202.118.65.138")
        }
        val tunneled = com.dut.runmate.auth.SportAuth.tunnelUrl(internalUrl, pref)
        return WebResourceResponse(
            "text/plain", "utf-8", 302, "Found",
            mapOf("Location" to tunneled),
            java.io.ByteArrayInputStream(ByteArray(0))
        )
    }

    /**
     * v1.8.3 B：取回隧道 JS/JSON 资源，体内含原始内网地址时替换为隧道地址后返回。
     * 不含内网地址的资源返回 null 交回 WebView 默认加载（避免无谓的双取）。
     */
    private fun interceptAndRewrite(
        v: WebView, url: String, reqHeaders: Map<String, String>
    ): WebResourceResponse? {
        val cookie = try { CookieManager.getInstance().getCookie(url) } catch (_: Exception) { null }
        val rb = Request.Builder().url(url)
            .header("User-Agent", v.settings.userAgentString)
            .header("Accept", "*/*")
        reqHeaders["Referer"]?.takeIf { it.isNotBlank() }?.let { rb.header("Referer", it) }
        if (!cookie.isNullOrBlank()) rb.header("Cookie", cookie)
        fetchClient.newCall(rb.build()).execute().use { r ->
            val ct = r.header("Content-Type") ?: "application/javascript"
            val bytes = r.body?.bytes() ?: return null
            val text = try { String(bytes, Charsets.UTF_8) } catch (_: Exception) { return null }
            if (!text.contains(com.dut.runmate.auth.SportAuth.SPORT_BASE)) return null
            val pref = tunnelPrefix.ifBlank {
                com.dut.runmate.auth.SportAuth.tunnelPrefix("202.118.65.138")
            }
            val tunneledBase = "https://webvpn.dlut.edu.cn/http-8081/$pref"
            val patched = text.replace(com.dut.runmate.auth.SportAuth.SPORT_BASE, tunneledBase)
            runOnUiThread {
                log(getString(R.string.h5_log_rewrite, url.substringAfterLast('/').take(40)))
            }
            return WebResourceResponse(
                ct.substringBefore(';').trim().ifBlank { "application/javascript" },
                "utf-8", 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"),
                java.io.ByteArrayInputStream(patched.toByteArray(Charsets.UTF_8))
            )
        }
    }

    /**
     * v1.5.1：CAS 统一认证页自动填表（sso.dlut.edu.cn/cas/login）。
     * 页面自带 des.js 与 login()——填 #un/#pd 后直接调 login()，rsa 加密由页面完成，
     * 与真人网页登录行为完全一致；如出现验证码/扫码等场景由用户手动完成。
     *
     * v1.6.0：同时匹配 WebVPN 隧道内的 CAS 页（webvpn.dlut.edu.cn/https/<前缀>/cas/login）
     * ——部分跳转链会落在隧道版页面而非真实 sso 域。
     */
    private fun maybeAutoFillCas(url: String) {
        val isRealCas = url.contains("sso.dlut.edu.cn") && url.contains("/cas")
        val isTunnelCas = url.contains("webvpn.dlut.edu.cn") &&
                url.substringAfter("webvpn.dlut.edu.cn").contains("/cas/login")
        if (casTriedFill || (!isRealCas && !isTunnelCas)) return
        if (number.isBlank() || password.isBlank()) return
        casTriedFill = true
        log(getString(R.string.h5_log_cas_page))
        val js = """
            (function(){
              try {
                var u = document.querySelector('#un');
                if (!u) return 'no_input';
                u.value = '${number.replace("'", "\\'")}';
                var p = document.querySelector('#pd');
                if (!p) return 'no_pwd';
                p.value = '${password.replace("'", "\\'")}';
                if (typeof login === 'function') { login(); return 'submitted'; }
                var btn = document.querySelector('#loginSubmitBtn');
                if (btn) { btn.click(); return 'clicked'; }
                document.querySelector('#loginForm').submit();
                return 'form_submit';
              } catch(e) { return 'err:' + e.message; }
            })()
        """.trimIndent()
        b.web.evaluateJavascript(js) { r ->
            log(getString(R.string.h5_log_cas_fill, r))
        }
    }

    /**
     * v1.6.0：WebVPN 登录页自动流程。
     * 实测（2026-10）webvpn.dlut.edu.cn/login 已无本地账号密码表单（only-auth 模式），
     * 页面仅提供「统一身份认证登录」链接（#cas-login → /login?cas_login=true → 隧道 CAS
     * → 302 到真实 sso.dlut.edu.cn/cas/login?loginFrom=webVPN&webvpn_token=…）。
     * v1.5.1 代码找 #user_name/#login 均不存在 → 静默失败，用户无法登录（本次修复根因）。
     */
    private fun maybeAutoFillWebvpn(url: String) {
        if (webvpnTriedFill) {
            // v1.6.0 自愈：会话未完全建立又回到纯登录页（H5 隧道被弹回）→ 重置自动流程再来一轮
            val plainLogin = url.contains("webvpn.dlut.edu.cn") &&
                    url.substringAfter("webvpn.dlut.edu.cn").startsWith("/login") &&
                    !url.contains("cas_login=true")
            if (plainLogin && webvpnStage >= 3) {
                // v1.8.6：重试上限——两轮自动重登仍被弹回说明会话建立不了（典型：学校
                // 短信二次认证未完成）。继续循环只会反复刷新页面、轮换会话，把用户手动
                // 输入到一半的验证码全部作废（用户实报「一直在自动刷新」的根因之二）。
                // 停止重试，等用户手动登录；登录成功落回门户时仍会自动进 H5（见 postLogin）。
                if (webvpnRetryCount >= 2) {
                    if (!webvpnHaltNotified) {
                        webvpnHaltNotified = true
                        setStatus(getString(R.string.h5_status_manual_login))
                        log(getString(R.string.h5_log_auto_halt))
                    }
                    return
                }
                webvpnRetryCount++
                webvpnTriedFill = false
                casTriedFill = false
                webvpnStage = 1
                portalScanned = false
                log(getString(R.string.h5_log_webvpn_retry))
            } else {
                return
            }
        }
        // v1.8.6：人工验证模式（用户正在页面上做短信/图形验证）不再自动点击登录入口
        if (manualAuthMode) return
        val isWebvpnLogin = url.contains("webvpn.dlut.edu.cn") &&
                url.substringAfter("webvpn.dlut.edu.cn").startsWith("/login")
        if (!isWebvpnLogin) return
        // cas_login=true 是跳转链中转页（非登录表单页），等它落回真正的 /login 再操作
        if (url.contains("cas_login=true")) return
        webvpnTriedFill = true
        if (webvpnStage >= 2) return      // 已走过认证，防重入
        // 点击「统一身份认证登录」链接
        val js = """
            (function(){
              try {
                var a = document.querySelector('#cas-login');
                if (a) { a.click(); return 'cas_clicked'; }
                // 兼容旧版本地表单（若学校未来恢复）
                var u = document.querySelector('#user_name');
                if (!u) return 'no_login_entry';
                u.value = '${number.replace("'", "\\'")}';
                var form = document.querySelector('form#form');
                if (!form) return 'no_form';
                var pws = form.querySelectorAll('input[type=password]');
                if (pws.length === 0) return 'no_pwd';
                pws[0].value = '${password.replace("'", "\\'")}';
                var btn = document.querySelector('#login');
                if (btn) btn.click(); else form.submit();
                return 'submitted';
              } catch(e) { return 'err:' + e.message; }
            })()
        """.trimIndent()
        b.web.evaluateJavascript(js) { r ->
            if (r != null && r.contains("cas_clicked")) {
                webvpnStage = 2
                log(getString(R.string.h5_log_webvpn_cas_click))
            } else if (r != null && r.contains("submitted")) {
                webvpnStage = 2
                log(getString(R.string.h5_log_webvpn_fill, r))
            } else {
                log(getString(R.string.h5_log_webvpn_fill, r))
            }
        }
    }

    /**
     * v1.6.0：WebVPN 登录完成后的自动导航。
     * 统一认证回跳后（门户首页 / 任意已登录页），自动重新加载隧道 H5；
     * 首选在门户资源列表里找「健康长跑」链接（携带用户专属隧道前缀，最可靠）。
     *
     * v1.8.1：①触发条件放宽为 stage>=1——从 /login 进入且已登录时会直接 302
     * 门户首页，旧条件（stage>=2）会把这种落地当成「没走过认证」而跳过扫描，
     * 卡在门户页进不了 H5；②找不到长跑链接时从任意隧道链接提取用户前缀，
     * 用真实前缀直达（不再盲目载 0 占位版——已登录会话下必失败）。
     *
     * v1.8.6 · 自动刷新死循环根治（用户实报「点进去后一直在自动刷新」）：
     * 旧版把【隧道 CAS 认证页】（webvpn.dlut.edu.cn/https/<前缀>/cas/login，由
     * /login?cas_login=true 302 而来）当成「已登录落地页”——它不是 /login 开头
     * 就直接扫描+load(隧道 H5)，把正在进行的 CAS 密码提交/二次认证页冲掉；H5
     * 无会话又被 302 回 /login，maybeAutoFillWebvpn 重置重试再点 cas-login……
     * 无限循环，每次循环都轮换 JSESSIONIDCAS 会话 → 图形验证码/短信会话全部
     * 作废（「验证码过期或无效」「第二次发送 get_session_error」的根因）。
     * 修复：①含 /cas/login 的页面（密码表单页/二次认证页，真实域或隧道域）一律
     * 不算登录完成、绝不导航；②已经在隧道 H5 页（/http-8081/）不必「重进」；
     * ③portalScanned 改由「落到纯 /login」复位——用户手动登录成功落回门户后
     * 仍能自动进 H5，不再依赖 stage 回退。
     */
    private fun maybeWebvpnPostLogin(v: WebView, url: String) {
        // 铁律：CAS 认证页（密码表单/二次认证页）绝不算「登录完成」
        if (url.contains("/cas/login")) return
        if (webvpnStage < 1) return
        if (!url.contains("webvpn.dlut.edu.cn")) return
        val path = url.substringAfter("webvpn.dlut.edu.cn")
        // 已经在健康长跑 H5（隧道 URL）——无需重进，嗅探器照常捕获
        if (path.startsWith("/http-8081/")) return
        // 纯登录页不算；但 cas_login=true 回跳（票据验证中/已登录）视为登录完成
        if (path.startsWith("/login") && !url.contains("cas_login=true")) {
            // 新一轮登录尝试开始：允许本轮完成后的 postLogin 扫描接管（含手动登录）
            portalScanned = false
            return
        }
        if (portalScanned) return
        webvpnStage = 3
        log(getString(R.string.h5_log_webvpn_ok))
        portalScanned = true
        // 门户页：①点击资源列表里的健康长跑链接（用户专属前缀，最可靠）；
        // ②没有长跑链接时从任意隧道链接提取前缀，真实前缀直达（v1.8.1 新增）
        val js = """
            (function(){
              try {
                var links = document.querySelectorAll('a[href]');
                var kw = ['mobilenew','8081'];
                for (var i = 0; i < links.length; i++) {
                  var h = links[i].getAttribute('href') || '';
                  var t = (links[i].innerText || '') + (links[i].getAttribute('title') || '');
                  if (/长跑|体质|锻炼|运动/.test(t) || kw.some(function(k){return h.indexOf(k) >= 0;})) {
                    if (h.indexOf('/http-8081/') >= 0 || h.indexOf('8081') >= 0) {
                      var m = h.match(/\/http-8081\/([^\/?#]+)/);
                      links[i].click();
                      return 'res:' + (m ? m[1] : h.slice(0, 80));
                    }
                  }
                }
                for (var j = 0; j < links.length; j++) {
                  var h2 = links[j].getAttribute('href') || '';
                  var m2 = h2.match(/\/http-8081\/([A-Za-z0-9+=_-]{6,128})\//);
                  if (m2) return 'prefix:' + m2[1];
                }
                return 'no_resource';
              } catch(e) { return 'err:' + e.message; }
            })()
        """.trimIndent()
        v.evaluateJavascript(js) { r ->
            when {
                r != null && r.contains("res:") -> {
                    // v1.6.2 提取真实前缀；v1.8.1 起持久化 + 同步 Http（请求时替换 0 占位）
                    val pref = r.trim('"').substringAfter("res:", "")
                    if (pref.length in 8..128 && !pref.contains('/') && pref != "0")
                        adoptTunnelPrefix(pref)
                    log(getString(R.string.h5_log_portal_link, r))
                }
                r != null && r.contains("prefix:") -> {
                    val pref = r.trim('"').substringAfter("prefix:", "")
                    if (pref.length in 6..128 && !pref.contains('/') && pref != "0") {
                        adoptTunnelPrefix(pref)
                        log(getString(R.string.h5_log_portal_prefix, pref.take(12)))
                        load(tunnelH5Url(pref))
                    } else {
                        log(getString(R.string.h5_log_portal_manual))
                    }
                }
                else -> {
                    // v1.8.1：不再盲目载 0 占位版——有前缀用前缀直达，无前缀提示手动
                    if (tunnelPrefix.isNotBlank()) {
                        log(getString(R.string.h5_log_tunnel_h5))
                        load(tunnelH5Url(tunnelPrefix))
                    } else {
                        log(getString(R.string.h5_log_portal_manual))
                    }
                }
            }
        }
    }

    /**
     * v1.8.1：采纳用户真实隧道前缀——持久化到 Prefs、同步 Http.tunnelPrefixHint
     * （发送测试/跑步轮询/批量查询请求时自动替换 URL 中 0 占位段），
     * 并就地修正当前距离模板 URL 里的 0 占位段。
     */
    private fun adoptTunnelPrefix(pref: String) {
        if (pref.isBlank() || pref == "0") return
        tunnelPrefix = pref
        prefs.tunnelPrefix = pref
        Http.tunnelPrefixHint = pref
        try {
            store.distanceProfile()?.let { p ->
                if (p.url.contains("/http-8081/0/")) {
                    p.url = Http.fixTunnelPlaceholder(p.url, pref)
                    store.update(p)
                }
            }
        } catch (_: Exception) {
        }
    }

    /** JS 噅探器回调：所有 XHR/fetch 都会到这里 */
    private inner class SniffBridge {
        @JavascriptInterface
        fun onCapture(json: String) {
            runOnUiThread { handleCapture(json) }
        }

        /** v1.8.6：二次认证页助手回调——短信发送成功（图形验证码已自动刷新） */
        @JavascriptInterface
        fun on2faSent() {
            runOnUiThread { log(getString(R.string.h5_log_2fa_sent)) }
        }
    }

    /**
     * v1.8.6 · 页面级人工验证检测。
     * 学校开启 CAS 短信二次认证后，自动流程只能完成到密码提交，短信/图形验证码必须
     * 由用户在页面上手动完成。检测到验证 UI 时：置 manualAuthMode（冻结自动导航）、
     * 状态栏与日志提示操作步骤（只提示一次）、注入二次认证页助手。落到非认证页时
     * 同步复位（登录成功后的门户/H5 页恢复正常自动流程）。
     */
    private fun detectManualAuthPage(url: String) {
        val isAuthPage = url.contains("/cas/login") ||
                (url.contains("webvpn.dlut.edu.cn") &&
                        url.substringAfter("webvpn.dlut.edu.cn").startsWith("/login"))
        if (!isAuthPage) {
            manualAuthMode = false      // 同步复位：非认证页（门户/H5）恢复正常自动流程
            return
        }
        val js = """
            (function(){
              try {
                var pm1 = document.getElementById('PM1');
                if (pm1) return 'CAS_2FA';
                var sl = document.getElementById('second_login_layer');
                if (sl && (sl.style.display !== 'none' || sl.offsetParent !== null)) return 'WEBVPN_2STEP';
                var sms = document.querySelector('.sms-field');
                if (sms && sms.offsetParent !== null) return 'WEBVPN_SMS_TAB';
                var cap = document.getElementById('captcha-wrap');
                if (cap && cap.offsetParent !== null) return 'WEBVPN_CAPTCHA';
                return '';
              } catch(e) { return ''; }
            })()
        """.trimIndent()
        b.web.evaluateJavascript(js) { r ->
            val kind = r?.trim('"') ?: ""
            if (kind.isBlank()) return@evaluateJavascript
            manualAuthMode = true
            if (!manualAuthHinted) {
                manualAuthHinted = true
                setStatus(getString(R.string.h5_status_manual_auth))
                log(getString(R.string.h5_log_manual_auth))
            }
            if (kind == "CAS_2FA") {
                // 二次认证操作步骤引导（页面重新加载后重发，防错过）
                log(getString(R.string.h5_log_cas_2fa_steps))
                inject2FAHelper()
            }
        }
    }

    /**
     * v1.8.6 · 二次认证页助手（修「第二次发送验证码 get_session_error」）：
     * 学校 CAS 二次认证页的「获取验证码」请求（recheckcode?code=图形码）会【消耗】
     * 当前图形验证码，但「发送成功」与「非法请求 get_session_error」两个分支页面都
     * 不刷新图片——用户第二次点「获取验证码」带的还是已消费的旧码 → 服务端再次报
     * get_session_error/图形码错误，死循环。
     * 本助手监视页面提示：一旦出现「验证码已发送」或「非法请求」立即自动刷新图形
     * 验证码图片并清空输入框，用户照新图输入即可，重复发送与首次一样顺畅。
     */
    private fun inject2FAHelper() {
        val js = """
            (function(){
              if (window.__rm2fa) return; window.__rm2fa = 1;
              var acted = false;
              var timer = setInterval(function(){
                try {
                  var msg = document.getElementById('errormsg');
                  var t = msg ? (msg.textContent || '') : '';
                  // 已发送(\u5df2\u53d1\u9001) / 非法请求(\u975e\u6cd5\u8bf7\u6c42 = get_session_error)
                  if (/\u5df2\u53d1\u9001|\u975e\u6cd5\u8bf7\u6c42/.test(t)) {
                    if (!acted) {
                      acted = true;
                      var img = document.getElementById('codeImage_mobile');
                      var code = document.getElementById('code');
                      if (img) img.src = 'code?' + Math.random();
                      if (code) code.value = '';
                      try { RunMate.on2faSent(); } catch(e) {}
                    }
                  } else if (acted) {
                    acted = false;   // 提示变化（新的一次发送）后允许再次触发
                  }
                } catch(e) {}
              }, 500);
              setTimeout(function(){ clearInterval(timer); }, 15 * 60 * 1000);
            })()
        """.trimIndent()
        b.web.evaluateJavascript(js, null)
    }

    private fun handleCapture(json: String) {
        try {
            val o = JSONObject(json)
            val url = o.optString("url")
            if (url.isBlank()) return
            val method = o.optString("method", "GET")
            val status = o.optInt("status", 0)
            log("→ $method [$status] ${url.take(100)}")

            // 目标：findExtExercise（含全部四个值 + Authorization，且响应 200 = 会话有效）
            if (url.contains("findExtExercise", ignoreCase = true)) {
                val bodyStr = o.optString("body")
                if (bodyStr.isBlank()) return
                if (captured) return
                // v1.6.2：非 200（401/302 等）说明 H5 会话未生效，等它重新发起再抓
                if (status != 200) {
                    log(getString(R.string.h5_log_skip_status, status))
                    return
                }
                val body = JSONObject(bodyStr)
                val userId = body.optString("userId")
                val amId = body.optString("amId")
                val pmId = body.optString("pmId")
                val sign = body.optString("sign")
                val headers = o.optJSONObject("headers")
                var auth = headers?.optString("Authorization") ?: ""
                if (auth.isBlank()) auth = headers?.optString("authorization") ?: ""
                if (userId.isBlank() || sign.isBlank()) return
                captured = true
                applyCapture(url, auth, body, userId, amId, pmId, sign)
            }
        } catch (_: Exception) {
        }
    }

    /** 把捕获结果写入「接口」页配置 + 开启 API 判定 */
    private fun applyCapture(
        reqUrl: String, auth: String, body: JSONObject,
        userId: String, amId: String, pmId: String, sign: String
    ) {
        // v1.6.2：捕获到的可能是相对路径（H5 内部请求）→ 以当前文档 URL 绝对化；
        // H5_TUNNEL 常量的 "0" 占位前缀若未被门户改写，换成真实用户前缀
        var absUrl = reqUrl
        if (!absUrl.startsWith("http", ignoreCase = true)) {
            absUrl = try {
                java.net.URI(b.web.url).resolve(reqUrl).toString()
            } catch (_: Exception) { reqUrl }
        }
        // v1.8.3：嗅探到的也可能是原始内网地址（H5 axios 的内网 BASE_URL，内容
        // 改写未覆盖到的路径）——转隧道 URL 才能校外回放，同时补齐隧道前缀
        if (absUrl.startsWith(com.dut.runmate.auth.SportAuth.SPORT_BASE)) {
            if (tunnelPrefix.isBlank() || tunnelPrefix == "0") {
                tunnelPrefix = com.dut.runmate.auth.SportAuth.tunnelPrefix("202.118.65.138")
                prefs.tunnelPrefix = tunnelPrefix
                Http.tunnelPrefixHint = tunnelPrefix
            }
            absUrl = com.dut.runmate.auth.SportAuth.tunnelUrl(absUrl, tunnelPrefix)
        }
        if (tunnelPrefix.isNotBlank() && absUrl.contains("/http-8081/0/"))
            absUrl = absUrl.replace("/http-8081/0/", "/http-8081/$tunnelPrefix/")
        // v1.8.1：从捕获 URL 反向提取真实前缀并持久化（门户链接没扫到时的兜底来源），
        // 后续 H5 捕获直达与全部距离查询请求都使用真实前缀
        Regex("/http-8081/([^/]+?)/").find(absUrl)?.let { m ->
            val pref = m.groupValues[1]
            if (pref != "0" && pref.length in 6..128 && !pref.contains('/')) adoptTunnelPrefix(pref)
        }

        // 1) 令牌：Authorization（userId:token）；WebVPN 场景还需隧道 Cookie
        prefs.apiToken = auth.ifBlank { "$userId:" }
        if (absUrl.contains("webvpn.dlut.edu.cn")) {
            val ck = try {
                CookieManager.getInstance().getCookie("https://webvpn.dlut.edu.cn")
            } catch (_: Exception) { null }
            prefs.apiCookie = ck ?: ""
        } else {
            prefs.apiCookie = ""
        }

        // 2) 距离模板：URL / 请求头 / 请求体（原样复刻 H5 实际使用的请求）
        val p = store.distanceProfile() ?: return
        p.method = "POST"
        p.url = absUrl
        p.headers = if (prefs.apiCookie.isBlank())
            "{\"Authorization\":\"{token}\",\"Content-Type\":\"application/json;charset=UTF-8\"}"
        else
            "{\"Authorization\":\"{token}\",\"Content-Type\":\"application/json;charset=UTF-8\",\"Cookie\":\"{cookie}\"}"
        p.body = body.toString()
        p.distPath = ApiStore.DIST_PATH
        store.update(p)

        // 3) 自动开启 API 判定
        prefs.apiEnabled = true

        // 4) 回填微哨会话（H5 换来的 userId 与登录一致时仅刷新显示）
        if (prefs.wsUserId.isBlank()) prefs.wsUserId = userId

        // 4.5) v1.7.0 多账号入库：当前登录者整套配置快照保存（学号为主键，
        //      重复捕获自动合并更新）——「查询全部账号距离」与账号切换的数据源
        try {
            val accNumber = number.ifBlank { prefs.wsNumber }.trim()
            if (accNumber.isNotBlank()) {
                com.dut.runmate.data.Accounts.upsert(prefs, store, com.dut.runmate.data.WsAccount(
                    number = accNumber, name = prefs.wsName, pwdB64 = prefs.wsPwdSaved,
                    wsUserId = prefs.wsUserId, wsSkey = prefs.wsSkey, casTgt = prefs.casTgt,
                    apiToken = prefs.apiToken, apiCookie = prefs.apiCookie,
                    apiUrl = absUrl, apiHeaders = p.headers, apiBody = body.toString(),
                    apiDistPath = ApiStore.DIST_PATH,
                    capturedAt = System.currentTimeMillis()
                ))
            }
        } catch (_: Exception) {
        }

        vibrate()
        setStatus(getString(R.string.h5_status_captured))
        log(getString(R.string.h5_log_captured, userId.take(8), amId.take(8), pmId.take(8), sign.take(8)))
        if (prefs.apiCookie.isNotBlank()) log(getString(R.string.h5_log_cookie_saved))

        // 5) 自动发一次测试请求验证整条链路（v1.5.0：页面可能已关闭，回调里判 isFinishing）
        // v1.6.1：结果改为持久对话框（旧版 Toast 一闪而过、长文本显示不全、无法复制）
        lifecycleScope.launch {
            val resp = try {
                Http.call(p, prefs.apiToken, prefs.apiCookie)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                null
            }
            val dist = resp?.let { Http.extractDouble(it.body, p.distPath) }
            val ok = resp != null && resp.error == null && resp.code == 200 && dist != null
            val detail = buildString {
                append(getString(if (ok) R.string.h5_test_ok else R.string.h5_test_fail,
                    if (dist != null) String.format("%.0f", dist) else "")).append("\n\n")
                if (resp != null) {
                    append("HTTP ${resp.code} · ${resp.ms} ms\n")
                    append("URL: ").append(resp.url.take(160)).append("\n\n")
                    append(Http.pretty(resp.body).take(2000))
                }
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                try {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this@H5CatcherActivity)
                        .setTitle(if (ok) R.string.h5_test_title_ok else R.string.h5_test_title_fail)
                        .setMessage(detail)
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.h5_test_copy) { _, _ ->
                            runCatching {
                                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("runmate", detail))
                                toast(R.string.pref_crash_copied)
                            }
                        }
                        .show()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun vibrate() {
        try {
            val v = getSystemService(Vibrator::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26)
                v.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") v.vibrate(300)
        } catch (_: Exception) {
        }
    }

    private fun setStatus(s: String) { if (!isFinishing) b.tvStatus.text = s }

    private fun log(s: String) {
        if (isFinishing) return
        b.tvLog.append(s + "\n")
        b.scrollLog.post { b.scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    // v1.8.11：Toast 改需手动确认对话框（用户实报临时弹窗显示不全，要求全部手动确认关闭）
    private fun toast(resId: Int) = Notice.show(this, resId)
    private fun toast(s: String) = Notice.show(this, s)

    override fun onDestroy() {
        try { b.web.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }
}
