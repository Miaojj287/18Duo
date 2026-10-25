package com.jake.duolauncher

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.app.Notification
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.telephony.TelephonyManager
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Colour of the app's own top edge per package, measured from the navigation service's settled
 * captures just below the status bar. The Apple status strip paints itself in it so the app looks
 * as if it ran under a transparent iOS status bar.
 */
internal object StatusTint {
    private val colors = HashMap<String, Int>()
    /** Set while one of our panels is on screen: its pixels are not the app's. */
    @Volatile var suspended = false
    /** Uptime until which captures are not the app's own: a shade (ours or Xiaomi's) was just pulled or closed. */
    @Volatile private var quietUntil = 0L

    fun quiet(ms: Long) { quietUntil = android.os.SystemClock.uptimeMillis() + ms }

    fun record(packageName: String, color: Int?) {
        if (suspended || color == null || android.os.SystemClock.uptimeMillis() < quietUntil) return
        synchronized(colors) { colors[packageName] = color }
    }

    fun of(packageName: String?): Int? = packageName?.let { synchronized(colors) { colors[it] } }
}

/** Relative perceived brightness (0..1) of an opaque colour. */
internal fun statusLuma(color: Int): Float =
    (.2126f * Color.red(color) + .7152f * Color.green(color) + .0722f * Color.blue(color)) / 255f

/**
 * Draws an optional iOS-style status bar and the Notification Center / Control Center over apps,
 * with the AP16 theme's artwork. No Xiaomi theme files are changed; the original shade stays behind.
 */
