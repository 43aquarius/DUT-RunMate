package com.dut.runmate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 指向下一打卡点的罗盘箭头：heading = 相对正北的方位角（度）。
 */
class RadarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f
        textAlign = Paint.Align.CENTER
    }
    private val arrow = Path()

    private var headingDeg = 0f
    private var tint = Color.BLACK

    fun setHeading(deg: Float) {
        headingDeg = ((deg % 360f) + 360f) % 360f
        invalidate()
    }

    fun setTint(color: Int) {
        tint = color
        ringPaint.color = color
        fillPaint.color = color
        textPaint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 6f
        if (r <= 0) return

        canvas.drawCircle(cx, cy, r, ringPaint)
        // 北向标记
        canvas.drawText("N", cx, cy - r + 22f, textPaint)
        canvas.drawLine(cx, cy - r + 28f, cx, cy - r + 40f, ringPaint)

        val rad = Math.toRadians(headingDeg.toDouble())
        canvas.save()
        canvas.rotate(headingDeg, cx, cy)
        arrow.reset()
        arrow.moveTo(cx, cy - r * 0.82f)          // 顶点
        arrow.lineTo(cx - r * 0.34f, cy + r * 0.25f)
        arrow.lineTo(cx, cy + r * 0.02f)
        arrow.lineTo(cx + r * 0.34f, cy + r * 0.25f)
        arrow.close()
        canvas.drawPath(arrow, fillPaint)
        canvas.restore()

        canvas.drawCircle(cx, cy, 6f, fillPaint)
    }
}
