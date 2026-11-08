package com.sandpad.pad

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val sand = Color.parseColor("#f0e0d0")
    private val rust = Color.parseColor("#c3592b")
    private val clay = Color.parseColor("#d69264")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val prefs = getSharedPreferences("sandpad", MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(24)); setBackgroundColor(sand)
        }
        fun text(s: String, size: Float, color: Int = Color.BLACK, bold: Boolean = false) = TextView(this).apply {
            text = s; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(6))
        }
        fun slider(title: String, key: String, min: Int, max: Int, def: Int) {
            val label = text("$title: ${prefs.getInt(key, def)}", 15f)
            val sb = SeekBar(this).apply {
                this.max = max - min; progress = prefs.getInt(key, def) - min
                progressTintList = android.content.res.ColorStateList.valueOf(rust)
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.BLACK)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                        prefs.edit().putInt(key, p + min).apply(); label.text = "$title: ${p + min}"
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            root.addView(label); root.addView(sb)
        }
        root.addView(text("SandPad", 34f, Color.BLACK, true))
        root.addView(text("A floating touchpad for the part of your screen that still works.", 15f, Color.DKGRAY))
        status = text("", 16f, rust, true).apply { setPadding(0, dp(20), 0, dp(10)) }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "Open Accessibility Settings"; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.BLACK) }
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "Customize pad buttons"; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(rust) }
            setOnClickListener { startActivity(Intent(this@MainActivity, EditorActivity::class.java)) }
        })
        root.addView(Button(this).apply {
            text = "Pinned apps"; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.BLACK) }
            setOnClickListener { startActivity(Intent(this@MainActivity, AppsActivity::class.java)) }
        })
        slider("Cursor speed", "speed", 5, 40, 12)
        slider("Pad size (dp)", "size", 160, 300, 230)
        slider("Pad opacity %", "alpha", 30, 100, 95)
        root.addView(text("Setup: Accessibility → Installed apps → SandPad → On.\nIf it is greyed out: App info → ⋮ → Allow restricted settings.\n\nCentre = touchpad: tap clicks, hold long-presses. Ring buttons are fully customizable. Press ✥ then slide to drag or scroll. Drag the empty part of the ring to move the pad. ▦ opens the window menu: split screen and fast launch.", 13f, Color.DKGRAY).apply { setPadding(0, dp(24), 0, 0) })
        setContentView(ScrollView(this).apply { setBackgroundColor(sand); addView(root) })
    }

    override fun onResume() {
        super.onResume()
        val on = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(packageName) == true
        status.text = if (on) "● Active — pad is on screen" else "○ Not enabled yet"
    }
}
