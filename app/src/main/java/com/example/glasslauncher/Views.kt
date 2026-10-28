package com.example.glasslauncher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.min

/** Root layout: catches a swipe-down anywhere on the home screen. */
class GestureFrame(ctx: Context) : FrameLayout(ctx) {
    var swipeEnabled = true
    var onSwipeDown: (() -> Unit)? = null
    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (!swipeEnabled) return false
        if (e.pointerCount > 1) {
            tracking = false
            return false
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                tracking = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (tracking) {
                    val dy = e.y - downY
                    val dx = e.x - downX
                    if (dy > slop * 2 && dy > abs(dx) * 1.5f) {
                        tracking = false
                        onSwipeDown?.invoke()
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracking = false
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = true
}

/** One home-screen page: lays children out in an even cols x rows grid. */
class GridPage(ctx: Context, private val cols: Int, private val rows: Int) : ViewGroup(ctx) {

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val h = MeasureSpec.getSize(heightSpec)
        setMeasuredDimension(w, h)
        val cw = w / cols
        val ch = h / rows
        for (i in 0 until childCount) {
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = (r - l) / cols
        val ch = (b - t) / rows
        for (i in 0 until childCount) {
            val x = (i % cols) * cw
            val y = (i / cols) * ch
            getChildAt(i).layout(x, y, x + cw, y + ch)
        }
    }
}

class DotsView(ctx: Context) : View(ctx) {
    var count = 0
        set(v) {
            field = v
            invalidate()
        }
    var index = 0f
        set(v) {
            field = v
            invalidate()
        }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        if (count < 2) return
        val r = height * 0.18f
        val gap = r * 4.5f
        val start = width / 2f - (count - 1) * gap / 2f
        for (i in 0 until count) {
            val a = 1f - min(1f, abs(i - index))
            p.color = Color.WHITE
            p.alpha = (110 + 145 * a).toInt()
            c.drawCircle(start + i * gap, height / 2f, r * (1f + 0.35f * a), p)
        }
    }
}

/** Pill-shaped slider like the brightness / volume bars in the control panel. */
class PillSlider(ctx: Context, iconRes: Int) : View(ctx) {
    var onChange: ((Float) -> Unit)? = null
    var progress = 0.5f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = ctx.getDrawable(iconRes)?.mutate()
    private val clip = Path()
    private val rect = RectF()

    init {
        track.color = Color.parseColor("#44FFFFFF")
        fill.color = Color.parseColor("#F2FFFFFF")
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        rect.set(0f, 0f, w, h)
        c.drawRoundRect(rect, r, r, track)
        c.save()
        clip.reset()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
        c.clipPath(clip)
        c.drawRect(0f, 0f, w * progress, h, fill)
        c.restore()
        icon?.let {
            val s = (h * 0.45f).toInt()
            val left = (h * 0.32f).toInt()
            val top = ((h - s) / 2f).toInt()
            it.setBounds(left, top, left + s, top + s)
            it.setTint(if (w * progress > left + s) Color.parseColor("#1B1B1F") else Color.WHITE)
            it.draw(c)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                update(e.x)
            }
            MotionEvent.ACTION_MOVE -> update(e.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun update(x: Float) {
        progress = x / width
        onChange?.invoke(progress)
    }
}

/** Round glass button with an icon. White when active. */
class Toggle(ctx: Context, icon: Int, private val sizeDp: Int) : FrameLayout(ctx) {
    private val bg = GradientDrawable()
    private val iv = ImageView(ctx)
    var active = false
        set(v) {
            field = v
            refresh()
        }

    init {
        bg.shape = GradientDrawable.OVAL
        background = bg
        iv.setImageResource(icon)
        val s = (sizeDp * 0.5f * resources.displayMetrics.density).toInt()
        addView(iv, FrameLayout.LayoutParams(s, s, Gravity.CENTER))
        refresh()
    }

    private fun refresh() {
        bg.setColor(Color.parseColor(if (active) "#F2FFFFFF" else "#44000000"))
        iv.imageTintList = ColorStateList.valueOf(Color.parseColor(if (active) "#1B1B1F" else "#FFFFFF"))
    }
}
