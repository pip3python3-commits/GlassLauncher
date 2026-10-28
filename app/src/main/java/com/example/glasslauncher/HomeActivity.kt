package com.example.glasslauncher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextClock
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.hypot

class HomeActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var launcherApps: LauncherApps
    private lateinit var root: GestureFrame
    private lateinit var homeLayer: LinearLayout
    private lateinit var pager: ViewPager2
    private lateinit var dots: DotsView
    private lateinit var dock: LinearLayout
    private lateinit var scrim: View
    private lateinit var shade: ShadeView
    private lateinit var card: FrameLayout
    private lateinit var cardIcon: ImageView
    private val cardBg = GradientDrawable()

    private var apps: List<AppInfo> = emptyList()
    private var resumed = false
    private var awaitingReturn = false
    private var shadeOpen = false
    private var loading = false
    private var builtSig = ""
    private var depth = 0f
    private var pinch = 1f
    private var iconsHidden = false
    private var unlockTime = 0L
    private var lastBurst = 0L
    private val ui = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        launcherApps = getSystemService(LauncherApps::class.java)!!
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(ColorDrawable(0x01000000))
        buildUi()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (shadeOpen) {
                    if (shade.showingQs) shade.showPage(false) else closeShade()
                }
            }
        })

        val f = IntentFilter()
        f.addAction(Intent.ACTION_USER_PRESENT)
        f.addAction(Intent.ACTION_SCREEN_OFF)
        ContextCompat.registerReceiver(this, screenReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        launcherApps.registerCallback(pkgCallback)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (builtSig != currentSig()) reload()
        if (awaitingReturn) {
            awaitingReturn = false
            playReturn()
        } else if (card.visibility == View.VISIBLE) {
            card.visibility = View.GONE
            setDepth(if (shadeOpen) 1f else 0f)
        }
        if (SystemClock.elapsedRealtime() - unlockTime < 2500L) playBurst()
        if (iconsHidden) {
            ui.postDelayed({ if (iconsHidden) restoreIcons() }, 2500L)
        }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(screenReceiver)
        launcherApps.unregisterCallback(pkgCallback)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!shadeOpen) scaleDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // ------------------------------------------------------------ receivers

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (!shadeOpen) {
                        iconsHidden = true
                        allCells().forEach { it.alpha = 0f }
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    unlockTime = SystemClock.elapsedRealtime()
                    if (resumed) playBurst()
                }
            }
        }
    }

    private val pkgCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(p: String?, u: UserHandle?) {
            reload()
        }

        override fun onPackageAdded(p: String?, u: UserHandle?) {
            reload()
        }

        override fun onPackageChanged(p: String?, u: UserHandle?) {
            reload()
        }

        override fun onPackagesAvailable(p: Array<out String>?, u: UserHandle?, replacing: Boolean) {
            reload()
        }

        override fun onPackagesUnavailable(p: Array<out String>?, u: UserHandle?, replacing: Boolean) {
            reload()
        }
    }

    // ------------------------------------------------------------ UI building

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun glass(color: String, radius: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(Color.parseColor(color))
        g.cornerRadius = dp(radius).toFloat()
        return g
    }

    private fun buildUi() {
        root = GestureFrame(this)
        homeLayer = LinearLayout(this)
        homeLayer.orientation = LinearLayout.VERTICAL
        homeLayer.clipChildren = false
        homeLayer.clipToPadding = false

        // clock widget
        val clockBox = LinearLayout(this)
        clockBox.orientation = LinearLayout.VERTICAL
        clockBox.setPadding(dp(28), dp(16), dp(28), dp(8))
        val time = TextClock(this)
        time.format12Hour = "h:mm"
        time.format24Hour = "H:mm"
        time.setTextColor(Color.WHITE)
        time.textSize = 44f
        time.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        time.setShadowLayer(8f, 0f, 2f, Color.parseColor("#66000000"))
        val date = TextClock(this)
        date.format12Hour = "EEEE, MMMM d"
        date.format24Hour = "EEEE, MMMM d"
        date.setTextColor(Color.WHITE)
        date.textSize = 15f
        date.setShadowLayer(8f, 0f, 2f, Color.parseColor("#66000000"))
        clockBox.addView(time)
        clockBox.addView(date)

        pager = ViewPager2(this)
        pager.clipChildren = false
        val rv = pager.getChildAt(0) as RecyclerView
        rv.clipChildren = false
        rv.clipToPadding = false
        rv.overScrollMode = View.OVER_SCROLL_NEVER

        dots = DotsView(this)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                dots.index = position + positionOffset
            }
        })

        dock = LinearLayout(this)
        dock.orientation = LinearLayout.HORIZONTAL
        dock.gravity = Gravity.CENTER_VERTICAL
        dock.clipChildren = false
        dock.background = glass("#33FFFFFF", 30)
        dock.setPadding(dp(8), dp(10), dp(8), dp(10))

        homeLayer.addView(clockBox, LinearLayout.LayoutParams(MATCH, WRAP))
        homeLayer.addView(pager, LinearLayout.LayoutParams(MATCH, 0, 1f))
        homeLayer.addView(dots, LinearLayout.LayoutParams(MATCH, dp(14)))
        val dockLp = LinearLayout.LayoutParams(MATCH, WRAP)
        dockLp.setMargins(dp(14), dp(6), dp(14), dp(10))
        homeLayer.addView(dock, dockLp)

        scrim = View(this)
        scrim.setBackgroundColor(Color.BLACK)
        scrim.alpha = 0f

        shade = ShadeView(this, prefs, object : ShadeView.Host {
            override fun close() {
                closeShade()
            }

            override fun settings() {
                closeShade()
                startActivity(Intent(this@HomeActivity, SettingsActivity::class.java))
            }
        })

        card = FrameLayout(this)
        card.background = cardBg
        card.visibility = View.GONE
        cardIcon = ImageView(this)
        card.addView(cardIcon, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))

        root.addView(homeLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(shade, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(card, FrameLayout.LayoutParams(1, 1))
        root.onSwipeDown = { openShade() }
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            homeLayer.setPadding(0, b.top, 0, b.bottom)
            shade.setInsets(b.top, b.bottom)
            insets
        }
    }

    // ------------------------------------------------------------ apps

    private fun currentSig(): String =
        listOf("iconSize", "round", "cols", "rows").joinToString(",") { prefs.get(it).toString() }

    private fun reload() {
        if (loading) return
        loading = true
        val sig = currentSig()
        val px = dp(prefs.get("iconSize"))
        val round = prefs.get("round")
        val dens = resources.displayMetrics.densityDpi
        Thread {
            try {
                val list = launcherApps.getActivityList(null, Process.myUserHandle())
                    .filter { it.componentName.packageName != packageName }
                    .map { AppInfo(it) }
                    .sortedBy { it.label.lowercase() }
                for (a in list) {
                    a.icon = IconRenderer.render(a.info, px, round, dens)
                }
                runOnUiThread {
                    apps = list
                    builtSig = sig
                    layoutHome()
                    loading = false
                }
            } catch (e: Exception) {
                runOnUiThread { loading = false }
            }
        }.start()
    }

    private fun pickDock(all: List<AppInfo>): List<AppInfo> {
        val groups = listOf(
            listOf("com.samsung.android.dialer", "com.google.android.dialer", "com.android.dialer"),
            listOf("com.samsung.android.messaging", "com.google.android.apps.messaging"),
            listOf("com.sec.android.app.sbrowser", "com.android.chrome"),
            listOf("com.sec.android.app.camera", "com.android.camera2")
        )
        val out = ArrayList<AppInfo>()
        for (g in groups) {
            val hit = all.firstOrNull { a -> a.component.packageName in g && out.none { it === a } }
            if (hit != null) out.add(hit)
        }
        for (a in all) {
            if (out.size >= 4) break
            if (out.none { it === a }) out.add(a)
        }
        return out
    }

    private fun layoutHome() {
        val dockApps = pickDock(apps)
        val rest = apps.filter { a -> dockApps.none { it === a } }
        val per = prefs.get("cols") * prefs.get("rows")
        val pages = rest.chunked(per)
        pager.adapter = PagesAdapter(pages)
        dots.count = pages.size
        dots.index = 0f
        dock.removeAllViews()
        for (a in dockApps) {
            dock.addView(makeCell(a, false), LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        if (iconsHidden) allCells().forEach { it.alpha = 0f }
    }

    private inner class PagesAdapter(val pages: List<List<AppInfo>>) : RecyclerView.Adapter<PageHolder>() {
        override fun getItemCount(): Int = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val g = GridPage(this@HomeActivity, prefs.get("cols"), prefs.get("rows"))
            g.clipChildren = false
            g.layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.MATCH_PARENT
            )
            return PageHolder(g)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val g = holder.itemView as GridPage
            g.removeAllViews()
            for (a in pages[position]) g.addView(makeCell(a, true))
        }
    }

    private class PageHolder(v: View) : RecyclerView.ViewHolder(v)

    private fun makeCell(app: AppInfo, label: Boolean): View {
        val sz = dp(prefs.get("iconSize"))
        val cell = LinearLayout(this)
        cell.orientation = LinearLayout.VERTICAL
        cell.gravity = Gravity.CENTER
        cell.clipChildren = false
        val iv = ImageView(this)
        iv.setImageBitmap(app.icon)
        cell.addView(iv, LinearLayout.LayoutParams(sz, sz))
        if (label) {
            val tv = TextView(this)
            tv.text = app.label
            tv.setTextColor(Color.WHITE)
            tv.textSize = 11f
            tv.maxLines = 1
            tv.ellipsize = TextUtils.TruncateAt.END
            tv.gravity = Gravity.CENTER
            tv.setShadowLayer(6f, 0f, 1f, Color.parseColor("#99000000"))
            tv.setPadding(dp(2), dp(5), dp(2), 0)
            cell.addView(tv, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        cell.setOnClickListener { launch(app, iv) }
        cell.setOnLongClickListener {
            showMenu(cell, app)
            true
        }
        return cell
    }

    private fun showMenu(anchor: View, app: AppInfo) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "App info")
        m.menu.add(0, 2, 1, "Uninstall")
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> launcherApps.startAppDetailsActivity(app.component, app.info.user, null, null)
                2 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.component.packageName)))
            }
            true
        }
        m.show()
    }

    private fun allCells(): List<View> {
        val out = ArrayList<View>()
        if (this::pager.isInitialized) {
            val rv = pager.getChildAt(0) as RecyclerView
            val page = rv.findViewHolderForAdapterPosition(pager.currentItem)?.itemView as? ViewGroup
            if (page != null) {
                for (i in 0 until page.childCount) out.add(page.getChildAt(i))
            }
            for (i in 0 until dock.childCount) out.add(dock.getChildAt(i))
        }
        return out
    }

    // ------------------------------------------------------------ depth (blur + dim)

    /** t = 0: home is sharp. t = 1: home is fully blurred and dimmed. */
    private fun setDepth(t: Float) {
        depth = t
        homeLayer.setBlur(prefs.blurPx() * t)
        scrim.alpha = 0.35f * t
        try {
            if (windowManager.isCrossWindowBlurEnabled) {
                window.setBackgroundBlurRadius((prefs.blurPx() * 1.5f * t).toInt())
            }
        } catch (e: Exception) {
        }
    }

    private fun animateDepth(to: Float, ms: Long, end: (() -> Unit)? = null) {
        val from = depth
        val a = ValueAnimator.ofFloat(from, to)
        a.duration = ms
        a.addUpdateListener { setDepth(it.animatedValue as Float) }
        if (end != null) {
            a.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    end()
                }
            })
        }
        a.start()
    }

    // ------------------------------------------------------------ shade

    private fun openShade() {
        if (shadeOpen) return
        shadeOpen = true
        root.swipeEnabled = false
        animateDepth(1f, prefs.ms(280))
        shade.show()
    }

    private fun closeShade() {
        if (!shadeOpen) return
        shadeOpen = false
        animateDepth(0f, prefs.ms(260))
        shade.hide { root.swipeEnabled = true }
    }

    // ------------------------------------------------------------ app open / return

    private fun averageColor(b: Bitmap?): Int {
        if (b == null) return Color.DKGRAY
        val s = Bitmap.createScaledBitmap(b, 1, 1, true)
        val c = s.getPixel(0, 0)
        return Color.rgb(Color.red(c), Color.green(c), Color.blue(c))
    }

    private fun launch(app: AppInfo, iv: View) {
        val loc = IntArray(2)
        iv.getLocationInWindow(loc)
        val rect = Rect(loc[0], loc[1], loc[0] + iv.width, loc[1] + iv.height)
        playOpen(app, rect)
        val opts = ActivityOptions.makeCustomAnimation(this, R.anim.app_in, R.anim.stay).toBundle()
        awaitingReturn = true
        try {
            launcherApps.startMainActivity(app.component, app.info.user, rect, opts)
        } catch (e: Exception) {
            awaitingReturn = false
            fadeOutCard()
            animateDepth(0f, prefs.ms(200))
        }
    }

    private fun playOpen(app: AppInfo, r: Rect) {
        val w = root.width.toFloat()
        val h = root.height.toFloat()
        val startR = r.width() * prefs.get("round") / 100f
        val endR = dp(30).toFloat()
        cardBg.setColor(averageColor(app.icon))
        cardIcon.setImageBitmap(app.icon)
        card.visibility = View.VISIBLE
        card.alpha = 1f
        val lp = card.layoutParams as FrameLayout.LayoutParams

        fun frameAt(t: Float) {
            lp.width = (r.width() + (w - r.width()) * t).toInt()
            lp.height = (r.height() + (h - r.height()) * t).toInt()
            card.layoutParams = lp
            card.translationX = r.left * (1f - t)
            card.translationY = r.top * (1f - t)
            cardBg.cornerRadius = startR + (endR - startR) * t
            // slight perspective tilt so the card is narrower at the bottom, like a funnel
            card.rotationX = -9f * (1f - t)
            cardIcon.alpha = (1f - t * 1.8f).coerceAtLeast(0f)
            cardIcon.scaleX = 1f + 0.5f * t
            cardIcon.scaleY = 1f + 0.5f * t
            setDepth(0.75f * t)
        }

        frameAt(0f)
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = prefs.ms(340)
        a.interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
        a.addUpdateListener { frameAt(it.animatedValue as Float) }
        a.start()

        // If the app never came up (launch failed), drop the card again.
        ui.postDelayed({
            if (resumed && card.visibility == View.VISIBLE) {
                awaitingReturn = false
                fadeOutCard()
                animateDepth(0f, prefs.ms(220))
            }
        }, 1600L)
    }

    private fun fadeOutCard() {
        card.animate().alpha(0f).setDuration(prefs.ms(180)).withEndAction {
            card.visibility = View.GONE
            card.rotationX = 0f
        }.start()
    }

    /** Back on the home screen after an app: blur clears, grid settles, icons pop. */
    private fun playReturn() {
        fadeOutCard()
        animateDepth(0f, prefs.ms(320))
        homeLayer.scaleX = 1.05f
        homeLayer.scaleY = 1.05f
        homeLayer.spring(DynamicAnimation.SCALE_X, 1f, prefs)
        homeLayer.spring(DynamicAnimation.SCALE_Y, 1f, prefs)
        var i = 0
        for (c in allCells()) {
            c.animate().cancel()
            c.scaleX = 0.85f
            c.scaleY = 0.85f
            c.alpha = 0.5f
            val d = (i % 8) * 14L
            c.spring(DynamicAnimation.SCALE_X, 1f, prefs, d)
            c.spring(DynamicAnimation.SCALE_Y, 1f, prefs, d)
            c.animate().alpha(1f).setStartDelay(d).setDuration(prefs.ms(200)).start()
            i++
        }
    }

    // ------------------------------------------------------------ unlock burst

    private fun restoreIcons() {
        iconsHidden = false
        for (c in allCells()) c.animate().alpha(1f).setDuration(200).start()
    }

    /** Icons fly out from the bottom centre into place, nearest first. */
    private fun playBurst() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBurst < 1000L) return
        lastBurst = now
        iconsHidden = false
        val cells = allCells()
        if (cells.isEmpty()) return
        val w = root.width.toFloat()
        val h = root.height.toFloat()
        val ox = w / 2f
        val oy = h - dp(90).toFloat()
        val loc = IntArray(2)
        setDepth(0.6f)
        animateDepth(0f, prefs.ms(600))
        for (c in cells) {
            c.getLocationInWindow(loc)
            val cx = loc[0] + c.width / 2f - c.translationX
            val cy = loc[1] + c.height / 2f - c.translationY
            val dist = hypot(cx - ox, cy - oy)
            val delay = (dist / h * prefs.ms(380)).toLong()
            c.animate().cancel()
            c.translationX = ox - cx
            c.translationY = oy - cy
            c.scaleX = 0.2f
            c.scaleY = 0.2f
            c.alpha = 0f
            c.spring(DynamicAnimation.TRANSLATION_X, 0f, prefs, delay)
            c.spring(DynamicAnimation.TRANSLATION_Y, 0f, prefs, delay)
            c.spring(DynamicAnimation.SCALE_X, 1f, prefs, delay)
            c.spring(DynamicAnimation.SCALE_Y, 1f, prefs, delay)
            c.animate().alpha(1f).setStartDelay(delay).setDuration(prefs.ms(140)).start()
        }
    }

    // ------------------------------------------------------------ pinch

    private val pinchListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            pinch = 1f
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pinch = (pinch * detector.scaleFactor).coerceIn(0.82f, 1f)
            homeLayer.scaleX = pinch
            homeLayer.scaleY = pinch
            setDepth((1f - pinch) / 0.18f * 0.6f)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            val openSettings = pinch < 0.9f
            homeLayer.spring(DynamicAnimation.SCALE_X, 1f, prefs)
            homeLayer.spring(DynamicAnimation.SCALE_Y, 1f, prefs)
            animateDepth(0f, prefs.ms(260))
            pinch = 1f
            if (openSettings) {
                startActivity(Intent(this@HomeActivity, SettingsActivity::class.java))
            }
        }
    }

    private val scaleDetector by lazy { ScaleGestureDetector(this, pinchListener) }
}
