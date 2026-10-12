package com.dut.runmate.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.dut.runmate.R
import com.dut.runmate.data.Accounts
import com.dut.runmate.data.Prefs
import com.dut.runmate.data.WsAccount
import com.dut.runmate.data.api.ApiProfile
import com.dut.runmate.data.api.ApiStore
import com.dut.runmate.databinding.FragmentApiBinding
import com.dut.runmate.databinding.ItemApiProfileBinding
import com.dut.runmate.databinding.SheetApiBinding
import com.dut.runmate.net.Http
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ApiFragment : Fragment() {

    private var _b: FragmentApiBinding? = null
    private val b get() = _b!!
    private val prefs by lazy { Prefs.get(requireContext()) }
    private val store by lazy { ApiStore.get(requireContext().filesDir) }

    private lateinit var adapter: ProfileAdapter

    /** v1.6.0：正在测试的接口 id（同步给 Adapter 显示「测试中…」防重复点击） */
    private var testingId: String?
        get() = adapter.testingId
        set(v) { adapter.testingId = v }

    /** v1.6.1：最近一次测试的完整结果（供一键复制） */
    private var lastResultText = ""

    /** v1.7.0：批量查询全部账号距离进行中（防重复点击） */
    private var queryingAll = false

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentApiBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        super.onViewCreated(v, savedInstanceState)
        // v1.8.2：复位批量查询在逓标记（旧版取消路径不复位——切页中断后 queryingAll
        // 永久卡 true，「查询全部账号距离」从此点击无反应，只能杀进程恢复）
        queryingAll = false
        adapter = ProfileAdapter(store.profiles, { p -> test(p) }, { p -> edit(p) })
        b.rvProfiles.adapter = adapter
        b.rvProfiles.layoutManager = LinearLayoutManager(requireContext())

        b.etToken.setText(prefs.apiToken)
        b.btnTokenSave.setOnClickListener {
            prefs.apiToken = b.etToken.text.toString().trim()
            Toast.makeText(requireContext(), "令牌已保存", Toast.LENGTH_SHORT).show()
        }
        b.swApiMode.isChecked = prefs.apiEnabled
        b.swApiMode.setOnCheckedChangeListener { _, c -> prefs.apiEnabled = c }

        // v1.4.0 账号登录 · 一键配置（v1.5.0 登录信息本机回填；v1.5.1 CAS+微哨双通道）
        b.etWsNumber.setText(prefs.wsNumber)
        b.etWsPwd.setText(prefs.wsPwdDecoded())
        // 长按密码框：删除当前账号（v1.7.0 起带确认对话框；未入库时退化为清空本机登录信息）
        b.etWsPwd.setOnLongClickListener {
            val cur = prefs.wsNumber.trim()
            val inList = cur.isNotBlank() && Accounts.byNumber(prefs, store, cur) != null
            if (inList) {
                Accounts.byNumber(prefs, store, cur)?.let { confirmDeleteAccount(it) }
            } else {
                clearActiveAccount()
                renderAccounts()
                Toast.makeText(requireContext(), "已清除本机保存的登录信息", Toast.LENGTH_SHORT).show()
            }
            true
        }
        renderWsStatus()
        b.btnWsLogin.setOnClickListener { doLogin() }
        b.btnOpenH5.setOnClickListener { openH5Catcher(prefs.wsPwdDecoded()) }

        // v1.7.0 多账号：chips 列表（切换/长按删除/＋添加）+ 批量查询全部账号距离
        renderAccounts()
        b.btnQueryAll.setOnClickListener { runCatching { queryAll() } }

        // v1.6.1：页面说明（令牌/三个模板/H5 三个入口都是干什么的）
        b.btnHelpApi.setOnClickListener { HelpDialog.show(requireContext()) }
        // v1.6.1：一键复制完整测试结果（旧版底部 Toast 一闪而过无法查看复制）
        b.btnCopyResult.setOnClickListener {
            if (lastResultText.isBlank()) {
                Toast.makeText(requireContext(), R.string.api_result_none, Toast.LENGTH_SHORT).show()
            } else {
                runCatching {
                    val cm = requireActivity().getSystemService(
                        android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("runmate", lastResultText))
                    Toast.makeText(requireContext(), R.string.pref_crash_copied, Toast.LENGTH_SHORT).show()
                }
            }
        }

        // v1.8.1：修复响应体区域无法内部滚动的问题。
        // 根因：整页 ScrollView(scrollApi，外层) 与结果卡片内 240dp ScrollView(svApiBody，
        // 内层) 同向嵌套，外层 onInterceptTouchEvent 在 MOVE 超阈值后抢走手势（Android
        // 经典嵌套滚动冲突），tvApiBody 的 textIsSelectable 又会先消费 DOWN——内层
        // 永远滚不动，用户实报「结果很长显示不全，上下滑动时外部窗口在动」。
        // 修法：按下时按内容是否可滚动态禁止外层拦截（能滚→内层独享手势；
        // 不能滚→交还外层正常滚动整页），抬起/取消时恢复。
        b.svApiBody.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                    val canScroll = v.canScrollVertically(1) || v.canScrollVertically(-1)
                    v.parent.requestDisallowInterceptTouchEvent(canScroll)
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL ->
                    v.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    private fun renderWsStatus() {
        val wsOk = prefs.wsUserId.isNotBlank()
        val casOk = prefs.casTgt.isNotBlank()
        when {
            wsOk -> {
                b.tvWsStatus.text = getString(
                    R.string.ws_status_logged, prefs.wsName, prefs.wsUserId.take(8)
                )
                b.btnOpenH5.visibility = View.VISIBLE
            }
            casOk -> {
                // v1.5.1：微哨通道失败但 CAS 会话可用，H5 捕获仍可走 CAS 鉴权
                b.tvWsStatus.text = getString(R.string.ws_status_cas_only, prefs.wsNumber)
                b.btnOpenH5.visibility = View.VISIBLE
            }
            prefs.wsNumber.isNotBlank() && prefs.wsPwdSaved.isNotBlank() -> {
                b.tvWsStatus.text = getString(R.string.ws_status_saved, prefs.wsNumber)
                b.btnOpenH5.visibility = View.GONE
            }
            else -> {
                b.tvWsStatus.text = getString(R.string.ws_status_none)
                b.btnOpenH5.visibility = View.GONE
            }
        }
    }

    /**
     * v1.5.1 双通道登录：
     *  1) CAS 统一认证（sso.dlut.edu.cn，与网页端同协议）—— 主通道，实测校验可靠
     *  2) 微哨 userLoginCas —— 辅通道，成功可多得 userId/whistlekey（H5 直连场景更稳）
     * 任一成功即打开 H5 捕获页完成自动配置；全部失败才报错（CAS 错误信息更准，优先展示）。
     */
    private fun doLogin() {
        val number = b.etWsNumber.text.toString().trim()
        val password = b.etWsPwd.text.toString()
        if (number.isBlank() || password.isBlank()) {
            b.tvWsStatus.text = getString(R.string.ws_status_need_input)
            return
        }
        // v1.7.0：换账号登录前，先把当前活跃账号快照入库（配置不丢，随时可切回来）
        if (prefs.wsNumber.isNotBlank() && number != prefs.wsNumber.trim()) {
            try {
                Accounts.snapshotCurrent(prefs, store)?.let { Accounts.upsert(prefs, store, it) }
            } catch (_: Exception) {
            }
        }
        b.btnWsLogin.isEnabled = false
        b.tvWsStatus.text = getString(R.string.ws_status_logging)
        viewLifecycleOwner.lifecycleScope.launch {
            var casOk = false
            var wsOk = false
            var casErr = ""
            var wsErr = ""
            // v1.8.8：学校 CAS 启用短信二次认证（MFA）时暂存，待用户在对话框完成
            var mfa: com.dut.runmate.auth.CasAuth.Result.MfaRequired? = null

            // —— 通道 1：CAS 统一认证 ——
            try {
                when (val r = com.dut.runmate.auth.CasAuth.login(number, password)) {
                    is com.dut.runmate.auth.CasAuth.Result.Ok -> {
                        casOk = true
                        prefs.casTgt = r.castgc
                    }
                    // v1.8.8：凭据已验对，学校要求短信二次认证（实测：账号密码无误，
                    // 旧版遇到此页无 CASTGC 又无错误文案，兜底误报「密码错误」）
                    is com.dut.runmate.auth.CasAuth.Result.MfaRequired -> mfa = r
                    // v1.8.9：MfaRetry 只会由 submitMfa 返回，login() 不会出现——占位保证穷尽
                    is com.dut.runmate.auth.CasAuth.Result.MfaRetry -> casErr = r.msg
                    is com.dut.runmate.auth.CasAuth.Result.Err -> casErr = r.msg
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                casErr = "网络错误：${e.message ?: "未知"}"
            }

            // —— 通道 2：微哨（不因 CAS 成功而跳过：成功时能拿 userId/whistlekey）——
            try {
                when (val r = com.dut.runmate.auth.WhistleAuth.login(number, password)) {
                    is com.dut.runmate.auth.WhistleAuth.Result.Ok -> {
                        wsOk = true
                        val s = r.s
                        prefs.wsUserId = s.userId; prefs.wsSkey = s.skey
                        prefs.wsName = s.name; prefs.wsNumber = s.number.ifBlank { number }
                    }
                    is com.dut.runmate.auth.WhistleAuth.Result.Err -> wsErr = r.msg
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                wsErr = "网络错误：${e.message ?: "未知"}"
            }

            if (_b == null) return@launch          // 页面已销毁：直接放弃，绝不触摸 UI
            b.btnWsLogin.isEnabled = true

            // 落盘登录信息（v1.7.0：仅任一通道成功才落盘 —— 失败不再覆盖活跃账号，
            // 否则输错一次新学号就会把当前账号的保存信息挤掉）
            if (casOk || wsOk) {
                prefs.wsNumber = number
                prefs.wsPwdSaved = android.util.Base64.encodeToString(
                    password.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            }

            when {
                casOk -> startNativeConfig(number, password)
                // v1.8.8：CAS 要短信二次认证——弹 MFA 对话框（图形码→发短信→输短信码
                // → CASTGC），完成后与直登同路径；用户取消时若微哨通道成功仍可走
                // H5 捕获页兜底。优先于 wsOk：拿到 CASTGC 才能走原生一键配置。
                mfa != null -> showMfaDialog(mfa!!, number, password, wsOk)
                wsOk -> {
                    renderWsStatus()
                    Toast.makeText(requireContext(),
                        getString(R.string.ws_login_ok, prefs.wsName), Toast.LENGTH_SHORT).show()
                    openH5Catcher(password)
                }
                else -> {
                    // 全部失败：展示 CAS 的错误（更准确）；附带微哨错误便于排查
                    b.tvWsStatus.text = getString(R.string.ws_login_fail, casErr) +
                        if (wsErr.isNotBlank()) "\n（微哨通道：$wsErr）" else ""
                }
            }
        }
    }

    /**
     * v1.8.8：CAS 凭据就绪（直登 Ok 或 MFA 完成）后的原生一键配置。
     * 从 doLogin 的 casOk 分支提取——MFA 对话框完成回调与直登共用同一路径。
     */
    private fun startNativeConfig(number: String, password: String) {
        if (_b == null) return
        // v1.8.3：CAS 会话可用 → 原生一键配置（复刻 H5 全链路，不依赖
        // WebView 嗅探，校外网络同样可用——「账号未捕获」的根治方案）。
        renderWsStatus()
        b.tvWsStatus.text = getString(R.string.ws_native_running)
        viewLifecycleOwner.lifecycleScope.launch {
            // ⚠ SportAuth.login 在 Dispatchers.IO 上回调 log —— 必须转主线程
            // 再触摸 TextView，否则 CalledFromWrongThreadException 直接闪退
            fun uiLog(line: String) {
                if (_b == null) return
                viewLifecycleOwner.lifecycleScope.launch(
                    kotlinx.coroutines.Dispatchers.Main
                ) {
                    if (_b != null) b.tvWsStatus.append("\n$line")
                }
            }
            val sr = com.dut.runmate.auth.SportAuth.login(
                number, password, freshCas = false
            ) { line -> uiLog(line) }
            if (_b == null) return@launch
            when (sr) {
                is com.dut.runmate.auth.SportAuth.Result.Ok -> {
                    com.dut.runmate.auth.SportAuth.applySession(prefs, store, sr.s)
                    b.etToken.setText(prefs.apiToken)
                    b.swApiMode.isChecked = prefs.apiEnabled
                    renderWsStatus()
                    renderAccounts()
                    showNativeOkDialog(sr.s)
                }
                is com.dut.runmate.auth.SportAuth.Result.Err -> {
                    // 原生失败 → 退回 H5 捕获页（手动兜底路径）
                    b.tvWsStatus.text =
                        getString(R.string.ws_native_fail, sr.step, sr.msg)
                    Toast.makeText(requireContext(),
                        getString(R.string.ws_native_fallback), Toast.LENGTH_LONG).show()
                    openH5Catcher(password)
                }
            }
        }
    }

    /**
     * v1.8.8：学校 CAS 新启用短信二次认证（MFA）——凭据已验对（login() 返回
     * MfaRequired，实测：账号密码无误），需用户交互完成：
     *   ① /cas/code 拉图形验证码（点图刷新）
     *   ② 输图形码 → recheckcode 发短信（Set-Cookie 反馈码）
     *   ③ 输短信码 → POST PM1 提交 → 服务端发 CASTGC
     * 与网页端 sso.dlut.edu.cn 二次认证页一一对应；三原语必须在 login() 之后
     * 同进程调用（CAS 会话存 CasAuth 共享 Cookie jar，进程重启作废）。
     * 短信码错误可重试（同一 execution，Spring Webflow 允许）；重试需重新
     * 输图形码（旧图形码已消耗），对话框重拉时自动刷新。
     */
    private fun showMfaDialog(
        mfa: com.dut.runmate.auth.CasAuth.Result.MfaRequired,
        number: String, password: String, wsOk: Boolean
    ) {
        if (_b == null) return
        val view = layoutInflater.inflate(R.layout.dialog_mfa, null)
        val tvPhone = view.findViewById<TextView>(R.id.tvMfaPhone)
        val imgCode = view.findViewById<ImageView>(R.id.imgMfaCode)
        val etGraphic = view.findViewById<EditText>(R.id.etMfaGraphic)
        val btnSend = view.findViewById<Button>(R.id.btnMfaSend)
        val etSms = view.findViewById<EditText>(R.id.etMfaSms)
        val tvErr = view.findViewById<TextView>(R.id.tvMfaErr)

        tvPhone.text = getString(R.string.mfa_intro) + "\n" +
            getString(R.string.mfa_phone_fmt, mfa.phoneTail.ifBlank { "****" })

        fun showErr(res: Int) {
            tvErr.setText(res); tvErr.visibility = View.VISIBLE
        }

        // ① 图形验证码：拉取 + 点击刷新
        fun loadCaptcha() {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val bytes = com.dut.runmate.auth.CasAuth.fetchCaptcha()
                withContext(Dispatchers.Main) {
                    if (_b == null) return@withContext
                    if (bytes == null) {
                        showErr(R.string.mfa_fb_network_error)
                    } else {
                        tvErr.visibility = View.GONE
                        imgCode.setImageBitmap(
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                    }
                }
            }
        }
        imgCode.setOnClickListener { loadCaptcha() }
        loadCaptcha()

        // v1.8.9：持有对话框引用（get_session_error 时需主动关闭后重开登录流程）
        var dialog: androidx.appcompat.app.AlertDialog? = null

        // ② 发送短信验证码（需图形码；反馈码来自 Set-Cookie，与网页端同源）
        btnSend.setOnClickListener {
            val graphic = etGraphic.text.toString().trim()
            if (graphic.isBlank()) { showErr(R.string.mfa_need_graphic); return@setOnClickListener }
            btnSend.isEnabled = false
            btnSend.setText(R.string.mfa_sending)
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val fb = com.dut.runmate.auth.CasAuth.sendMfaSms(graphic)
                withContext(Dispatchers.Main) {
                    if (_b == null) return@withContext
                    btnSend.isEnabled = true
                    btnSend.setText(R.string.mfa_send_sms)
                    when (fb) {
                        "success" -> {
                            // v1.8.9 关键修复：发送成功后【绝不】再拉 /cas/code！
                            // 实测对比网页端：官方页面仅在错误分支刷新图形码，成功分支
                            // 从不刷新——短信已发出后服务端流程进入「待提交短信码」状态，
                            // 此时再请求 /cas/code 会重置二次认证流程：提交短信码必报
                            // 「验证码过期或无效」（用户实报问题1），之后的再次发送也
                            // 会报 get_session_error（用户实报问题2）。需要重发时用户
                            // 自己点图片刷新（此时会重新发送，流程重新推进，安全）。
                            showErr(R.string.mfa_send_ok)
                            etGraphic.setText("")
                        }
                        "img_code_error" -> {
                            showErr(R.string.mfa_fb_img_code_error)
                            loadCaptcha()
                            etGraphic.setText("")
                        }
                        "error1" -> showErr(R.string.mfa_fb_error1)
                        "error2" -> showErr(R.string.mfa_fb_error2)
                        "get_times_more" -> showErr(R.string.mfa_fb_get_times_more)
                        "user_not_exist" -> showErr(R.string.mfa_fb_user_not_exist)
                        "network_error" -> showErr(R.string.mfa_fb_network_error)
                        // v1.8.9：get_session_error = 本次登录会话已被服务端作废
                        // （旧流程状态被重置/过期），唯一恢复方式 = 重新 login()。
                        // 自动重开登录流程，用户只需在新弹出的对话框里重新验证。
                        "get_session_error" -> {
                            dialog?.dismiss()
                            Toast.makeText(requireContext(),
                                R.string.mfa_fb_session_error, Toast.LENGTH_LONG).show()
                            doLogin()
                        }
                        else -> {
                            tvErr.text = getString(R.string.mfa_fb_unknown, fb)
                            tvErr.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }

        dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mfa_title)
            .setView(view)
            .setPositiveButton(R.string.mfa_submit) { _, _ ->
                val sms = etSms.text.toString().trim()
                if (sms.isBlank()) {
                    Toast.makeText(requireContext(), R.string.mfa_need_sms, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                b.btnWsLogin.isEnabled = false
                b.tvWsStatus.text = getString(R.string.mfa_submitting)
                viewLifecycleOwner.lifecycleScope.launch {
                    val r2 = com.dut.runmate.auth.CasAuth.submitMfa(sms, mfa.execution)
                    if (_b == null) return@launch
                    b.btnWsLogin.isEnabled = true
                    when (r2) {
                        is com.dut.runmate.auth.CasAuth.Result.Ok -> {
                            // 与直登成功同路径：落盘 + 原生一键配置
                            prefs.casTgt = r2.castgc
                            prefs.wsNumber = number
                            prefs.wsPwdSaved = android.util.Base64.encodeToString(
                                password.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                            renderWsStatus()
                            startNativeConfig(number, password)
                        }
                        is com.dut.runmate.auth.CasAuth.Result.MfaRetry -> {
                            // v1.8.9：短信码未过但流程仍在二次认证页——用响应页的
                            // 【新 execution】重开对话框（实测 PM1 提交后 e1s2→e1s3
                            // 推进，旧值作废；旧版用旧值重试 = 永远「验证码过期或
                            // 无效」）。新对话框会重新拉图形码（此时流程允许）。
                            b.tvWsStatus.text = getString(R.string.ws_login_fail, r2.msg)
                            Toast.makeText(requireContext(), r2.msg, Toast.LENGTH_LONG).show()
                            showMfaDialog(
                                com.dut.runmate.auth.CasAuth.Result.MfaRequired(
                                    r2.execution,
                                    r2.phoneTail.ifBlank { mfa.phoneTail }),
                                number, password, wsOk)
                        }
                        is com.dut.runmate.auth.CasAuth.Result.Err -> {
                            // 会话已丢失（回到密码页等）：重开整个登录流程
                            b.tvWsStatus.text = getString(R.string.ws_login_fail, r2.msg)
                            Toast.makeText(requireContext(), r2.msg, Toast.LENGTH_LONG).show()
                            doLogin()
                        }
                        else -> {}
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                if (wsOk) {
                    // 微哨通道已成功：仍可走 H5 捕获页兜底
                    renderWsStatus()
                    openH5Catcher(password)
                } else {
                    b.tvWsStatus.text = getString(R.string.ws_login_fail,
                        "未完成短信二次认证（学校新要求，网页/客户端登录均需短信验证）")
                }
            }
            .show()
    }

    private fun openH5Catcher(password: String) {
        val hasCredential = prefs.casTgt.isNotBlank() || prefs.wsSkey.isNotBlank()
        // v1.8.9：无已保存会话但学号+密码已填 → 也允许进入（学校开启短信二次认证后
        // 的兜底路径：页面自动填表，短信验证在页面上手动完成，捕获器不会再打扰）
        val hasForm = b.etWsNumber.text.toString().trim().isNotBlank() && password.isNotBlank()
        if (!hasCredential && !hasForm) {
            b.tvWsStatus.text = getString(R.string.ws_status_none)
            return
        }
        val it = android.content.Intent(requireContext(), H5CatcherActivity::class.java)
        it.putExtra(H5CatcherActivity.EXTRA_NUMBER, b.etWsNumber.text.toString().trim())
        if (password.isNotEmpty()) it.putExtra(H5CatcherActivity.EXTRA_PASSWORD, password)
        startActivity(it)
    }

    /**
     * v1.8.3：原生一键配置成功弹窗——姓名/学号/班级 + 距离验证结果 + 使用提示。
     * 替代旧版「登录成功 → 打开 H5 捕获页」的两步流程，一步到位。
     */
    private fun showNativeOkDialog(s: com.dut.runmate.auth.SportAuth.Session) {
        if (_b == null) return
        val distLine = if (s.distanceRaw == "无")
            getString(R.string.ws_native_dist_none)
        else
            getString(R.string.ws_native_dist, s.distanceRaw)
        val msg = getString(R.string.ws_native_ok_body,
            s.name, s.number, s.userId.take(8), distLine)
        try {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ws_native_ok_title)
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } catch (_: Throwable) {
        }
    }

    // ==================== v1.7.0 多账号 ====================

    /** chips 显示名：优先姓名，无姓名用学号 */
    private fun acctLabel(a: WsAccount): String =
        a.name.ifBlank { a.number }.ifBlank { "未命名" }

    /**
     * 渲染账号 chips：✓=当前活跃账号；点击切换；长按删除；末尾「＋添加」录入新账号。
     * 「查询全部账号距离」按钮：至少一个账号完成过 H5 捕获才显示。
     */
    private fun renderAccounts() {
        val list = Accounts.load(prefs, store)
        val active = prefs.wsNumber.trim()
        b.layAccounts.removeAllViews()
        for (acc in list) {
            val chip = Chip(requireContext())
            val isActive = acc.number == active
            chip.text = (if (isActive) "✓ " else "") + acctLabel(acc) +
                if (acc.captured) "" else "（未捕获）"
            chip.isCheckable = false
            chip.setOnClickListener { runCatching { switchAccount(acc.number) } }
            chip.setOnLongClickListener {
                confirmDeleteAccount(acc)
                true
            }
            b.layAccounts.addView(chip)
        }
        val addChip = Chip(requireContext())
        addChip.text = getString(R.string.acct_add)
        addChip.isCheckable = false
        addChip.setOnClickListener {
            b.etWsNumber.setText("")
            b.etWsPwd.setText("")
            b.etWsNumber.requestFocus()
            b.tvWsStatus.text = getString(R.string.acct_add_hint)
        }
        b.layAccounts.addView(addChip)

        b.scrollAccounts.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        b.btnQueryAll.visibility = if (list.any { it.captured }) View.VISIBLE else View.GONE
    }

    /** 切换活跃账号：快照当前 → 目标写入 Prefs/DIST 模板 → 刷新整页 */
    private fun switchAccount(number: String) {
        val acc = Accounts.byNumber(prefs, store, number) ?: return
        val changed = Accounts.switchTo(prefs, store, number)
        if (!changed) return
        b.etWsNumber.setText(acc.number)
        b.etWsPwd.setText(prefs.wsPwdDecoded())
        b.etToken.setText(prefs.apiToken)
        renderWsStatus()
        renderAccounts()
        Toast.makeText(requireContext(), getString(
            if (acc.captured) R.string.acct_switched else R.string.acct_switch_need_capture,
            acctLabel(acc)), Toast.LENGTH_LONG).show()
    }

    private fun confirmDeleteAccount(acc: WsAccount) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.acct_delete_title)
            .setMessage(getString(R.string.acct_delete_msg, acctLabel(acc)))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                runCatching { deleteAccountFlow(acc) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 删除账号。删的是当前活跃账号时：先清空活跃字段再切换到剩余账号
     * （顺序很关键 —— switchAccount 会快照当前状态，若不清空会把刚删的账号又加回来）。
     */
    private fun deleteAccountFlow(acc: WsAccount) {
        Accounts.delete(prefs, store, acc.number)
        if (acc.number == prefs.wsNumber.trim()) {
            prefs.wsNumber = ""; prefs.wsPwdSaved = ""
            prefs.wsUserId = ""; prefs.wsSkey = ""; prefs.wsName = ""
            prefs.casTgt = ""; prefs.apiToken = ""; prefs.apiCookie = ""
            val remaining = Accounts.load(prefs, store)
            if (remaining.isNotEmpty()) {
                renderAccounts()
                switchAccount(remaining.first().number)
                return
            }
            b.etWsNumber.setText(""); b.etWsPwd.setText(""); b.etToken.setText("")
            renderWsStatus()
        }
        renderAccounts()
        Toast.makeText(requireContext(), getString(R.string.acct_deleted, acctLabel(acc)),
            Toast.LENGTH_SHORT).show()
    }

    /** 清空活跃账号的全部本机信息（不动作账号列表） */
    private fun clearActiveAccount() {
        prefs.wsNumber = ""; prefs.wsPwdSaved = ""
        prefs.wsUserId = ""; prefs.wsSkey = ""; prefs.wsName = ""
        prefs.casTgt = ""; prefs.apiToken = ""; prefs.apiCookie = ""
        b.etWsNumber.setText(""); b.etWsPwd.setText("")
        renderWsStatus()
    }

    /**
     * v1.7.0 核心：批量查询全部账号距离。
     * 每个账号用它自己的接口快照（URL/请求体/令牌/Cookie）逐个请求 findExtExercise，
     * 校外直连失败会标注原因；结果汇总成持久对话框（可复制）。
     */
    private fun queryAll() {
        if (queryingAll) return
        // 先把当前活跃账号最新状态入库（刚捕获/刚改过配置也能查到）
        try {
            Accounts.snapshotCurrent(prefs, store)?.let { Accounts.upsert(prefs, store, it) }
        } catch (_: Exception) {
        }
        val accounts = Accounts.load(prefs, store)
        if (accounts.isEmpty()) {
            Toast.makeText(requireContext(), R.string.acct_query_none, Toast.LENGTH_LONG).show()
            return
        }
        queryingAll = true
        b.btnQueryAll.isEnabled = false
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.acct_query_title, accounts.size))
            .setMessage(getString(R.string.acct_query_progress, 0, accounts.size,
                acctLabel(accounts.first())))
            .setCancelable(false)
            .show()

        viewLifecycleOwner.lifecycleScope.launch {
            val sb = StringBuilder()
            var offCampusAny = false
            try {
                accounts.forEachIndexed { i, acc ->
                    if (_b == null) {                       // 页面已销毁：放弃 UI 更新
                        dialog.dismiss(); queryingAll = false; return@launch
                    }
                    dialog.setMessage(getString(R.string.acct_query_progress,
                        i + 1, accounts.size, acctLabel(acc)))
                    val line = queryAccountLine(acc)
                    if (line.contains("【校外直连】")) offCampusAny = true
                    sb.append(line).append('\n')
                }
            } catch (e: CancellationException) {
                dialog.dismiss()
                queryingAll = false        // v1.8.2：页面销毁取消时同样复位（见 onViewCreated 注释）
                throw e
            } catch (_: Exception) {
            }
            if (offCampusAny) sb.append('\n').append(getString(R.string.api_hint_offcampus))
            if (sb.contains("【需重新捕获】"))
                sb.append('\n').append(getString(R.string.api_hint_webvpn_expired))
            dialog.dismiss()
            if (_b == null) return@launch
            val result = sb.toString()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.acct_query_title, accounts.size))
                .setMessage(result)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.h5_test_copy) { _, _ ->
                    runCatching {
                        val cm = requireActivity().getSystemService(
                            android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("runmate", result))
                        Toast.makeText(requireContext(), R.string.pref_crash_copied,
                            Toast.LENGTH_SHORT).show()
                    }
                }
                .show()
            queryingAll = false
            b.btnQueryAll.isEnabled = true
        }
    }

    /** 查询单个账号的距离（IO 挂起），返回一行结果文本 */
    private suspend fun queryAccountLine(acc: WsAccount): String {
        val label = acctLabel(acc)
        if (!acc.captured) return "✗ $label：" + getString(R.string.acct_not_captured)
        val prof = ApiProfile(
            id = "batch_${acc.number}", title = label, method = "POST",
            url = acc.apiUrl, headers = acc.apiHeaders.ifBlank { "{}" },
            body = acc.apiBody, distPath = acc.apiDistPath.ifBlank { ApiStore.DIST_PATH }
        )
        val resp = Http.call(prof, acc.apiToken, acc.apiCookie)
        return when {
            resp.error != null -> {
                val off = resp.error.contains("connect", true) &&
                        acc.apiUrl.contains("202.118.65.138") &&
                        !acc.apiUrl.contains("webvpn")
                "✗ $label：${resp.error}" + if (off) "【校外直连】" else ""
            }
            // v1.8.0：隧道会话失效被重定向到登录页（HTTP 200 但拿回的是 HTML）
            Http.isWebvpnLoginRedirect(resp.finalUrl, resp.body, resp.url) ->
                "✗ $label：WebVPN 会话已过期【需重新捕获】"
            resp.code == 200 -> {
                val dv = Http.extractDouble(resp.body, prof.distPath)
                if (dv != null) "✓ $label：${String.format("%.0f", dv)} m（${resp.ms} ms）"
                else "✗ $label：HTTP 200 但未提取到距离（令牌可能过期，切换到该账号重新捕获）"
            }
            else -> "✗ $label：HTTP ${resp.code}（令牌可能过期，切换到该账号重新捕获）"
        }
    }

    private fun test(p: ApiProfile) {
        if (p.url.isBlank()) {
            Toast.makeText(requireContext(), "URL 为空，请先编辑", Toast.LENGTH_SHORT).show()
            return
        }
        if (testingId != null) return                  // v1.6.0：防重复点击
        testingId = p.id
        adapter.notifyItemChanged(store.profiles.indexOfFirst { it.id == p.id })
        // v1.6.0：点击立即反馈（旧版卡片在页面底部且无任何提示，用户以为没反应）
        b.cardResult.visibility = View.VISIBLE
        b.tvApiStatus.text = getString(R.string.api_testing)
        b.tvApiExtract.text = p.title
        b.tvApiBody.text = "→ ${p.method} ${p.url.take(120)}"
        b.scrollApi.post { b.scrollApi.smoothScrollTo(0, b.cardResult.top) }
        // v1.6.1：反馈只走卡片（旧版底部 Toast 显示不全且一闪而过，无法查看复制）
        lastResultText = "${p.title}\n${p.method} ${p.url}"
        viewLifecycleOwner.lifecycleScope.launch {
            // v1.5.1：整段 runCatching 双保险——任何异常都进结果卡片，绝不崩溃白屏
            runCatching {
                val resp = Http.call(p, prefs.apiToken, prefs.apiCookie)
                if (_b == null) return@launch          // 页面已销毁（v1.5.0 闪退修复）
                val ok = resp.error == null && resp.code in 200..299
                val statusLine = when {
                    resp.error != null -> getString(R.string.api_test_error, resp.error)
                    ok -> getString(R.string.api_test_ok, resp.code, resp.ms)
                    else -> getString(R.string.api_test_fail, resp.code, resp.ms)
                }
                b.tvApiStatus.text = statusLine
                // v1.8.0：根因提示（校外直连不可达 / WebVPN 会话失效重定向到登录页）
                var statusText = statusLine
                if (resp.error != null && resp.error.contains("connect", true) &&
                    p.url.contains("202.118.65.138") && !p.url.contains("webvpn")) {
                    statusText = statusLine + "\n" + getString(R.string.api_hint_offcampus)
                    b.tvApiStatus.text = statusText
                } else if (resp.error == null && Http.isWebvpnLoginRedirect(
                        resp.finalUrl, resp.body, resp.url)) {
                    statusText = statusLine + "\n" + getString(R.string.api_hint_webvpn_expired)
                    b.tvApiStatus.text = statusText
                }
                b.tvApiStatus.setTextColor(ContextCompat.getColor(requireContext(),
                    if (ok) R.color.md_success_bright else R.color.md_error))
                b.tvApiBody.text = Http.pretty(resp.body)
                var extractLine = p.title
                if (p.distPath.isNotBlank()) {
                    val dv = Http.extractDouble(resp.body, p.distPath)
                    extractLine = when {
                        dv != null -> getString(R.string.api_extracted, String.format("%.2f m", dv))
                        // v1.8.3：「无」= 今日尚未开跑（正常基线，不是配置错误）
                        Http.extractRaw(resp.body, p.distPath) == "无" ->
                            getString(R.string.api_test_no_record)
                        else -> getString(R.string.api_extract_fail)
                    }
                }
                b.tvApiExtract.text = extractLine
                // v1.6.1：完整结果落内存，供「复制完整结果」一键取用
                lastResultText = buildString {
                    append(p.title).append('\n')
                    append(p.method).append(' ').append(resp.url).append("\n\n")
                    append(statusText).append('\n')
                    append(extractLine).append("\n\n")
                    append(Http.pretty(resp.body))
                }
            }.onFailure { e ->
                // 兜底：把异常写进结果卡片而不是闪退
                if (_b != null) {
                    b.cardResult.visibility = View.VISIBLE
                    val errLine = getString(R.string.api_test_error,
                        "${e.javaClass.simpleName}: ${e.message ?: ""}")
                    b.tvApiStatus.text = errLine
                    b.tvApiBody.text = android.util.Log.getStackTraceString(e).take(3000)
                    lastResultText = "${p.title}\n${p.method} ${p.url}\n\n$errLine\n\n${b.tvApiBody.text}"
                }
            }
            // v1.6.0：恢复按钮状态（页面已销毁时无需理会）
            if (_b != null) {
                val idx = store.profiles.indexOfFirst { it.id == testingId }
                testingId = null
                if (idx >= 0) adapter.notifyItemChanged(idx)
            }
        }
    }

    private fun edit(p: ApiProfile) {
        val dialog = BottomSheetDialog(requireContext(), com.dut.runmate.R.style.Theme_RunMate_BottomSheet)
        val sb = SheetApiBinding.inflate(layoutInflater, null, false)
        dialog.setContentView(sb.root)

        sb.etApiTitle.setText(p.title)
        sb.etApiUrl.setText(p.url)
        sb.etApiHeaders.setText(p.headers)
        sb.etApiBody.setText(p.body)
        sb.etApiPath.setText(p.distPath)
        when (p.method.uppercase()) {
            "POST" -> sb.chipPost.isChecked = true
            "PUT" -> sb.chipPut.isChecked = true
            else -> sb.chipGet.isChecked = true
        }

        sb.btnSheetApiSave.setOnClickListener {
            p.title = sb.etApiTitle.text.toString().ifBlank { "未命名" }
            p.url = sb.etApiUrl.text.toString().trim()
            p.headers = sb.etApiHeaders.text.toString().ifBlank { "{}" }
            p.body = sb.etApiBody.text.toString()
            p.distPath = sb.etApiPath.text.toString().trim()
            p.method = when {
                sb.chipPost.isChecked -> "POST"
                sb.chipPut.isChecked -> "PUT"
                else -> "GET"
            }
            store.update(p)
            adapter.notifyItemChanged(store.profiles.indexOfFirst { it.id == p.id })
            dialog.dismiss()
        }
        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        // v1.6.2：从 H5 捕获页返回后刷新令牌/开关/登录状态。
        // MainActivity 用 replace() 管理 Fragment、捕获页是覆盖其上的独立 Activity，
        // 返回时 View 不重建——旧版令牌框不刷新，即使捕获成功用户也以为「没自动填充」。
        if (_b != null) {
            b.etToken.setText(prefs.apiToken)
            b.swApiMode.isChecked = prefs.apiEnabled
            renderWsStatus()
            // v1.7.0：从 H5 捕获页返回后刷新账号 chips（新捕获的账号会自动入库）
            renderAccounts()
        }
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }
}

class ProfileAdapter(
    private val profiles: List<ApiProfile>,
    private val onTest: (ApiProfile) -> Unit,
    private val onEdit: (ApiProfile) -> Unit
) : androidx.recyclerview.widget.RecyclerView.Adapter<ProfileAdapter.VH>() {

    /** v1.6.0：正在测试中的接口 id（由外部持有/更新后 notifyDataSetChanged） */
    var testingId: String? = null

    class VH(val b: ItemApiProfileBinding) : androidx.recyclerview.widget.RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemApiProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = profiles.size

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val p = profiles[pos]
        holder.b.tvTitle.text = p.title
        holder.b.tvUrl.text = p.url.ifBlank { "（待抓包后填写）" }
        holder.b.chipMethod.text = p.method
        val busy = testingId == p.id
        holder.b.btnTest.isEnabled = !busy
        holder.b.btnTest.text = if (busy) "测试中…" else "发送测试"
        holder.b.btnTest.setOnClickListener { runCatching { onTest(p) } }
        holder.b.btnEdit.setOnClickListener { runCatching { onEdit(p) } }
    }
}
