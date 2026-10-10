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
import org.json.JSONObject

/**
 * v1.4.0 · H5 捕获配置页（一键登录配置的第二步）。
 *
 * 背景：健康长跑 H5（202.118.65.138:8081/mobilenew/）自行向服务端换取
 * userId/amId/pmId/sign + Authorization 令牌，算法在 H5 JS 内部（含 sign 签名），
 * 原生无法复刻。本页用内嵌 WebView 完整复刻 i大工 的 H5 运行环境：
 *
 *   1. 注入会话 Cookie：whistlekey（微哨）+ CASTGC（v1.5.1 CAS 统一认证，对齐
 *      i大工 synCookies 同时注入 skey+TGT 的行为——H5 若走 CAS 鉴权即可免密通过）
 *   2. 校内直连 H5；校外自动走 WebVPN（自动填学号密码，验证码/双因素手动完成）
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
        const val H5_DIRECT = "http://202.118.65.138:8081/mobilenew/"
        const val WEBVPN_LOGIN = "https://webvpn.dlut.edu.cn/login"

        /**
         * v1.6.0 · WebVPN 隧道直达 H5 的 URL（实测验证）：
         * wengine（网瑞）门户的路由由查询参数 vpn-12-o1-<host:port> 决定，
         * 前缀段不参与路由（未登录时固定 302 → /login，登录后直达目标）。
         * 实抓包的真实请求形如
         *   /http-8081/<用户加密前缀>/service/mobile/extExercise/findExtExercise?vpn-12-o1-202.118.65.138:8081
         * （见 docs API 文档 §3.4），H5 内部请求由门户改写自动携带正确前缀。
         */
        const val H5_TUNNEL =
            "https://webvpn.dlut.edu.cn/http-8081/0/mobilenew/?vpn-12-o1-202.118.65.138:8081"
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
        r.body = (b && typeof b === 'string') ? b : '';
        send(r);
      }
    } catch(e){}
    return os.apply(this, arguments);
  };
  if (window.fetch) {
    var of = window.fetch;
    window.fetch = function(input, init){
      try {
        var u = (typeof input === 'string') ? input : (input && input.url);
        var h = {};
        if (init && init.headers) {
          if (init.headers.forEach) { init.headers.forEach(function(v,k){ h[k]=v; }); }
          else { h = init.headers; }
        }
        send({method:(init&&init.method)||'GET', url:String(u), headers:h,
              body:(init && typeof init.body==='string') ? init.body : ''});
      } catch(e){}
      return of.apply(this, arguments);
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

    // v1.6.0 WebVPN 自动流程状态：
    //   0 = 未走隧道；1 = 已加载隧道 H5（未登录被重定向到 /login）；
    //   2 = 已自动点击「统一身份认证登录」；3 = 登录完成（回到门户）已自动重进 H5
    private var webvpnStage = 0
    private var portalScanned = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityH5CatcherBinding.inflate(layoutInflater)
        setContentView(b.root)

        number = intent.getStringExtra(EXTRA_NUMBER) ?: prefs.wsNumber
        password = intent.getStringExtra(EXTRA_PASSWORD) ?: ""

        // v1.5.1：不再强制要求 whistlekey——CAS 会话（CASTGC）同样可以完成
        // H5 鉴权（对齐官方 synCookies 注入 skey+TGT 的行为），两者至少有其一即可。
        if (prefs.wsSkey.isBlank() && prefs.casTgt.isBlank()) {
            Toast.makeText(this, R.string.h5_no_session, Toast.LENGTH_LONG).show()
            finish()
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

            override fun onPageStarted(v: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(v, url, favicon)
                injectSniffer()
            }

            override fun onPageFinished(v: WebView, url: String) {
                super.onPageFinished(v, url)
                injectSniffer()
                maybeAutoFillCas(url)
                maybeAutoFillWebvpn(url)
                maybeWebvpnPostLogin(v, url)
                syncCookies()
            }
        }
        // JS 桥（嗅探器回传捕获的请求）
        b.web.addJavascriptInterface(SniffBridge(), "RunMate")

        b.btnH5Direct.setOnClickListener { load(H5_DIRECT) }
        b.btnWebvpn.setOnClickListener { load(WEBVPN_LOGIN) }
        b.btnSniffAgain.setOnClickListener { injectSniffer(); toast(R.string.h5_sniff_reinjected) }
        b.btnGo.setOnClickListener {
            val u = b.etUrl.text.toString().trim()
            if (u.startsWith("http")) load(u)
        }
        b.btnClose.setOnClickListener { finish() }

        log(getString(R.string.h5_log_start))
        start()
    }

    /** 入口：注入会话 Cookie → 探测校内直连 → 选择直连或 WebVPN */
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
        log(getString(if (prefs.wsSkey.isNotBlank()) R.string.h5_log_cookie else R.string.h5_log_cookie_cas))

        lifecycleScope.launch {
            val direct = com.dut.runmate.auth.WhistleAuth.probeDirect()
            setStatus(getString(if (direct) R.string.h5_status_direct else R.string.h5_status_webvpn))
            log(getString(if (direct) R.string.h5_log_direct_ok else R.string.h5_log_direct_fail))
            if (direct) {
                load(H5_DIRECT)
            } else {
                // v1.6.0：校外直接加载隧道 H5 —— 未登录会被门户 302 到 /login，
                // 后续自动点击统一认证 → CAS 自动填表 → 回门户后自动重进 H5。
                // （v1.5.1 直接停在 /login 且找不到本地登录表单，用户无从下手）
                webvpnStage = 1
                load(H5_TUNNEL)
            }
        }
    }

    private fun load(url: String) {
        b.etUrl.setText(url)
        b.web.loadUrl(url)
    }

    private fun injectSniffer() {
        b.web.evaluateJavascript(SNIFFER_JS, null)
    }

    private fun syncCookies() {
        try {
            CookieManager.getInstance().flush()
        } catch (_: Exception) {
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
                webvpnTriedFill = false
                casTriedFill = false
                webvpnStage = 1
                portalScanned = false
                log(getString(R.string.h5_log_webvpn_retry))
            } else {
                return
            }
        }
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
     * 首选在门户资源列表里找「健康长跑」链接（携带用户专属隧道前缀，最可靠），
     * 找不到再退回 H5_TUNNEL 常量 URL（路由由 vpn-12-o1 查询参数决定，实测可达）。
     */
    private fun maybeWebvpnPostLogin(v: WebView, url: String) {
        if (webvpnStage < 2 || webvpnStage >= 3) return
        if (!url.contains("webvpn.dlut.edu.cn")) return
        val path = url.substringAfter("webvpn.dlut.edu.cn")
        // 纯登录页不算；但 cas_login=true 回跳（票据验证中/已登录）视为登录完成
        if (path.startsWith("/login") && !url.contains("cas_login=true")) return
        webvpnStage = 3
        log(getString(R.string.h5_log_webvpn_ok))
        if (portalScanned) return
        portalScanned = true
        // 门户页：优先点击资源列表里的健康长跑链接（拿到用户专属前缀的隧道 URL）
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
                      links[i].click();
                      return 'res:' + h.slice(0, 80);
                    }
                  }
                }
                return 'no_resource';
              } catch(e) { return 'err:' + e.message; }
            })()
        """.trimIndent()
        v.evaluateJavascript(js) { r ->
            if (r != null && r.contains("res:")) {
                log(getString(R.string.h5_log_portal_link, r))
            } else {
                log(getString(R.string.h5_log_tunnel_h5))
                load(H5_TUNNEL)
            }
        }
    }

    /** JS 嗅探器回调：所有 XHR/fetch 都会到这里 */
    private inner class SniffBridge {
        @JavascriptInterface
        fun onCapture(json: String) {
            runOnUiThread { handleCapture(json) }
        }
    }

    private fun handleCapture(json: String) {
        try {
            val o = JSONObject(json)
            val url = o.optString("url")
            if (url.isBlank()) return
            val method = o.optString("method", "GET")
            log("→ $method ${url.take(110)}")

            // 目标：findExtExercise（含全部四个值 + Authorization）
            if (url.contains("findExtExercise", ignoreCase = true)) {
                val bodyStr = o.optString("body")
                if (bodyStr.isBlank()) return
                val body = JSONObject(bodyStr)
                val userId = body.optString("userId")
                val amId = body.optString("amId")
                val pmId = body.optString("pmId")
                val sign = body.optString("sign")
                val headers = o.optJSONObject("headers")
                var auth = headers?.optString("Authorization") ?: ""
                if (auth.isBlank()) auth = headers?.optString("authorization") ?: ""
                if (userId.isBlank() || sign.isBlank()) return
                if (captured) return
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
        // 1) 令牌：Authorization（userId:token）；WebVPN 场景还需隧道 Cookie
        prefs.apiToken = auth.ifBlank { "$userId:" }
        if (reqUrl.contains("webvpn.dlut.edu.cn")) {
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
        p.url = reqUrl
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

        vibrate()
        setStatus(getString(R.string.h5_status_captured))
        log(getString(R.string.h5_log_captured, userId.take(8), amId.take(8), pmId.take(8), sign.take(8)))
        if (prefs.apiCookie.isNotBlank()) log(getString(R.string.h5_log_cookie_saved))

        // 5) 自动发一次测试请求验证整条链路（v1.5.0：页面可能已关闭，回调里判 isFinishing）
        lifecycleScope.launch {
            val resp = Http.call(p, prefs.apiToken, prefs.apiCookie)
            val dist = Http.extractDouble(resp.body, p.distPath)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (resp.error == null && resp.code == 200 && dist != null) {
                    toast(getString(R.string.h5_test_ok, String.format("%.0f", dist)))
                    log(getString(R.string.h5_log_test_ok, String.format("%.0f", dist)))
                } else {
                    toast(R.string.h5_test_fail)
                    log(getString(R.string.h5_log_test_fail,
                        if (resp.error != null) resp.error ?: "" else "HTTP ${resp.code}"))
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

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_LONG).show()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        try { b.web.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }
}
