package com.dut.runmate.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
    }

    private fun test(p: ApiProfile) {
        if (p.url.isBlank()) {
            Toast.makeText(requireContext(), "URL 为空，请先编辑", Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            b.cardResult.visibility = View.VISIBLE
            b.tvApiBody.text = "请求中…"
            val resp = Http.call(p, prefs.apiToken)
            b.tvApiStatus.text =
                if (resp.error != null) "错误: ${resp.error}"
                else getString(R.string.api_result_fmt, resp.code, resp.ms)
            b.tvApiBody.text = Http.pretty(resp.body)
            if (p.distPath.isNotBlank()) {
                val dv = Http.extractDouble(resp.body, p.distPath)
                b.tvApiExtract.text = if (dv != null)
                    getString(R.string.api_extracted, String.format("%.2f m", dv))
                else getString(R.string.api_extract_fail)
            } else {
                b.tvApiExtract.text = ""
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

    class VH(val b: ItemApiProfileBinding) : androidx.recyclerview.widget.RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemApiProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = profiles.size

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val p = profiles[pos]
        holder.b.tvTitle.text = p.title
        holder.b.tvUrl.text = p.url.ifBlank { "（待抓包后填写）" }
        holder.b.chipMethod.text = p.method
        holder.b.btnTest.setOnClickListener { onTest(p) }
        holder.b.btnEdit.setOnClickListener { onEdit(p) }
    }
}
