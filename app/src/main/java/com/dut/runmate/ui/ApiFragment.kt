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
import com.dut.runmate.data.Prefs
import com.dut.runmate.data.api.ApiProfile
import com.dut.runmate.data.api.ApiStore
import com.dut.runmate.databinding.FragmentApiBinding
import com.dut.runmate.databinding.ItemApiProfileBinding
import com.dut.runmate.databinding.SheetApiBinding
import com.dut.runmate.net.Http
import com.google.android.material.bottomsheet.BottomSheetDialog
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
        // 长按密码框：清除本机保存的登录信息（学号/会话/密码/CAS）
        b.etWsPwd.setOnLongClickListener {
            prefs.wsNumber = ""; prefs.wsPwdSaved = ""
            prefs.wsUserId = ""; prefs.wsSkey = ""; prefs.wsName = ""
            prefs.casTgt = ""
            b.etWsNumber.setText(""); b.etWsPwd.setText("")
            renderWsStatus()
            Toast.makeText(requireContext(), "已清除本机保存的登录信息", Toast.LENGTH_SHORT).show()
            true
        }
        renderWsStatus()
        b.btnWsLogin.setOnClickListener { doLogin() }
        b.btnOpenH5.setOnClickListener { openH5Catcher(prefs.wsPwdDecoded()) }
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

            // 落盘登录信息（下次自动回填）
            prefs.wsNumber = number
            prefs.wsPwdSaved = android.util.Base64.encodeToString(
                password.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)

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
        Toast.makeText(requireContext(),
            getString(R.string.api_test_toast, p.title), Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            // v1.5.1：整段 runCatching 双保险——任何异常都进结果卡片，绝不崩溃白屏
            runCatching {
                val resp = Http.call(p, prefs.apiToken, prefs.apiCookie)
                if (_b == null) return@launch          // 页面已销毁（v1.5.0 闪退修复）
                val ok = resp.error == null && resp.code in 200..299
                b.tvApiStatus.text = when {
                    resp.error != null -> getString(R.string.api_test_error, resp.error)
                    ok -> getString(R.string.api_test_ok, resp.code, resp.ms)
                    else -> getString(R.string.api_test_fail, resp.code, resp.ms)
                }
                b.tvApiStatus.setTextColor(ContextCompat.getColor(requireContext(),
                    if (ok) R.color.md_success_bright else R.color.md_error))
                b.tvApiBody.text = Http.pretty(resp.body)
                if (p.distPath.isNotBlank()) {
                    val dv = Http.extractDouble(resp.body, p.distPath)
                    b.tvApiExtract.text = if (dv != null)
                        getString(R.string.api_extracted, String.format("%.2f m", dv))
                    else getString(R.string.api_extract_fail)
                } else {
                    b.tvApiExtract.text = p.title
                }
            }.onFailure { e ->
                // 兜底：把异常写进结果卡片而不是闪退
                if (_b != null) {
                    b.cardResult.visibility = View.VISIBLE
                    b.tvApiStatus.text = getString(R.string.api_test_error,
                        "${e.javaClass.simpleName}: ${e.message ?: ""}")
                    b.tvApiBody.text = android.util.Log.getStackTraceString(e).take(3000)
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
