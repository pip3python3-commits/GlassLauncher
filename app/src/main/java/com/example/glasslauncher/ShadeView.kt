package com.example.glasslauncher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import kotlin.math.abs

/**
 * The pull-down panel. Page 1: quick toggles, brightness, real notifications.
 * Page 2: full quick-settings tiles. Swipe down on page 1 to reach page 2.
 */
class ShadeView(ctx: Context, private val prefs: Prefs, private val host: Host) : FrameLayout(ctx) {

    interface Host {
        fun close()
        fun settings()
    }

    var showingQs = false
        private set

    private val density = ctx.resources.displayMetrics.density
    private val notifPage = FrameLayout(ctx)
    private val qsPage = FrameLayout(ctx)
    private val notifList = LinearLayout(ctx)
    private val notifScroll = ScrollView(ctx)
    private val notifItems = ArrayList<Pair<View, Int>>()
    private val qsItems = ArrayList<Pair<View, Int>>()
    private val torchToggles = ArrayList<Toggle>()
    private val brightSliders = ArrayList<PillSlider>()
    private val volSliders = ArrayList<PillSlider>()
    private val mediaTitle = TextView(ctx)
    private lateinit var mediaPlay: ImageView
    private var torchOn = false
    private var torchId: String? = null
    private var swapAnim: ValueAnimator? = null
    private var askedWrite = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var inList = false
    private val cam: CameraManager? = ctx.getSystemService(CameraManager::class.java)
    private val audio: AudioManager = ctx.getSystemService(AudioManager::class.java)!!

