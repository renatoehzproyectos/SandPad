package com.sandpad.pad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Full-screen transparent layer that draws the arrow cursor, so it can never be clamped by window placement. */
class CursorView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private var px = 0f
    private var py = 0f
    private val shape = Path().apply {
        moveTo(0f, 0f); lineTo(0f, 20f * d); lineTo(5f * d, 15.5f * d); lineTo(8.5f * d, 23f * d)
        lineTo(12f * d, 21.5f * d); lineTo(8.5f * d, 14.5f * d); lineTo(14.5f * d, 14.5f * d); close()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.parseColor("#c3592b") }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d; color = Color.WHITE; strokeJoin = Paint.Join.ROUND
    }

    fun setPos(x: Float, y: Float) { px = x; py = y; invalidate() }

    override fun onDraw(c: Canvas) {
        c.save(); c.translate(px, py)
        c.drawPath(shape, edge); c.drawPath(shape, fill)
        c.restore()
    }
}
