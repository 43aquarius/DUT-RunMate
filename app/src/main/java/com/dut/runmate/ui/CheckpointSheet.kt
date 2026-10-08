package com.dut.runmate.ui

import com.dut.runmate.data.Checkpoint
import com.dut.runmate.data.CheckpointStore
import com.dut.runmate.data.Prefs
import com.dut.runmate.databinding.SheetCheckpointBinding
import com.dut.runmate.geo.GeoKit
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * 新增/编辑打卡点底部弹窗（地图长按、标定保存、列表编辑共用）。
 */
object CheckpointSheet {

    fun show(
        fragment: androidx.fragment.app.Fragment,
        store: CheckpointStore,
        origin: Checkpoint?,
        lat: Double? = null,
        lon: Double? = null,
        onDone: () -> Unit
    ) {
        val ctx = fragment.requireContext()
        val prefs = Prefs.get(ctx)
        val dialog = BottomSheetDialog(ctx, com.dut.runmate.R.style.Theme_RunMate_BottomSheet)
        val b = SheetCheckpointBinding.inflate(fragment.layoutInflater, null, false)
        dialog.setContentView(b.root)

        val editing = origin != null
        val cp = origin ?: Checkpoint(radius = prefs.defaultRadius)

        b.tvSheetTitle.text = if (editing) ctx.getString(com.dut.runmate.R.string.edit_point)
        else ctx.getString(com.dut.runmate.R.string.add_point)

        b.etCpName.setText(cp.name.ifEmpty { store.defaultName() })
        b.etCpNote.setText(cp.note)
        b.sliderSheetRadius.value = cp.radius.toFloat().coerceIn(10f, 100f)
        b.tvSheetRadius.text = "${cp.radius} m"

        if (lat != null && lon != null) {
            b.etLat.setText(GeoKit.fmt7(lat))
            b.etLon.setText(GeoKit.fmt7(lon))
        } else {
            b.etLat.setText(GeoKit.fmt7(cp.lat))
            b.etLon.setText(GeoKit.fmt7(cp.lon))
        }

        fun refreshGcjLabel() {
            val la = b.etLat.text.toString().toDoubleOrNull()
            val lo = b.etLon.text.toString().toDoubleOrNull()
            if (la == null || lo == null) { b.tvSheetCoords.text = ""; return }
            val g = if (b.chipGcj.isChecked) doubleArrayOf(la, lo) else GeoKit.wgs2gcj(la, lo)
            val w = if (b.chipGcj.isChecked) GeoKit.gcj2wgs(la, lo) else doubleArrayOf(la, lo)
            b.tvSheetCoords.text =
                "GCJ-02 ${GeoKit.fmt7(g[0])}, ${GeoKit.fmt7(g[1])}  ·  WGS-84 ${GeoKit.fmt7(w[0])}, ${GeoKit.fmt7(w[1])}"
        }

        if (prefs.coordGcj) b.chipGcj.isChecked = true else b.chipWgs.isChecked = true
        b.chipWgs.setOnCheckedChangeListener { _, _ -> refreshGcjLabel() }
        b.chipGcj.setOnCheckedChangeListener { _, _ -> refreshGcjLabel() }
        b.etLat.setOnFocusChangeListener { _, _ -> refreshGcjLabel() }
        b.etLat.setOnClickListener { refreshGcjLabel() }
        b.etLat.doAfterTextChangedCompat { refreshGcjLabel() }
        b.etLon.doAfterTextChangedCompat { refreshGcjLabel() }
        refreshGcjLabel()

        b.sliderSheetRadius.addOnChangeListener { _, v, _ ->
            b.tvSheetRadius.text = "${v.toInt()} m"
        }

        if (editing) b.btnSheetDelete.isVisibleSafe(true) else b.btnSheetDelete.isVisibleSafe(false)

        b.btnSheetDelete.setOnClickListener {
            store.delete(cp.id)
            dialog.dismiss(); onDone()
        }

        b.btnSheetSave.setOnClickListener {
            val la = b.etLat.text.toString().toDoubleOrNull()
            val lo = b.etLon.text.toString().toDoubleOrNull()
            if (la == null || lo == null || la == 0.0 || lo == 0.0) {
                b.etLat.error = "坐标无效"; return@setOnClickListener
            }
            var wLat = la; var wLon = lo
            if (b.chipGcj.isChecked) {
                val w = GeoKit.gcj2wgs(la, lo); wLat = w[0]; wLon = w[1]
            }
            if (outOfChinaGuard(wLat, wLon)) {
                b.etLat.error = "坐标超出中国区域"; return@setOnClickListener
            }
            cp.name = b.etCpName.text.toString().ifBlank { store.defaultName() }
            cp.note = b.etCpNote.text.toString()
            cp.radius = b.sliderSheetRadius.value.toInt()
            cp.lat = wLat; cp.lon = wLon
            if (!editing) cp.source = "manual"
            store.upsert(cp)
            dialog.dismiss(); onDone()
        }

        dialog.show()
    }

    private fun outOfChinaGuard(lat: Double, lon: Double) = GeoKit.outOfChina(lat, lon)
}

private fun android.widget.EditText.doAfterTextChangedCompat(f: () -> Unit) {
    addTextChangedListener(object : android.text.TextWatcher {
        override fun afterTextChanged(s: android.text.Editable?) { f() }
        override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) { }
        override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) { }
    })
}

private fun android.view.View.isVisibleSafe(v: Boolean) {
    visibility = if (v) android.view.View.VISIBLE else android.view.View.GONE
}
