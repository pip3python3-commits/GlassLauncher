package com.example.glasslauncher

import android.content.ComponentName
import android.content.pm.LauncherActivityInfo
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF

class AppInfo(val info: LauncherActivityInfo) {
    val label: String = info.label.toString()
    val component: ComponentName = info.componentName
    var icon: Bitmap? = null
}

object IconRenderer {
    /** Draws an app icon into a rounded square whose roundness comes from the settings. */
    fun render(info: LauncherActivityInfo, sizePx: Int, roundPct: Int, density: Int): Bitmap {
        val d = info.getIcon(density)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (d is AdaptiveIconDrawable) {
            val inset = (sizePx * 0.25f).toInt()
            val l = -inset
            val t = -inset
            val r = sizePx + inset
            val b = sizePx + inset
            d.background?.let {
                it.setBounds(l, t, r, b)
                it.draw(c)
            }
            d.foreground?.let {
                it.setBounds(l, t, r, b)
                it.draw(c)
            }
        } else {
            c.drawColor(Color.WHITE)
            val pad = (sizePx * 0.14f).toInt()
            d.setBounds(pad, pad, sizePx - pad, sizePx - pad)
            d.draw(c)
        }
        // Cut everything outside the rounded square (anti-aliased).
        val radius = sizePx * roundPct / 100f
        val path = Path()
        path.addRoundRect(RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), radius, radius, Path.Direction.CW)
        path.fillType = Path.FillType.INVERSE_WINDING
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        c.drawPath(path, p)
        return bmp
    }
}
