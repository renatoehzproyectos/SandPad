package com.sandpad.pad

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.widget.Toast

/** Plain full #e7ceb9 screen for the lower half of split screen. Closes itself if split screen did not actually start. */
class SplitActivity : Activity() {
    companion object { var instance: SplitActivity? = null }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        instance = this
        val c = Color.parseColor("#e7ceb9")
        window.setBackgroundDrawable(ColorDrawable(c))
        setContentView(View(this).apply { setBackgroundColor(c) })
    }

    override fun onResume() {
        super.onResume()
        window.decorView.postDelayed({
            if (!isFinishing && !isInMultiWindowMode) {
                Toast.makeText(this, "Split screen didn't start on this phone", Toast.LENGTH_LONG).show()
                finish()
            }
        }, 700)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }
}
