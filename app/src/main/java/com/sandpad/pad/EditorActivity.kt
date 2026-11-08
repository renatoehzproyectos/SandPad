package com.sandpad.pad

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.hypot
import kotlin.math.min

class RingEditorView(ctx: Context, private val onSlot: (Int) -> Unit) : View(ctx) {
    private val painter = RingPainter(ctx.resources.displayMetrics.density)
    private var btns: List<Btn> = emptyList()
    private var sel = -1

    fun setState(b: List<Btn>, s: Int) { btns = b; sel = s; invalidate() }

    override fun onDraw(c: Canvas) { painter.draw(c, width, height, btns, null, false, sel, true) }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val dx = e.x - width / 2f
            val dy = e.y - height / 2f
            val d = hypot(dx, dy)
            val r = min(width, height) / 2f
            if (d > r * 0.62f && d <= r) onSlot(Ring.slotAt(dx, dy))
        }
        return true
    }
}

class EditorActivity : Activity() {
    private val sand = Color.parseColor("#f0e0d0")
    private lateinit var prefs: SharedPreferences
    private lateinit var ringView: RingEditorView
    private lateinit var controls: LinearLayout
    private lateinit var note: TextView
    private var btns = mutableListOf<Btn>()
    private var selSlot = -1

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("sandpad", MODE_PRIVATE)
        btns = Ring.load(prefs).toMutableList()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            setBackgroundColor(sand)
        }
        root.addView(TextView(this).apply {
            text = "Customize pad buttons"; textSize = 22f; setTextColor(Color.BLACK)
            setTypeface(typeface, Typeface.BOLD)
        })
        note = TextView(this).apply { textSize = 13f; setTextColor(Color.DKGRAY); setPadding(0, dp(4), 0, dp(4)) }
        root.addView(note)
        ringView = RingEditorView(this) { slot -> selSlot = slot; refresh() }
        root.addView(ringView, LinearLayout.LayoutParams(dp(230), dp(230)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
        root.addView(controls)
        setContentView(ScrollView(this).apply { setBackgroundColor(sand); addView(root) })
        refresh()
    }

    private fun chip(label: String, onTap: () -> Unit): TextView = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = 13f; setTextColor(sand)
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.BLACK) }
        setPadding(dp(6), dp(12), dp(6), dp(12))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(3), dp(3), dp(3), dp(3))
        }
        setOnClickListener { onTap() }
    }

    private fun row(vararg v: View): LinearLayout = LinearLayout(this).apply { for (x in v) addView(x) }

    private fun free(n: Btn, except: Btn?): Boolean =
        (0 until n.span).none { i -> btns.any { it != except && it.covers((n.start + i) % Ring.SLOTS) } }

    private fun commit() { Ring.save(prefs, btns); refresh() }

    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    private fun refresh() {
        val cur = if (selSlot >= 0) btns.firstOrNull { it.covers(selSlot) } else null
        ringView.setState(btns, selSlot)
        controls.removeAllViews()
        if (selSlot < 0) {
            note.text = "Tap a slot on the ring (12 slots)."
        } else if (cur == null) {
            note.text = "Empty slot ${selSlot + 1}. Pick a button for it:"
            val unused = Ring.TYPES.filter { t -> btns.none { it.type == t } }
            for (group in unused.chunked(3)) {
                val chips = group.map { t -> chip(Ring.label(t) + " " + Ring.name(t)) { place(t) } }
                controls.addView(row(*chips.toTypedArray()))
            }
        } else {
            note.text = "${Ring.name(cur.type)} · ${cur.span} slot(s) wide"
            controls.addView(row(chip("◀ Shift") { shift(cur, -1) }, chip("Shift ▶") { shift(cur, 1) }))
            controls.addView(row(chip("− Smaller") { resize(cur, -1) }, chip("+ Bigger") { resize(cur, 1) }))
            controls.addView(row(chip("Remove") { btns.remove(cur); commit() }))
        }
        controls.addView(row(
            chip("Reset to default") { btns = Ring.defaults().toMutableList(); selSlot = -1; commit() },
            chip("Done") { finish() }
        ))
    }

    private fun place(t: String) {
        btns.add(Btn(t, selSlot, 1))
        commit()
    }

    private fun shift(b: Btn, dir: Int) {
        val n = b.copy(start = (b.start + dir + Ring.SLOTS) % Ring.SLOTS)
        if (free(n, b)) {
            btns[btns.indexOf(b)] = n; selSlot = n.start; commit()
        } else toast("That slot is taken")
    }

    private fun resize(b: Btn, d: Int) {
        val i = btns.indexOf(b)
        if (d < 0) {
            if (b.span > 1) { btns[i] = b.copy(span = b.span - 1); selSlot = b.start; commit() }
            return
        }
        if (b.span >= 8) { toast("That's the maximum size"); return }
        val right = b.copy(span = b.span + 1)
        val left = b.copy(start = (b.start + Ring.SLOTS - 1) % Ring.SLOTS, span = b.span + 1)
        val n = if (free(right, b)) right else if (free(left, b)) left else null
        if (n == null) { toast("No room. Free a neighbouring slot first"); return }
        btns[i] = n; selSlot = n.start; commit()
    }
}