    init {
        visibility = View.GONE
        addView(notifPage, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(qsPage, FrameLayout.LayoutParams(MATCH, MATCH))
        qsPage.visibility = View.GONE
        notifList.orientation = LinearLayout.VERTICAL
        buildNotifPage()
        buildQsPage()
        setupTorch()
    }

    // ---------- small helpers ----------

    private fun dp(v: Int): Int = (v * density).toInt()

    private fun glass(color: String, radius: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(Color.parseColor(color))
        g.cornerRadius = dp(radius).toFloat()
        return g
    }

    /** Layout params for a LinearLayout child. w/h in px (or MATCH/WRAP), margins in dp. */
    private fun lp(
        w: Int, h: Int, weight: Float = 0f,
        l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0
    ): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h, weight)
        p.setMargins(dp(l), dp(t), dp(r), dp(b))
        return p
    }

    fun setInsets(top: Int, bottom: Int) {
        setPadding(dp(16), top + dp(8), dp(16), bottom + dp(8))
    }

    private fun startSafe(i: Intent) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            host.close()
        } catch (e: Exception) {
        }
    }

    // ---------- page building ----------

    private fun headerRow(): LinearLayout {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val time = TextClock(context)
        time.format12Hour = "h:mm"
        time.format24Hour = "H:mm"
        time.setTextColor(Color.WHITE)
        time.textSize = 24f
        time.setTypeface(time.typeface, Typeface.BOLD)
        val date = TextClock(context)
        date.format12Hour = "EEE, MMM d"
        date.format24Hour = "EEE, MMM d"
        date.setTextColor(Color.parseColor("#DDFFFFFF"))
        date.textSize = 13f
        date.setPadding(dp(10), dp(6), 0, 0)
        row.addView(time, LinearLayout.LayoutParams(WRAP, WRAP))
        row.addView(date, LinearLayout.LayoutParams(0, WRAP, 1f))
        val gear = ImageView(context)
        gear.setImageResource(R.drawable.ic_settings)
        gear.imageTintList = ColorStateList.valueOf(Color.WHITE)
        gear.setPadding(dp(8), dp(8), dp(8), dp(8))
        gear.setOnClickListener { host.settings() }
        row.addView(gear, LinearLayout.LayoutParams(dp(40), dp(40)))
        return row
    }

    private fun toggleCell(res: Int, size: Int, torch: Boolean = false, onClick: () -> Unit): FrameLayout {
        val cell = FrameLayout(context)
        cell.setPadding(0, dp(4), 0, dp(4))
        val t = Toggle(context, res, size)
        if (torch) {
            torchToggles.add(t)
            t.active = torchOn
        }
        cell.addView(t, FrameLayout.LayoutParams(dp(size), dp(size), Gravity.CENTER))
        cell.setOnClickListener { onClick() }
        return cell
    }

    private fun pill(text: String, onClick: () -> Unit): TextView {
        val tv = TextView(context)
        tv.text = text
        tv.setTextColor(Color.WHITE)
        tv.textSize = 13f
        tv.gravity = Gravity.CENTER
        tv.background = glass("#55000000", 22)
        tv.setOnClickListener { onClick() }
        return tv
    }

    private fun wideTile(res: Int, title: String, sub: String?, onClick: () -> Unit): LinearLayout {
        val t = LinearLayout(context)
        t.orientation = LinearLayout.HORIZONTAL
        t.gravity = Gravity.CENTER_VERTICAL
        t.background = glass("#44FFFFFF", 28)
        t.setPadding(dp(12), 0, dp(12), 0)
        t.setOnClickListener { onClick() }
        val ic = Toggle(context, res, 40)
        ic.active = true
        t.addView(ic, LinearLayout.LayoutParams(dp(40), dp(40)))
        val tx = LinearLayout(context)
        tx.orientation = LinearLayout.VERTICAL
        tx.setPadding(dp(10), 0, 0, 0)
        val a = TextView(context)
        a.text = title
        a.setTextColor(Color.WHITE)
        a.textSize = 14f
        a.maxLines = 1
        a.setTypeface(a.typeface, Typeface.BOLD)
        tx.addView(a)
        if (sub != null) {
            val b = TextView(context)
            b.text = sub
            b.setTextColor(Color.parseColor("#BBFFFFFF"))
            b.textSize = 11f
            b.maxLines = 1
            tx.addView(b)
        }
        t.addView(tx, LinearLayout.LayoutParams(0, WRAP, 1f))
        return t
    }

    private fun buildNotifPage() {
        val col = LinearLayout(context)
        col.orientation = LinearLayout.VERTICAL
        notifPage.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        val header = headerRow()
        col.addView(header, lp(MATCH, WRAP, t = 4, b = 8))
        notifItems.add(Pair(header, -1))

        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(toggleCell(R.drawable.ic_wifi, 44) {
            startSafe(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
        }, lp(0, WRAP, 1f))
        row.addView(toggleCell(R.drawable.ic_bluetooth, 44) {
            startSafe(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }, lp(0, WRAP, 1f))
        row.addView(toggleCell(R.drawable.ic_volume_up, 44) {
            startSafe(Intent(Settings.ACTION_SOUND_SETTINGS))
        }, lp(0, WRAP, 1f))
        row.addView(toggleCell(R.drawable.ic_flashlight_on, 44, true) { toggleTorch() }, lp(0, WRAP, 1f))
        row.addView(toggleCell(R.drawable.ic_screen_rotation, 44) {
            startSafe(Intent(Settings.ACTION_DISPLAY_SETTINGS))
        }, lp(0, WRAP, 1f))
        row.addView(toggleCell(R.drawable.ic_airplanemode_active, 44) {
            startSafe(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
        }, lp(0, WRAP, 1f))
        col.addView(row, lp(MATCH, WRAP))
        notifItems.add(Pair(row, -1))

        val bright = brightnessSlider()
        col.addView(bright, lp(MATCH, dp(48), t = 10))
        notifItems.add(Pair(bright, 1))

        notifScroll.addView(notifList, FrameLayout.LayoutParams(MATCH, WRAP))
        notifScroll.overScrollMode = View.OVER_SCROLL_NEVER
        col.addView(notifScroll, lp(MATCH, 0, 1f, t = 12))
        notifItems.add(Pair(notifScroll, 0))

        val bottom = LinearLayout(context)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.addView(pill("Notification settings") {
            startSafe(Intent("android.settings.NOTIFICATION_SETTINGS"))
        }, lp(0, dp(44), 1f, r = 6))
        bottom.addView(pill("Clear") {
            NotifService.instance?.cancelAllNotifications()
            refreshNotifs()
        }, lp(dp(110), dp(44), l = 6))
        col.addView(bottom, lp(MATCH, WRAP, t = 10))
        notifItems.add(Pair(bottom, 1))
    }

    private fun buildQsPage() {
        val col = LinearLayout(context)
        col.orientation = LinearLayout.VERTICAL
        qsPage.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        val header = headerRow()
        col.addView(header, lp(MATCH, WRAP, t = 4, b = 8))
        qsItems.add(Pair(header, -1))

        // Wi-Fi + Bluetooth tiles on the left, media card on the right
        val top = LinearLayout(context)
        top.orientation = LinearLayout.HORIZONTAL
        val left = LinearLayout(context)
        left.orientation = LinearLayout.VERTICAL
        val wifi = wideTile(R.drawable.ic_wifi, "Wi-Fi", "Tap to open") {
            startSafe(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
        }
        val bt = wideTile(R.drawable.ic_bluetooth, "Bluetooth", "Tap to open") {
            startSafe(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        left.addView(wifi, lp(MATCH, dp(64), b = 8))
        left.addView(bt, lp(MATCH, dp(64)))
        val media = mediaCard()
        top.addView(left, lp(0, WRAP, 1f, r = 4))
        top.addView(media, lp(0, dp(136), 1f, l = 4))
        col.addView(top, lp(MATCH, WRAP))
        qsItems.add(Pair(wifi, -1))
        qsItems.add(Pair(bt, -1))
        qsItems.add(Pair(media, 1))

        // 2 x 4 grid of round toggles
        val panel = LinearLayout(context)
        panel.orientation = LinearLayout.VERTICAL
        panel.background = glass("#33FFFFFF", 28)
        panel.setPadding(dp(8), dp(10), dp(8), dp(10))
        val r1 = LinearLayout(context)
        r1.orientation = LinearLayout.HORIZONTAL
        val r2 = LinearLayout(context)
        r2.orientation = LinearLayout.HORIZONTAL
        val cells = listOf(
            toggleCell(R.drawable.ic_airplanemode_active, 48) {
                startSafe(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
            },
            toggleCell(R.drawable.ic_flashlight_on, 48, true) { toggleTorch() },
            toggleCell(R.drawable.ic_location_on, 48) {
                startSafe(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            },
            toggleCell(R.drawable.ic_screen_rotation, 48) {
                startSafe(Intent(Settings.ACTION_DISPLAY_SETTINGS))
            },
            toggleCell(R.drawable.ic_nfc, 48) { startSafe(Intent(Settings.ACTION_NFC_SETTINGS)) },
            toggleCell(R.drawable.ic_battery_saver, 48) {
                startSafe(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            },
            toggleCell(R.drawable.ic_wifi_tethering, 48) {
                startSafe(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            },
            toggleCell(R.drawable.ic_cast, 48) { startSafe(Intent(Settings.ACTION_CAST_SETTINGS)) }
        )
        cells.forEachIndexed { i, c ->
            val target = if (i < 4) r1 else r2
            target.addView(c, lp(0, WRAP, 1f))
            qsItems.add(Pair(c, if (i % 4 < 2) -1 else 1))
        }
        panel.addView(r1, lp(MATCH, WRAP))
        panel.addView(r2, lp(MATCH, WRAP))
        col.addView(panel, lp(MATCH, WRAP, t = 8))

        // brightness + volume
        val sl = LinearLayout(context)
        sl.orientation = LinearLayout.HORIZONTAL
        val bright = brightnessSlider()
        val vol = volumeSlider()
        sl.addView(bright, lp(0, dp(52), 1f, r = 4))
        sl.addView(vol, lp(0, dp(52), 1f, l = 4))
        col.addView(sl, lp(MATCH, WRAP, t = 8))
        qsItems.add(Pair(bright, -1))
        qsItems.add(Pair(vol, 1))

        // launcher settings + system settings
        val bottom = LinearLayout(context)
        bottom.orientation = LinearLayout.HORIZONTAL
        val t1 = wideTile(R.drawable.ic_settings, "Launcher", "Animations & layout") { host.settings() }
        val t2 = wideTile(R.drawable.ic_settings, "Settings", "System settings") {
            startSafe(Intent(Settings.ACTION_SETTINGS))
        }
        bottom.addView(t1, lp(0, dp(60), 1f, r = 4))
        bottom.addView(t2, lp(0, dp(60), 1f, l = 4))
        col.addView(bottom, lp(MATCH, WRAP, t = 8))
        qsItems.add(Pair(t1, -1))
        qsItems.add(Pair(t2, 1))
    }

    private fun brightnessSlider(): PillSlider {
        val s = PillSlider(context, R.drawable.ic_brightness_medium)
        s.onChange = { p ->
            if (!Settings.System.canWrite(context)) {
                if (!askedWrite) {
                    askedWrite = true
                    startSafe(
                        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName))
                    )
                }
            } else {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    (p * 255).toInt().coerceIn(1, 255)
                )
            }
        }
        brightSliders.add(s)
        return s
    }

    private fun volumeSlider(): PillSlider {
        val s = PillSlider(context, R.drawable.ic_volume_up)
        s.onChange = { p ->
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (p * max).toInt().coerceIn(0, max), 0)
        }
        volSliders.add(s)
        return s
    }

    private fun iconButton(res: Int, onClick: () -> Unit): ImageView {
        val iv = ImageView(context)
        iv.setImageResource(res)
        iv.imageTintList = ColorStateList.valueOf(Color.WHITE)
        iv.setPadding(dp(10), dp(10), dp(10), dp(10))
        iv.setOnClickListener { onClick() }
        return iv
    }

    private fun mediaCard(): FrameLayout {
        val c = FrameLayout(context)
        c.background = glass("#44FFFFFF", 28)
        mediaTitle.setTextColor(Color.WHITE)
        mediaTitle.textSize = 13f
        mediaTitle.maxLines = 2
        mediaTitle.ellipsize = TextUtils.TruncateAt.END
        mediaTitle.text = "Nothing playing"
        mediaTitle.setPadding(dp(14), dp(14), dp(14), 0)
        c.addView(mediaTitle, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        val controls = LinearLayout(context)
        controls.orientation = LinearLayout.HORIZONTAL
        controls.gravity = Gravity.CENTER
        controls.addView(iconButton(R.drawable.ic_skip_previous) {
            mediaController()?.transportControls?.skipToPrevious()
            postDelayed({ refreshMedia() }, 400)
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
        mediaPlay = iconButton(R.drawable.ic_play_arrow) {
            val mc = mediaController()
            if (mc != null) {
                if (mc.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    mc.transportControls.pause()
                } else {
                    mc.transportControls.play()
                }
            }
            postDelayed({ refreshMedia() }, 400)
        }
        controls.addView(mediaPlay, LinearLayout.LayoutParams(dp(46), dp(46)))
        controls.addView(iconButton(R.drawable.ic_skip_next) {
            mediaController()?.transportControls?.skipToNext()
            postDelayed({ refreshMedia() }, 400)
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
        c.addView(controls, FrameLayout.LayoutParams(MATCH, dp(56), Gravity.BOTTOM))
        return c
    }

    // ---------- live data ----------

    private fun mediaController(): MediaController? {
        return try {
            val msm = context.getSystemService(MediaSessionManager::class.java)
            msm?.getActiveSessions(ComponentName(context, NotifService::class.java))?.firstOrNull()
        } catch (e: Exception) {
            null
        }
    }

    private fun refreshMedia() {
        val c = mediaController()
        mediaTitle.text = c?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Nothing playing"
        val playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING
        mediaPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow)
    }

    private fun syncSliders() {
        val b = try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (e: Exception) {
            0.5f
        }
        brightSliders.forEach { it.progress = b }
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val v = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
        volSliders.forEach { it.progress = v }
    }

    private fun setupTorch() {
        val c = cam ?: return
        try {
            torchId = c.cameraIdList.firstOrNull { id ->
                val ch = c.getCameraCharacteristics(id)
                ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            c.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (cameraId == torchId) {
                        torchOn = enabled
                        torchToggles.forEach { it.active = enabled }
                    }
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
        }
    }

    private fun toggleTorch() {
        val id = torchId ?: return
        try {
            cam?.setTorchMode(id, !torchOn)
        } catch (e: Exception) {
        }
    }

    private fun infoCard(title: String, body: String, onClick: (() -> Unit)?): View {
        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        card.background = glass("#E8F4F4F8", 22)
        card.setPadding(dp(16), dp(14), dp(16), dp(14))
        val a = TextView(context)
        a.text = title
        a.setTextColor(Color.parseColor("#1B1B1F"))
        a.textSize = 14f
        a.setTypeface(a.typeface, Typeface.BOLD)
        val b = TextView(context)
        b.text = body
        b.setTextColor(Color.parseColor("#55585F"))
        b.textSize = 13f
        card.addView(a)
        card.addView(b)
        if (onClick != null) card.setOnClickListener { onClick() }
        return card
    }

    private fun notifCard(sbn: StatusBarNotification): View {
        val n = sbn.notification
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val body = (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""

        val card = LinearLayout(context)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.background = glass("#E8F4F4F8", 22)
        card.setPadding(dp(12), dp(10), dp(12), dp(10))

        val ic = ImageView(context)
        val circle = GradientDrawable()
        circle.shape = GradientDrawable.OVAL
        circle.setColor(if (n.color != 0) n.color else Color.parseColor("#6B7280"))
        ic.background = circle
        ic.setPadding(dp(7), dp(7), dp(7), dp(7))
        try {
            val d = n.smallIcon?.loadDrawable(context)
            if (d != null) {
                ic.setImageDrawable(d)
                ic.imageTintList = ColorStateList.valueOf(Color.WHITE)
            }
        } catch (e: Exception) {
        }
        card.addView(ic, LinearLayout.LayoutParams(dp(36), dp(36)))

        val tx = LinearLayout(context)
        tx.orientation = LinearLayout.VERTICAL
        tx.setPadding(dp(12), 0, 0, 0)
        val t1 = TextView(context)
        t1.text = title
        t1.setTextColor(Color.parseColor("#1B1B1F"))
        t1.textSize = 14f
        t1.maxLines = 1
        t1.ellipsize = TextUtils.TruncateAt.END
        t1.setTypeface(t1.typeface, Typeface.BOLD)
        val t2 = TextView(context)
        t2.text = body
        t2.setTextColor(Color.parseColor("#55585F"))
        t2.textSize = 13f
        t2.maxLines = 2
        t2.ellipsize = TextUtils.TruncateAt.END
        tx.addView(t1)
        tx.addView(t2)
        card.addView(tx, LinearLayout.LayoutParams(0, WRAP, 1f))

        card.setOnClickListener {
            try {
                n.contentIntent?.send()
            } catch (e: Exception) {
            }
            if ((n.flags and Notification.FLAG_AUTO_CANCEL) != 0) {
                NotifService.instance?.cancelNotification(sbn.key)
            }
            host.close()
        }
        card.setOnLongClickListener {
            NotifService.instance?.cancelNotification(sbn.key)
            refreshNotifs()
            true
        }
        return card
    }

    private fun refreshNotifs() {
        notifList.removeAllViews()
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        if (!enabled) {
            notifList.addView(
                infoCard("Allow notification access", "Tap here so the launcher can show your notifications.") {
                    startSafe(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
                lp(MATCH, WRAP, b = 8)
            )
            return
        }
        val all: List<StatusBarNotification> = try {
            NotifService.instance?.activeNotifications?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val shown = all.filter { s ->
            val ex = s.notification.extras
            (s.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 &&
                s.packageName != context.packageName &&
                (ex.getCharSequence(Notification.EXTRA_TITLE) != null ||
                    ex.getCharSequence(Notification.EXTRA_TEXT) != null)
        }.sortedByDescending { it.postTime }
        if (shown.isEmpty()) {
            notifList.addView(infoCard("No notifications", "You're all caught up.", null), lp(MATCH, WRAP, b = 8))
        }
        for (s in shown) {
            notifList.addView(notifCard(s), lp(MATCH, WRAP, b = 8))
        }
    }

    // ---------- animation ----------

    private fun slideIn(items: List<Pair<View, Int>>) {
        items.forEachIndexed { i, pair ->
            val v = pair.first
            val side = pair.second
            v.animate().cancel()
            v.alpha = 0f
            v.translationX = side * dp(150).toFloat()
            v.translationY = if (side == 0) dp(24).toFloat() else 0f
            val d = i * 28L
            v.spring(DynamicAnimation.TRANSLATION_X, 0f, prefs, d)
            v.spring(DynamicAnimation.TRANSLATION_Y, 0f, prefs, d)
            v.animate().alpha(1f).setStartDelay(d).setDuration(prefs.ms(170)).start()
        }
    }

    fun show() {
        showingQs = false
        notifPage.visibility = View.VISIBLE
        notifPage.alpha = 1f
        notifPage.setBlur(0f)
        qsPage.visibility = View.GONE
        visibility = View.VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(prefs.ms(220)).start()
        refreshNotifs()
        refreshMedia()
        syncSliders()
        askedWrite = false
        slideIn(notifItems)
        NotifService.onChange = {
            post {
                refreshNotifs()
                refreshMedia()
            }
        }
        val b = prefs.blurPx() * 0.8f
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = prefs.ms(260)
            addUpdateListener { setBlur(b * (it.animatedValue as Float)) }
            start()
        }
    }

    fun hide(end: () -> Unit) {
        NotifService.onChange = null
        val b = prefs.blurPx() * 0.8f
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = prefs.ms(200)
            addUpdateListener { setBlur(b * (it.animatedValue as Float)) }
            start()
        }
        animate().alpha(0f).setDuration(prefs.ms(200)).withEndAction {
            visibility = View.GONE
            setBlur(0f)
            end()
        }.start()
    }

    fun showPage(qs: Boolean) {
        if (qs == showingQs) return
        if (swapAnim?.isRunning == true) return
        showingQs = qs
        val from = if (qs) notifPage else qsPage
        val to = if (qs) qsPage else notifPage
        to.visibility = View.VISIBLE
        to.alpha = 0f
        val b = prefs.blurPx()
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = prefs.ms(260)
        anim.addUpdateListener {
            val t = it.animatedValue as Float
            from.alpha = 1f - t
            from.setBlur(b * t)
            to.alpha = t
            to.setBlur(b * (1f - t))
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                from.visibility = View.GONE
                from.alpha = 1f
                from.setBlur(0f)
                to.alpha = 1f
                to.setBlur(0f)
            }
        })
        swapAnim = anim
        anim.start()
        slideIn(if (qs) qsItems else notifItems)
        refreshMedia()
        if (!qs) refreshNotifs()
    }

    // ---------- gestures ----------

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                tracking = true
                val r = Rect()
                notifScroll.getGlobalVisibleRect(r)
                inList = !showingQs && r.contains(e.rawX.toInt(), e.rawY.toInt())
            }
            MotionEvent.ACTION_MOVE -> {
                if (tracking) {
                    val dy = e.y - downY
                    val dx = e.x - downX
                    if (abs(dy) > slop * 2 && abs(dy) > abs(dx) * 1.5f) {
                        tracking = false
                        if (inList && notifScroll.canScrollVertically(if (dy < 0) 1 else -1)) {
                            return false
                        }
                        if (dy > 0) {
                            if (!showingQs) showPage(true)
                        } else {
                            if (showingQs) showPage(false) else host.close()
                        }
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
