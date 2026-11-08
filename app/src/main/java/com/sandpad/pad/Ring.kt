package com.sandpad.pad

import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** One button on the ring: its action, first slot, and how many slots wide it is. */
data class Btn(val type: String, val start: Int, val span: Int) {
    fun covers(slot: Int): Boolean {
        for (i in 0 until span) if ((start + i) % Ring.SLOTS == slot) return true
        return false
    }
}

object Ring {
    const val SLOTS = 12
    val TYPES = listOf("window", "back", "home", "recents", "drag", "min", "qs", "move", "up", "down", "left", "right")

    fun label(t: String): String = when (t) {
        "window" -> "▦"; "back" -> "◀"; "home" -> "●"; "recents" -> "▣"; "drag" -> "✥"; "min" -> "–"
        "qs" -> "⚙"; "move" -> "≡"; "up" -> "↑"; "down" -> "↓"; "left" -> "←"; "right" -> "→"
        else -> "?"
    }

    fun name(t: String): String = when (t) {
        "window" -> "Window menu"; "back" -> "Back"; "home" -> "Home"; "recents" -> "Recents"; "drag" -> "Drag / scroll"
        "min" -> "Minimize"; "qs" -> "Quick settings"; "move" -> "Move pad"
        "up" -> "Scroll up"; "down" -> "Scroll down"; "left" -> "Scroll left"; "right" -> "Scroll right"
        else -> t
    }

    fun defaults(): List<Btn> = listOf(
        Btn("window", 0, 1), Btn("drag", 1, 2), Btn("min", 3, 1), Btn("back", 4, 2),
        Btn("home", 6, 1), Btn("recents", 7, 2), Btn("qs", 9, 1), Btn("move", 10, 2)
    )

    fun load(p: SharedPreferences): List<Btn> {
        val s = p.getString("layout", null) ?: return defaults()
        var list: List<Btn> = try {
            val a = JSONArray(s)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Btn(o.getString("t"), o.getInt("s"), o.getInt("n"))
            }
        } catch (e: Exception) {
            defaults()
        }
        // one-time: older saved layouts get the new window button in the free top slot
        if (!p.getBoolean("win_added", false)) {
            p.edit().putBoolean("win_added", true).apply()
            if (list.none { it.type == "window" } && list.none { it.covers(0) }) {
                list = list + Btn("window", 0, 1)
                save(p, list)
            }
        }
        return list
    }

    fun save(p: SharedPreferences, list: List<Btn>) {
        val a = JSONArray()
        for (b in list) a.put(JSONObject().put("t", b.type).put("s", b.start).put("n", b.span))
        p.edit().putString("layout", a.toString()).apply()
    }

    /** Which of the 12 slots a point (relative to the pad centre) falls in. Slot 0 is centred on the top. */
    fun slotAt(dx: Float, dy: Float): Int {
        val ang = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        val rel = (ang + 90f + 15f + 720f) % 360f
        return (rel / 30f).toInt() % SLOTS
    }
}

/** Draws the round pad. Shared by the live pad and the layout editor. */
class RingPainter(private val density: Float) {
    private fun dp(v: Float) = v * density
    private val sand = Color.parseColor("#f0e0d0")
    private val sand2 = Color.parseColor("#e7ceb9")
    private val sand3 = Color.parseColor("#e1be9b")
    private val clay = Color.parseColor("#d69264")
    private val rust = Color.parseColor("#c3592b")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; pathEffect = CornerPathEffect(dp(5f)) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.2f); color = clay }
    private val sel = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f); color = rust }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val outer = RectF()
    private val inner = RectF()
    private val path = Path()
    private val track = Path()

    private fun wedge(start: Int, span: Int) {
        val a0 = -90f + 30f * start - 15f + 1.5f
        val sweep = 30f * span - 3f
        path.reset()
        path.arcTo(outer, a0, sweep)
        path.arcTo(inner, a0 + sweep, -sweep)
        path.close()
    }

    fun draw(c: Canvas, w: Int, h: Int, btns: List<Btn>, pressed: Btn?, dragOn: Boolean, selSlot: Int, editor: Boolean) {
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f - dp(1.5f)
        fill.color = sand
        c.drawCircle(cx, cy, r, fill)
        c.drawCircle(cx, cy, r, line)
        val rOut = r - dp(5f)
        val rIn = r * 0.62f
        outer.set(cx - rOut, cy - rOut, cx + rOut, cy + rOut)
        inner.set(cx - rIn, cy - rIn, cx + rIn, cy + rIn)

        track.reset()
        track.addCircle(cx, cy, rOut, Path.Direction.CW)
        track.addCircle(cx, cy, rIn, Path.Direction.CCW)
        fill.color = sand2
        c.drawPath(track, fill)

        if (editor) {
            for (k in 0 until Ring.SLOTS) {
                val a = Math.toRadians(-90.0 + 30.0 * k - 15.0)
                c.drawLine(
                    cx + (rIn * cos(a)).toFloat(), cy + (rIn * sin(a)).toFloat(),
                    cx + (rOut * cos(a)).toFloat(), cy + (rOut * sin(a)).toFloat(), line
                )
            }
        }

        txt.textSize = r * 0.2f
        for (b in btns) {
            wedge(b.start, b.span)
            val hot = b.type == "home" || (b.type == "drag" && dragOn)
            fill.color = if (b == pressed) clay else if (hot) rust else sand3
            c.drawPath(path, fill)
            if (editor && selSlot >= 0 && b.covers(selSlot)) c.drawPath(path, sel)
            val am = Math.toRadians(-90.0 + 30.0 * (b.start + (b.span - 1) / 2.0))
            val rm = (rOut + rIn) / 2f
            txt.color = if (hot) Color.WHITE else Color.BLACK
            val tx = cx + (rm * cos(am)).toFloat()
            val ty = cy + (rm * sin(am)).toFloat() - (txt.ascent() + txt.descent()) / 2f
            c.drawText(Ring.label(b.type), tx, ty, txt)
        }
        if (editor && selSlot >= 0 && btns.none { it.covers(selSlot) }) {
            wedge(selSlot, 1)
            c.drawPath(path, sel)
        }

        fill.color = sand2
        c.drawCircle(cx, cy, rIn - dp(6f), fill)
        c.drawCircle(cx, cy, rIn - dp(6f), line)
    }
}
