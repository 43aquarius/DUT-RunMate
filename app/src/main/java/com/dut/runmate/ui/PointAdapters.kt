package com.dut.runmate.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.dut.runmate.R
import com.dut.runmate.data.CheckpointStore
import com.dut.runmate.data.Prefs
import com.dut.runmate.databinding.ItemCpStateBinding
import com.dut.runmate.geo.GeoKit
import com.dut.runmate.run.RunBus

class RunPointAdapter(
    private val store: CheckpointStore
) : RecyclerView.Adapter<RunPointAdapter.VH>() {

    private val items = mutableListOf<RunBus.CpSnap>()

    fun submit(snaps: List<RunBus.CpSnap>) {
        items.clear(); items.addAll(snaps)
        notifyDataSetChanged()
    }

    class VH(val b: ItemCpStateBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemCpStateBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val s = items[pos]
        val ctx = holder.b.root.context
        holder.b.tvName.text = s.cp.name
        holder.b.tvSub.text = "半径 ${String.format("%.1f", s.cp.radius)} m · ${if (s.distM >= 0) "${s.distM} m" else "--"}"

        val (label, colorRes, filled) = when (s.state) {
            RunBus.CpState.PENDING -> Triple("待经过", R.color.map_stroke_pending, false)
            RunBus.CpState.NEAR -> Triple("接近中", R.color.md_amber, true)
            RunBus.CpState.IN_ZONE -> Triple("检测区内", R.color.md_tertiary, true)
            RunBus.CpState.GRACE -> Triple("核查中", R.color.md_amber, true)
            RunBus.CpState.CONFIRMED -> Triple("已打卡 ✓", R.color.md_success_bright, true)
            RunBus.CpState.MISSED -> Triple("未确认!", R.color.md_error, true)
        }
        holder.b.chipState.text = if (s.missedCount > 0 && s.state != RunBus.CpState.CONFIRMED)
            "$label ×${s.missedCount}" else label
        val c = ContextCompat.getColor(ctx, colorRes)
        holder.b.chipState.chipBackgroundColor =
            android.content.res.ColorStateList.valueOf(if (filled) c else 0x00000000)
        holder.b.chipState.setTextColor(
            if (filled) ContextCompat.getColor(ctx, R.color.white)
            else ContextCompat.getColor(ctx, R.color.md_on_surface_variant)
        )
    }
}

