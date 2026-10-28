package com.example.glasslauncher

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val prefs = Prefs(this)
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(24), dp(20), dp(40))

        val title = TextView(this)
        title.text = "Launcher settings"
        title.textSize = 24f
        title.setTextColor(Color.WHITE)
        title.setTypeface(title.typeface, Typeface.BOLD)
        col.addView(title)

        val note = TextView(this)
        note.text = "Grid, icon size and roundness apply when you return to the home screen."
        note.textSize = 13f
        note.setTextColor(Color.parseColor("#AAFFFFFF"))
        note.setPadding(0, dp(6), 0, 0)
        col.addView(note)

        for (s in Prefs.ALL) {
            val label = TextView(this)
            label.setTextColor(Color.WHITE)
            label.textSize = 15f
            label.setPadding(0, dp(22), 0, 0)
            label.text = s.label + ": " + prefs.get(s.key) + s.unit

            val bar = SeekBar(this)
            bar.max = s.max - s.min
            bar.progress = prefs.get(s.key) - s.min
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p + s.min
                    prefs.put(s.key, v)
                    label.text = s.label + ": " + v + s.unit
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}

                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            col.addView(label)
            col.addView(bar)
        }

        fun button(text: String, onClick: () -> Unit) {
            val b = Button(this)
            b.text = text
            b.setOnClickListener { onClick() }
            val p = LinearLayout.LayoutParams(MATCH, WRAP)
            p.topMargin = dp(16)
            col.addView(b, p)
        }
        button("Set as default home app") { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        button("Allow notification access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        button("Reset to defaults") {
            prefs.reset()
            recreate()
        }

        val scroll = ScrollView(this)
        scroll.addView(col)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, b.top, 0, b.bottom)
            insets
        }
        setContentView(scroll)
    }
}
