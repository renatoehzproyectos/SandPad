package com.sandpad.pad

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View

/** Plain full #e7ceb9 screen that fills the lower half of split screen. */
class SplitActivity : Activity() {
    companion object { var instance: SplitActivity? = null }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        instance = this
        val c = Color.parseColor("#e7ceb9")
        window.setBackgroundDrawable(ColorDrawable(c))
        setContentView(View(this).apply { setBackgroundColor(c) })
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }
}
