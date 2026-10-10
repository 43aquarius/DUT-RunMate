package com.dut.runmate.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import kotlinx.coroutines.launch

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

            // —— 通道 1：CAS 统一认证 ——
            try {
                when (val r = com.dut.runmate.auth.CasAuth.login(number, password)) {
                    is com.dut.runmate.auth.CasAuth.Result.Ok -> {
                        casOk = true
                        prefs.casTgt = r.castgc
                    }
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
                casOk && wsOk -> {
                    renderWsStatus()
                    Toast.makeText(requireContext(),
                        getString(R.string.ws_login_ok, prefs.wsName), Toast.LENGTH_SHORT).show()
                    openH5Catcher(password)
                }
                casOk -> {
                    // CAS 会话已建立（可完成自动配置），微哨通道单独失败不影响
                    renderWsStatus()
                    Toast.makeText(requireContext(),
                        getString(R.string.ws_login_cas_ok), Toast.LENGTH_LONG).show()
                    openH5Catcher(password)
                }
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

    private fun openH5Catcher(password: String) {
        val hasCredential = prefs.casTgt.isNotBlank() || prefs.wsSkey.isNotBlank()
        if (!hasCredential) {
            b.tvWsStatus.text = getString(R.string.ws_status_none)
            return
        }
        val it = android.content.Intent(requireContext(), H5CatcherActivity::class.java)
        it.putExtra(H5CatcherActivity.EXTRA_NUMBER, b.etWsNumber.text.toString().trim())
        if (password.isNotEmpty()) it.putExtra(H5CatcherActivity.EXTRA_PASSWORD, password)
        startActivity(it)
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
                        dialog.dismiss(); return@launch
                    }
                    dialog.setMessage(getString(R.string.acct_query_progress,
                        i + 1, accounts.size, acctLabel(acc)))
                    val line = queryAccountLine(acc)
                    if (line.contains("【校外直连】")) offCampusAny = true
                    sb.append(line).append('\n')
                }
            } catch (e: CancellationException) {
                dialog.dismiss()
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
                    extractLine = if (dv != null)
                        getString(R.string.api_extracted, String.format("%.2f m", dv))
                    else getString(R.string.api_extract_fail)
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