internal class AppleShadeOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val keyguard = service.getSystemService(KeyguardManager::class.java)
    private val roles = service.getSystemService(RoleManager::class.java)
    private val displays = service.getSystemService(DisplayManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val chinese = Locale.getDefault().language == "zh"
    private val art = Ap16Art(service)
    private val icons = AppleIconPack(service.assets)
    private val appIcons = HashMap<String, Bitmap?>()
    private var started = false
    private var status: StatusStrip? = null
    private var statusSize = ""
    private var panel: PanelRoot? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var panelKind: ShadePanel? = null
    private var controlCenter: AppleControlCenter? = null
    private var notificationList: LinearLayout? = null
    private var lastNotificationIdentity = emptyList<String>()
    private var lastNotificationAccess: Boolean? = null
    private var foregroundPackage: String? = null
    private var ownScreen = false
    private var progressAnimator: ValueAnimator? = null
    private val ease = PathInterpolator(.32f, .72f, 0f, 1f)
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                closePanel(animated = false)
                removeStatus()
            }
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!started) return
            update()
            if (panelKind == ShadePanel.NOTIFICATIONS) renderNotifications()
            controlCenter?.refresh()
            handler.postDelayed(this, 1_000)
        }
    }

    fun start() {
        if (started) return
        started = true
        service.registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        tick.run()
    }

    fun stop() {
        started = false
        handler.removeCallbacks(tick)
        runCatching { service.unregisterReceiver(screenOff) }
        closePanel(animated = false)
        removeStatus()
    }

    fun hideIfLocked() {
        if (!keyguard.isKeyguardLocked) return
        closePanel(animated = false)
        removeStatus()
    }

    /** A window-state change of [packageName]: the strip takes that app's colour (or leaves Home) at once. */
    fun onWindow(packageName: String?, className: String?) {
        // Only an activity coming forward counts (not an overlay, toast or gesture window: the system's
        // own top/bottom gesture windows report as FrameLayouts of the launcher or SystemUI).
        if (packageName == null || className == null || !isActivity(packageName, className)) return
        // 18Duo's own lock screen, editor and StandBy draw their own status (or none).
        if (packageName == service.packageName) ownScreen = className != MainActivity::class.java.name
        else if (packageName != "com.android.systemui") ownScreen = false
        if (packageName != "com.android.systemui") {
            // Another app (or Home) came forward under an open panel: the panel goes, as iOS's does.
            if (panel != null && packageName != foregroundPackage && packageName != service.packageName &&
                !packageName.startsWith("miui.systemui") && !packageName.startsWith("com.miui.systemui")) {
                Log.i("AppleShade", "panel closes: $packageName/$className came forward (was $foregroundPackage)")
                closePanel(animated = false)
            }
            foregroundPackage = packageName
        }
        handler.post { update() }
        // The settled capture that measures the app's top edge lands shortly after.
        handler.postDelayed({ update() }, AutoCapturePolicy.SETTLE_MS + 400L)
    }

    private val activityCache = HashMap<String, Boolean>()

    private fun isActivity(packageName: String, className: String): Boolean = activityCache.getOrPut("$packageName/$className") {
        runCatching { service.packageManager.getActivityInfo(ComponentName(packageName, className), 0) }.isSuccess
    }

    /** Home's own top-edge swipe (it hides the system bar and draws its own status rail). */
    fun open(kind: ShadePanel): Boolean {
        if (!started || keyguard.isKeyguardLocked) return false
        if (panel == null) openPanel(kind)
        animateProgress(1f)
        return panel != null
    }

    private fun update() {
        val displayOn = displays.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON
        val visible = displayOn && !keyguard.isKeyguardLocked && roles.isRoleHeld(RoleManager.ROLE_HOME)
        val bounds = wm.currentWindowMetrics.bounds
        val size = "${bounds.width()}x${bounds.height()}"
        if (!visible) {
            if (panel != null) Log.i("AppleShade", "panel closes: not visible (display/keyguard/home role)")
            closePanel(animated = false)
            removeStatus()
            return
        }
        if (panel != null && statusSize != size && status != null) {
            Log.i("AppleShade", "panel closes: window size $statusSize -> $size")
            closePanel(animated = false)
        }
        // Home hides the system status bar and draws its own rail; the strip would only cover the wallpaper.
        if ((NavigationProgressBus.launcherVisible || ownScreen) && panel == null) {
            removeStatus()
            return
        }
        if (status == null || statusSize != size) {
            removeStatus()
            addStatus(size)
        }
        status?.refresh()
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    private fun dp(value: Float): Float = value * service.resources.displayMetrics.density
    private fun label(zh: String, en: String): String = if (chinese) zh else en
    private fun night() = service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun statusHeight(): Int = max(dp(26), runCatching {
        wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
    }.getOrDefault(0))

    /** Camera cutouts in the top band, in window pixels (the inner screen's sits in the top-left corner). */
    private fun topCutouts(): List<Rect> = runCatching {
        wm.currentWindowMetrics.windowInsets.displayCutout?.boundingRects.orEmpty().filter { it.top <= 0 && !it.isEmpty }
    }.getOrDefault(emptyList())

    private fun addStatus(size: String) {
        val view = StatusStrip()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, statusHeight(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            title = "18Duo-AppleStatus"
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        runCatching { wm.addView(view, lp) }
            .onSuccess { status = view; statusSize = size; view.refresh() }
            .onFailure { Log.w("AppleShade", "Status overlay unavailable", it) }
    }

    private fun removeStatus() {
        status?.let { runCatching { wm.removeViewImmediate(it) } }
        status = null
        statusSize = ""
    }

    /** What the status bar shows: read once a second. */
    private class DeviceState(val cellLevel: Int?, val airplane: Boolean, val wifiLevel: Int?, val battery: Int,
        val charging: Boolean, val powerSave: Boolean)

    private fun deviceState(): DeviceState {
        val battery = service.getSystemService(BatteryManager::class.java)
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 } ?: 0
        val network = service.getSystemService(ConnectivityManager::class.java)
        val caps = network.getNetworkCapabilities(network.activeNetwork)
        val wifiLevel = if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) runCatching {
            val wifi = service.getSystemService(WifiManager::class.java)
            val rssi = caps.signalStrength
            if (rssi == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) 4
            else (wifi.calculateSignalLevel(rssi) * 4f / wifi.maxSignalLevel.coerceAtLeast(1)).roundToInt()
        }.getOrDefault(4) else null
        val airplane = Settings.Global.getInt(service.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        val cell = runCatching { service.getSystemService(TelephonyManager::class.java).signalStrength?.level }.getOrNull()
        return DeviceState(cell, airplane, wifiLevel, level, battery.isCharging,
            service.getSystemService(PowerManager::class.java).isPowerSaveMode)
    }

    /** Signal, Wi-Fi and battery as the AP16 theme draws them (natural size: its xxhdpi pixels at our density). */
    private inner class StatusIconsView(private val forceLight: Boolean = false) : View(service) {
        private var images = emptyList<Bitmap>()
        private val gap = dp(5f)
        private val glyphHeight = dp(20f)

        fun update(state: DeviceState, dark: Boolean) {
            val d = dark && !forceLight
            images = listOfNotNull(
                art.image(Ap16Status.signal(state.cellLevel, state.airplane, d)).takeIf { state.cellLevel != null || state.airplane },
                state.wifiLevel?.let { art.image(Ap16Status.wifi(it, d)) },
                art.batteryFrame(Ap16Status.battery(state.charging, state.powerSave, d), state.battery),
            )
            requestLayout(); invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = images.sumOf { (it.width * glyphHeight / it.height).toDouble() }.toFloat() + gap * max(0, images.size - 1)
            setMeasuredDimension(w.roundToInt(), MeasureSpec.getSize(heightMeasureSpec))
        }

        override fun onDraw(canvas: Canvas) {
            var x = 0f
            val top = (height - glyphHeight) / 2f
            for (image in images) {
                val w = image.width * glyphHeight / image.height
                canvas.drawBitmap(image, null, RectF(x, top, x + w, top + glyphHeight), null)
                x += w + gap
            }
        }
    }

    private inner class StatusStrip : FrameLayout(service) {
        private val time = text("", 15.5f, bold = true)
        private val indicators = StatusIconsView()
        private val island = View(service).apply {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
            clipToOutline = true
            setBackgroundColor(Color.BLACK)
        }
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var dragKind: ShadePanel? = null

        init {
            isClickable = true
            val width = wm.currentWindowMetrics.bounds.width()
            val cutouts = topCutouts()
            val leftCut = cutouts.filter { it.left < width / 3 }.maxOfOrNull { it.right } ?: 0
            val rightCut = cutouts.filter { it.right > width * 2 / 3 }.minOfOrNull { it.left } ?: width
            val centre = cutouts.firstOrNull { it.left >= width / 3 && it.right <= width * 2 / 3 }
            time.gravity = Gravity.CENTER_VERTICAL
            addView(time, LayoutParams(-2, -1, Gravity.START).apply { leftMargin = max(dp(22), leftCut + dp(10)) })
            addView(indicators, LayoutParams(-2, -1, Gravity.END).apply { rightMargin = max(dp(18), width - rightCut + dp(10)) })
            // Cover: a Dynamic Island around the punch-hole camera, centred on it as on iPhone.
            if (centre != null) {
                val h = min(statusHeight() - dp(6), max(centre.height() + dp(4), dp(24)))
                val w = max(dp(92), centre.width() + dp(64))
                addView(island, LayoutParams(w, h, Gravity.TOP or Gravity.START).apply {
                    leftMargin = centre.centerX() - w / 2
                    topMargin = max(dp(2), centre.centerY() - h / 2)
                })
            }
        }

        fun refresh() {
            val background = if (NavigationProgressBus.launcherVisible) null else StatusTint.of(foregroundPackage)
            val color = background ?: if (night()) Color.BLACK else Color.WHITE
            setBackgroundColor(color)
            val dark = statusLuma(color) > .62f
            time.setTextColor(if (dark) Color.BLACK else Color.WHITE)
            time.text = LocalDateTime.now().format(DateTimeFormatter.ofPattern("H:mm"))
            indicators.update(deviceState(), dark)
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

        /** The panel follows the finger from the first few pixels, as iOS's does; release decides. */
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; dragging = false; dragKind = null
                    StatusTint.quiet(2_500)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - downY
                    if (!dragging && dy > ViewConfiguration.get(service).scaledTouchSlop) {
                        val width = wm.currentWindowMetrics.bounds.width()
                        val kind = if (downX > width * .62f) ShadePanel.QUICK_SETTINGS else ShadePanel.NOTIFICATIONS
                        if (panel == null) openPanel(kind)
                        dragging = panel != null
                        dragKind = kind
                    }
                    if (dragging) panel?.setProgress((dy / dragDistance()).coerceIn(0f, 1f))
                }
                MotionEvent.ACTION_UP -> if (dragging) {
                    val progress = panel?.progress ?: 0f
                    animateProgress(if (progress > .28f && event.rawY - downY > 0) 1f else 0f)
                    dragging = false
                }
                // The system's own top-edge gesture may take the stream over (it pulls its shade): the
                // pull already started, so ours completes and the system shade is sent back up.
                MotionEvent.ACTION_CANCEL -> {
                    // Android's own status-bar gesture can take the stream before our slop: that is a pull too.
                    if (!dragging && panel == null) {
                        val width = wm.currentWindowMetrics.bounds.width()
                        openPanel(if (downX > width * .62f) ShadePanel.QUICK_SETTINGS else ShadePanel.NOTIFICATIONS)
                    }
                    if (panel != null) animateProgress(1f)
                    dragging = false
                }
            }
            return true
        }
    }

    private fun dragDistance(): Float = min(wm.currentWindowMetrics.bounds.height() * .42f, dp(360f))

    /** Root of a panel window: tapping empty space or swiping up closes it, as on iOS. */
    private inner class PanelRoot(val kind: ShadePanel) : FrameLayout(service) {
        var progress = 0f
            private set
        lateinit var content: View
        private val scrim = View(service)
        private var downY = 0f
        private var downX = 0f
        private var closing = false
        private var startProgress = 1f

        init {
            addView(scrim, LayoutParams(-1, -1))
            scrim.setOnClickListener { animateProgress(0f) }
        }

        fun setProgress(value: Float) {
            progress = value
            val blur = wm.isCrossWindowBlurEnabled
            val dim = if (blur) (if (night()) 120 else 72) else 226
            scrim.setBackgroundColor(Color.argb((dim * value).roundToInt(), 8, 9, 12))
            content.alpha = min(1f, value * 1.6f)
            // The panels carry their own clock / status icons; the strip fades out under them (it keeps the touch).
            status?.alpha = 1f - min(1f, value * 2f)
            if (kind == ShadePanel.NOTIFICATIONS) {
                content.translationY = -(1f - value) * dp(140f)
            } else {
                val s = .9f + .1f * value
                content.scaleX = s; content.scaleY = s
                content.translationY = -(1f - value) * dp(36f)
            }
            panelLp?.let { lp ->
                // Eight steps: each radius change relayouts the window, too costly on every frame of a drag.
                val radius = if (blur) (dp(42f) * (value * 8f).roundToInt() / 8f).roundToInt() else 0
                if (lp.blurBehindRadius != radius) {
                    lp.blurBehindRadius = radius
                    runCatching { wm.updateViewLayout(this, lp) }
                }
            }
        }

        /**
         * Watched before any child sees the move: an upward swipe the list cannot use closes the panel
         * (the ScrollView would otherwise claim it at the same touch slop and forbid interception).
         */
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (closing) {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_MOVE -> setProgress((startProgress + (ev.rawY - downY) / dragDistance()).coerceIn(0f, 1f))
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        closing = false
                        animateProgress(if (progress < .85f || ev.actionMasked == MotionEvent.ACTION_CANCEL) 0f else 1f)
                    }
                }
                return true
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = ev.rawY; downX = ev.rawX }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - downY
                    val slop = ViewConfiguration.get(service).scaledTouchSlop
                    if (dy < -slop && abs(dy) > abs(ev.rawX - downX) * 1.4f && !content.canScrollVertically(1)) {
                        closing = true; startProgress = progress; downY = ev.rawY
                        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                        return true
                    }
                }
            }
            return super.dispatchTouchEvent(ev)
        }
    }

    private fun animateProgress(target: Float) {
        val root = panel ?: return
        progressAnimator?.cancel()
        val reduceMotion = Settings.Global.getFloat(service.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        if (reduceMotion) {
            root.setProgress(target)
            if (target == 0f) closePanel(animated = false)
            return
        }
        progressAnimator = ValueAnimator.ofFloat(root.progress, target).apply {
            duration = if (target > root.progress) 380 else 260
            interpolator = ease
            addUpdateListener { if (panel === root) root.setProgress(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled && target == 0f && panel === root) closePanel(animated = false)
                }
            })
            start()
        }
    }

    private fun openPanel(kind: ShadePanel) {
        if (!started || keyguard.isKeyguardLocked || panel != null) return
        StatusTint.suspended = true
        val root = PanelRoot(kind)
        val bounds = wm.currentWindowMetrics.bounds
        val top = statusHeight()
        val content: View = if (kind == ShadePanel.NOTIFICATIONS) notificationCenter(bounds.width(), top)
            else controlCenterContent(bounds.width(), bounds.height(), top)
        root.content = content
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        val lp = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_BLUR_BEHIND,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.FILL
            title = "18Duo-${kind.name}"
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            blurBehindRadius = 0
        }
        panelLp = lp
        runCatching { wm.addView(root, lp) }.onSuccess {
            Log.i("AppleShade", "panel opens: $kind over $foregroundPackage")
            panel = root; panelKind = kind
            root.setProgress(0f)
            // Xiaomi's shade may have started down under ours from the same swipe.
            handler.postDelayed({
                if (panel === root) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            }, 160)
        }.onFailure {
            Log.w("AppleShade", "Panel overlay unavailable", it)
            panelLp = null
            controlCenter?.release(); controlCenter = null
            StatusTint.suspended = false
        }
    }

    private fun closePanel(animated: Boolean = true) {
        val view = panel ?: return
        if (animated) { animateProgress(0f); return }
        Log.i("AppleShade", "panel removed (${panelKind})")
        progressAnimator?.cancel(); progressAnimator = null
        panel = null; panelKind = null; panelLp = null; notificationList = null
        controlCenter?.release(); controlCenter = null
        lastNotificationIdentity = emptyList(); lastNotificationAccess = null
        runCatching { wm.removeViewImmediate(view) }
        status?.alpha = 1f
        StatusTint.suspended = false
        StatusTint.quiet(2_500)
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
    }

    // ---- Control Center ----

    private fun controlCenterContent(width: Int, height: Int, top: Int): View {
        val wide = width > dp(600)
        val margin = if (wide) dp(36) else dp(16)
        val available = if (wide) min(width / 2, dp(420)) else width - 2 * margin
        val cc = AppleControlCenter(service, art, launch = ::launch, dismiss = { closePanel(animated = false) })
        controlCenter = cc
        val scroll = ScrollView(service).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }
        val column = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (wide) Gravity.END else Gravity.CENTER_HORIZONTAL
            setPadding(margin, top + dp(10), margin, dp(40))
            // Taps between tiles reach the root and close the panel.
            isClickable = true
            setOnClickListener { animateProgress(0f) }
        }
        // iOS Control Center repeats the status icons in its top-right corner.
        val icons = StatusIconsView(forceLight = true).also { it.update(deviceState(), false) }
        column.addView(icons, LinearLayout.LayoutParams(-2, dp(26)).apply { gravity = Gravity.END; bottomMargin = dp(16) })
        column.addView(cc.build(available, height - top - dp(80)), LinearLayout.LayoutParams(-2, -2).apply {
            gravity = if (wide) Gravity.END else Gravity.CENTER_HORIZONTAL
        })
        scroll.addView(column, FrameLayout.LayoutParams(-1, -2))
        if (wide) scroll.pivotX = width.toFloat() else scroll.pivotX = width / 2f
        scroll.pivotY = 0f
        return scroll
    }

    // ---- Notification Center ----

    private fun notificationCenter(width: Int, top: Int): View {
        val scroll = ScrollView(service).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }
        val maxWidth = min(width - dp(24), dp(560))
        val column = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, top + dp(26), 0, dp(48))
        }
        val holder = FrameLayout(service).apply {
            isClickable = true
            setOnClickListener { animateProgress(0f) }
        }
        holder.addView(column, FrameLayout.LayoutParams(maxWidth, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        scroll.addView(holder)
        val now = LocalDateTime.now()
        val date = text(now.format(DateTimeFormatter.ofPattern(if (chinese) "M月d日 EEEE" else "EEEE, MMMM d")), 19f, bold = true)
        date.gravity = Gravity.CENTER_HORIZONTAL
        val clock = text(now.format(DateTimeFormatter.ofPattern("H:mm")), 92f).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            typeface = ResourcesCompat.getFont(service, R.font.duo_clock_digits)
            includeFontPadding = false
        }
        column.addView(date, LinearLayout.LayoutParams(-1, -2))
        column.addView(clock, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        val header = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(text(label("通知中心", "Notification Center"), 21f, bold = true),
            LinearLayout.LayoutParams(0, -2, 1f))
        val clear = ImageView(service).apply {
            setImageBitmap(art.image("cc/clear"))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = GlassPlate(1_000f, service.resources.displayMetrics.density)
            contentDescription = label("清除全部通知", "Clear all notifications")
            setOnClickListener {
                AppleNotificationListener.clearAll()
                renderNotifications(force = true)
            }
        }
        header.addView(clear, LinearLayout.LayoutParams(dp(34), dp(34)))
        column.addView(header, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(30); bottomMargin = dp(12); leftMargin = dp(8); rightMargin = dp(4)
        })
        val list = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        notificationList = list
        column.addView(list, LinearLayout.LayoutParams(-1, -2))
        renderNotifications(force = true)
        return scroll
    }

    private fun renderNotifications(force: Boolean = false) {
        val list = notificationList ?: return
        val items = AppleNotificationListener.snapshot()
        val identity = items.map { "${it.key}:${it.postTime}:${it.notification.extras?.getCharSequence(Notification.EXTRA_TITLE)}:${it.notification.extras?.getCharSequence(Notification.EXTRA_TEXT)}" }
        val access = AppleShadeSettings.notificationAccess(service)
        if (!force && lastNotificationIdentity == identity && list.childCount > 0 && lastNotificationAccess == access) return
        lastNotificationIdentity = identity
        lastNotificationAccess = access
        list.removeAllViews()
        if (!access) {
            val card = card()
            card.addView(text(label("允许 18Duo 读取通知后，这里会显示应用消息。", "Allow 18Duo notification access to show messages here."), 15f, ink = true))
            card.addView(action(label("开启通知读取权限", "Enable notification access")) {
                launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            list.addView(card, cardParams())
            return
        }
        if (items.isEmpty()) {
            list.addView(text(label("没有较早的通知", "No Older Notifications"), 15f).apply {
                gravity = Gravity.CENTER_HORIZONTAL; alpha = .7f
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(36) })
            return
        }
        items.forEach { sbn -> list.addView(notificationCard(sbn), cardParams()) }
    }

    private fun appIcon(packageName: String): Bitmap? = appIcons.getOrPut(packageName) {
        runCatching {
            val component = service.packageManager.getLaunchIntentForPackage(packageName)?.component
            component?.let { icons.iconFor(it) } ?: launcherIcon(service.packageManager.getApplicationIcon(packageName))
        }.getOrNull()
    }

    private fun relativeTime(postTime: Long): String {
        val minutes = (System.currentTimeMillis() - postTime) / 60_000
        return when {
            minutes < 1 -> label("现在", "now")
            minutes < 60 -> label("${minutes}分钟前", "${minutes}m ago")
            minutes < 24 * 60 -> label("${minutes / 60}小时前", "${minutes / 60}h ago")
            else -> java.time.Instant.ofEpochMilli(postTime).atZone(java.time.ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern(if (chinese) "M月d日" else "MMM d"))
        }
    }

    /** iOS notification: app icon, bold title with the time on its right, up to four lines of text. Swipe sideways to clear. */
    private fun notificationCard(sbn: StatusBarNotification): View {
        val card = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(13), dp(16), dp(14))
            background = notificationPlate()
        }
        val appName = runCatching {
            val info = service.packageManager.getApplicationInfo(sbn.packageName, 0)
            service.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(sbn.packageName)
        val icon = ImageView(service).apply {
            appIcon(sbn.packageName)?.let(::setImageBitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        card.addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(11); topMargin = dp(1) })
        val column = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        val n = sbn.notification
        val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() } ?: appName
        val body = (n.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: n.extras?.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.take(400)
        val titleRow = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(text(title, 15f, bold = true, ink = true), LinearLayout.LayoutParams(0, -2, 1f))
        titleRow.addView(text(relativeTime(sbn.postTime), 13f, ink = true).apply { alpha = .55f },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        column.addView(titleRow)
        if (!body.isNullOrBlank()) column.addView(text(body, 15f, ink = true).apply {
            maxLines = 4; setLineSpacing(0f, 1.12f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        card.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
        card.contentDescription = "$appName, $title"
        swipeToDismiss(card, sbn)
        n.contentIntent?.let { intent ->
            card.isClickable = true
            card.setOnClickListener {
                closePanel(animated = false)
                runCatching { intent.send() }
                if (n.flags and Notification.FLAG_AUTO_CANCEL != 0) AppleNotificationListener.dismiss(sbn.key)
            }
        }
        return card
    }

    private fun swipeToDismiss(card: View, sbn: StatusBarNotification) {
        if (!sbn.isClearable) return
        var downX = 0f
        var downY = 0f
        var swiping = false
        card.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; swiping = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    if (!swiping && abs(dx) > ViewConfiguration.get(service).scaledTouchSlop && abs(dx) > abs(e.rawY - downY) * 1.3f) {
                        swiping = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        (v.parent?.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (swiping) { v.translationX = dx; v.alpha = 1f - min(1f, abs(dx) / v.width) * .6f }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (swiping) {
                    val dx = v.translationX
                    if (abs(dx) > v.width * .35f && e.actionMasked == MotionEvent.ACTION_UP) {
                        v.animate().translationX(if (dx > 0) v.width.toFloat() else -v.width.toFloat()).alpha(0f).setDuration(180)
                            .withEndAction { AppleNotificationListener.dismiss(sbn.key); renderNotifications(force = true) }.start()
                    } else v.animate().translationX(0f).alpha(1f).setDuration(200).start()
                    swiping = false
                    return@setOnTouchListener true
                }
            }
            swiping
        }
    }

    /** Light frosted card in light mode (dark text), smoky glass in dark mode — iOS notification materials. */
    private fun notificationPlate() = GlassPlate(dp(22f), service.resources.displayMetrics.density).apply {
        if (night()) fillAlpha = 52 else active = Color.argb(196, 246, 246, 248)
    }

    private fun launch(intent: Intent) {
        closePanel(animated = false)
        runCatching { service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure {
                Log.w("AppleShade", "Specific control unavailable, opening Settings", it)
                runCatching { service.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
    }

    /** [ink] text sits on a notification card: dark in light mode. Other panel text is white over the dimmed blur. */
    private fun text(value: String, size: Float, bold: Boolean = false, ink: Boolean = false): TextView = TextView(service).apply {
        text = value
        setTextColor(if (ink && !night()) Color.rgb(20, 20, 22) else Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        typeface = ResourcesCompat.getFont(service, if (bold) R.font.inter_semibold else R.font.inter_regular)
        ellipsize = TextUtils.TruncateAt.END
        if (!ink && !bold) setShadowLayer(dp(6f), 0f, 0f, Color.argb(60, 0, 0, 0))
    }

    private fun card(): LinearLayout = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = notificationPlate()
    }

    private fun cardParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }

    private fun action(value: String, onClick: () -> Unit): TextView = text(value, 15f, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(44)
        background = GlassPlate(dp(22f), service.resources.displayMetrics.density).also { it.active = Color.rgb(10, 132, 255) }
        isClickable = true
        setOnClickListener { onClick() }
    }
}
