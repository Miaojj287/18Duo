package com.jake.duolauncher

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.app.AlertDialog
import android.app.KeyguardManager
import android.app.role.RoleManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.RoundedCorner
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Three narrow input windows plus one full-screen draw-only stage. Screen content is captured when
 * the user starts a bottom gesture over an app (iOS needs that frame to shrink the app into a card)
 * and, so every switcher card shows its app as it was left, once an app has settled in front and
 * then at most every few seconds while it stays there ([AutoCapturePolicy]); no window content is
 * retrieved. The only accessibility events observed are
 * window-state changes, reduced to the package/class name of the window now in front, so the
 * card, its snapshot and the home indicator belong to the right app (NavigationForeground.kt).
 *
 * Threads: the edge windows, accessibility events and Home's copies of itself live on the main
 * thread, which the launcher shares. The stage window is added from its own display-priority
 * Looper thread ([stageThread]), so its frames never wait for Home resuming, relaying out or being
 * copied — the moments a card flies into an icon. Everything that touches [stage] or the gesture
 * plumbing below runs on that thread ([onStage]). Home publishes its icon positions and frozen
 * frames as immutable values the stage reads directly (LaunchIconRegistry, HomeSnapshotStore).
 * Binder calls the stage triggers (start an app, bring Home, Back) run on [actions], so a slow
 * system server never holds a stage frame either.
 */
internal class NavigationOverlay(private val service: AccessibilityService) : DisplayManager.DisplayListener, NavigationStageHost {
    private val handler = Handler(Looper.getMainLooper())
    private val stageThread = HandlerThread("DuoNavStage", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
    private val stageHandler = Handler(stageThread.looper)
    private val stageExecutor = Executor { stageHandler.post(it) }
    private val wm = service.getSystemService(WindowManager::class.java)
    private val displays = service.getSystemService(DisplayManager::class.java)
    private val prefs = service.getSharedPreferences("navigation_gestures", 0)
    private val views = mutableListOf<EdgeView>()
    /** Created, used and removed on the stage thread only; other threads read just its volatile flags. */
    @Volatile private var stage: NavigationStage? = null
    private var stageParams: WindowManager.LayoutParams? = null
    private var stageRefresh: DisplayRefreshBoost? = null
    /** Main thread: a stage has been requested for the current [signature]. */
    private var stageWanted = false
    private val worker = Executors.newSingleThreadExecutor()
    /** Activity starts and global actions, in order, off both the stage and the main thread. */
    private val actions = Executors.newSingleThreadExecutor()
    private val probe = Executors.newSingleThreadExecutor()
    /**
     * Snapshot files: reads (warming the cache) and WebP writes, in order, off every UI thread. Round 22: at
     * background priority — the encode ran during the launch/return animations and took CPU from them.
     */
    private val diskIo = Executors.newSingleThreadExecutor { task ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "duo-app-snapshot-io")
    }
    private val source = RecentTaskSource(service)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var busJob: Job? = null
    private var signature = ""
    @Volatile private var cornerRadius = 34f
    private var dialog: AlertDialog? = null
    // ---- stage thread: gesture plumbing ----
    /** Bumped for every bottom gesture so late screenshots and task lists from an old one are dropped. */
    private var gestureToken = 0
    /** Snapshot key of the app in front, when it is certain enough to file its capture (see [RecentTaskList.fileable]). */
    private var gestureForeground: String? = null
    private var gestureForegroundPackage: String? = null
    private var gestureSecure = false
    private var gestureSnapshot: AppSnapshot? = null
    private var gestureTasksLoaded = false
    /** [AppSnapshotStore.epoch] when the gesture began: a capture is filed only if no screen-off intervened. */
    private var gestureEpoch = 0
    /** Last disk write per snapshot key, and the newest capture not yet written (stage thread). */
    private val persistedAt = HashMap<String, Long>()
    private var pendingPersist: Pair<String, AppSnapshot>? = null
    private var autoFiledSample: WindowSample? = null
    // ---- main thread: background captures ([AutoCapturePolicy]) ----
    /** Wall-clock time a system window or one of our own sheets last appeared (it may cover the app). */
    private var chromeAt = 0L
    private var autoShotSample: WindowSample? = null
    private var autoShotAt = 0L
    private var autoInFlight = false
    private val autoCheck = Runnable { maybeAutoCapture() }
    // ---- worker thread ----
    private var autoKeySample: WindowSample? = null
    private var autoKey: String? = null
    // ---- main thread ----
    /** Last window that became active (accessibility window-state events); read by the stage thread. */
    @Volatile private var window: WindowSample? = null
    @Volatile private var windowEventsSeen = false
    private var inputMethods: Set<String> = emptySet()
    private val windowWaiters = ArrayList<Pair<String, (WindowOwner, String?) -> Unit>>()
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) { refresh() }
    }
    private val monitor = object : Runnable {
        override fun run() { refresh(); pollForeground(); maybeAutoCapture(); handler.postDelayed(this, 1500) }
    }
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT) {
                // Gesture windows come back the moment the keyguard goes, not at the next poll;
                // the app now in front is seen anew, and so is its home indicator.
                refresh(); onStage { it.wakeIndicator() }; warmStored(); return
            }
            // iOS keeps every app's last frame across a lock (the switcher is unreachable while
            // locked: the overlay is removed). A capture still in flight is refused, the newest one is saved.
            AppSnapshotStore.revokeInFlight()
            handler.removeCallbacks(autoCheck)
            stageHandler.post { gestureToken++; stage?.finishNow(deliverBack = false); flushPersist() }
        }
    }

    /** An app was uninstalled: its snapshots go with it. */
    private val packageRemoved = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            AppSnapshotStore.removePackage(pkg)
        }
    }

    fun start() {
        AppSnapshotStore.disk = SnapshotDisk(service.noBackupFilesDir, diskIo)
        warmStored()
        service.contentResolver.registerContentObserver(Settings.Global.getUriFor("force_fsg_nav_bar"), false, observer)
        displays.registerDisplayListener(this, handler)
        service.registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_USER_PRESENT) },
            Context.RECEIVER_NOT_EXPORTED)
        service.registerReceiver(packageRemoved, IntentFilter(Intent.ACTION_PACKAGE_FULLY_REMOVED).apply { addDataScheme("package") },
            Context.RECEIVER_NOT_EXPORTED)
        refreshInputMethods()
        // Launch colours are read from other apps' themes: resolve them once, off the main thread,
        // so an icon tap never has to (see SystemShadeAccessibilityService.animateLaunch).
        worker.execute {
            runCatching {
                service.getSystemService(LauncherApps::class.java).getActivityList(null, android.os.Process.myUserHandle())
                    .forEach { SplashColors.color(service, it.componentName) }
            }
        }
        handler.post(monitor)
        busJob?.cancel()
        busJob = scope.launch {
            NavigationProgressBus.homeVisible.collect { visible ->
                val launched = NavigationProgressBus.launchedPackage
                if (!visible) NavigationProgressBus.launchedPackage = null
                onStage { it.setHomeVisible(visible, launched) }
            }
        }
    }

    /** [forget]: the service is being turned off — its snapshots are deleted from disk too. */
    fun stop(forget: Boolean = false) {
        busJob?.cancel(); busJob = null
        scope.cancel()
        worker.shutdownNow(); probe.shutdownNow(); actions.shutdown()
        handler.removeCallbacksAndMessages(null)
        service.contentResolver.unregisterContentObserver(observer)
        runCatching { service.unregisterReceiver(screenOff) }
        runCatching { service.unregisterReceiver(packageRemoved) }
        displays.unregisterDisplayListener(this)
        dialog?.dismiss(); dialog = null
        windowWaiters.clear()
        removeWindows()
        val disk = AppSnapshotStore.disk
        stageHandler.post { flushPersist() }
        stageThread.quitSafely()
        AppSnapshotStore.clear()
        if (forget) disk?.deleteAll()
        // Queued writes finish; nothing new is accepted.
        diskIo.shutdown()
        if (AppSnapshotStore.disk === disk) AppSnapshotStore.disk = null
    }

    /** Runs [block] with the stage on the stage thread (in order with every other stage call). */
    private inline fun onStage(crossinline block: (NavigationStage) -> Unit) {
        stageHandler.post { stage?.let { block(it) } }
    }

    private fun refreshInputMethods() {
        inputMethods = runCatching {
            service.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .enabledInputMethodList.mapTo(HashSet()) { it.packageName }
        }.getOrDefault(emptySet())
    }

    /** A window became active (accessibility TYPE_WINDOW_STATE_CHANGED): only its names are read. */
    fun onWindowStateChanged(packageName: String?, className: String?) {
        // The shade, a system dialog, a heads-up or one of our sheets may lie over the app: no
        // background capture until the app shows a window of its own again.
        if (AutoCapturePolicy.coversApp(packageName, className, service.packageName, MainActivity::class.java.name))
            chromeAt = System.currentTimeMillis()
        val owner = classifyWindow(packageName, className, service.packageName, MainActivity::class.java.name, inputMethods)
        if (owner == WindowOwner.IGNORE) return
        windowEventsSeen = true
        val pkg = if (owner == WindowOwner.APP) packageName else null
        window = WindowSample(pkg, owner == WindowOwner.HOME, System.currentTimeMillis())
        if (windowWaiters.isNotEmpty()) windowWaiters.toList().forEach { (_, waiter) -> waiter(owner, pkg) }
        // The home indicator follows the app in front at once (no 1.5 s poll), and a new window
        // brings a faded indicator back.
        if (pkg != null && !NavigationProgressBus.launcherVisible) onStage { it.setForegroundPackage(pkg, newWindow = true) }
        // The app that was in front has been left: its newest frame goes to disk now.
        stageHandler.post { flushPersist() }
        handler.removeCallbacks(autoCheck)
        if (pkg != null) handler.postDelayed(autoCheck, AutoCapturePolicy.SETTLE_MS + 20L)
    }

    /** True when the overlay can play the icon → app zoom right now (attached, idle, motion allowed). */
    fun canAnimateLaunch(): Boolean {
        val view = stage ?: return false
        return view.sized && !view.busy && !reduceMotion
    }

    override fun onDisplayAdded(displayId: Int) { refresh() }
    override fun onDisplayRemoved(displayId: Int) { refresh() }
    override fun onDisplayChanged(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) refresh() }

    private fun refresh() {
        val enabled = prefs.getBoolean("enabled", true) &&
            service.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME) &&
            Settings.Global.getInt(service.contentResolver, "force_fsg_nav_bar", 0) == 1 &&
            !service.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
            displays.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON
        if (!enabled) { removeWindows(); signature = ""; return }
        val bounds = wm.currentWindowMetrics.bounds
        val density = service.resources.displayMetrics.density
        val next = "${bounds.width()}:${bounds.height()}:$density"
        if (signature == next && views.size == 3 && stageWanted) return
        removeWindows()
        cornerRadius = cornerRadiusDp(bounds.width(), bounds.height(), density)
        fun dp(value: Int) = (value * density).toInt().coerceAtLeast(1)
        try {
            stageWanted = true
            stageHandler.post { addStage() }
            add(NavigationEdge.LEFT, dp(8), (bounds.height() * .60f).toInt(), Gravity.LEFT or Gravity.TOP,
                0, (bounds.height() * .20f).toInt())
            add(NavigationEdge.RIGHT, dp(8), (bounds.height() * .60f).toInt(), Gravity.RIGHT or Gravity.TOP,
                0, (bounds.height() * .20f).toInt())
            add(NavigationEdge.BOTTOM, (bounds.width() * .70f).toInt(), dp(18), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 0)
            signature = next
            // Folded or unfolded: the other panel's shape of every card from disk.
            warmStored()
            Log.i("DuoNavigation", "overlay ready $next radius=$cornerRadius")
        } catch (e: RuntimeException) {
            removeWindows(); signature = ""
            Log.e("DuoNavigation", "Could not attach gesture windows", e)
        }
    }

    /** The panel's physical corner radius; the emulator and some panels report none, so use iPhone-like ones. */
    private fun cornerRadiusDp(width: Int, height: Int, density: Float): Float {
        val display = displays.getDisplay(Display.DEFAULT_DISPLAY)
        val px = display?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
        val dp = px / density
        return if (dp >= 12f) dp else if (width < height) 34f else 28f
    }

    private fun add(edge: NavigationEdge, width: Int, height: Int, gravity: Int, x: Int, y: Int) {
        val view = EdgeView(edge)
        val lp = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            this.gravity = gravity; this.x = x; this.y = y
            title = "DuoNavigation-${edge.name}"
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
        }
        wm.addView(view, lp)
        views.add(view)
    }

    /** Stage thread. */
    private fun addStage() {
        if (stage != null) return
        val view = NavigationStage(service, this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.FILL
            title = "DuoNavigation-Stage"
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
        }
        try {
            // Added from this thread, the window gets its own ViewRootImpl, Choreographer and input here.
            wm.addView(view, lp)
        } catch (e: RuntimeException) {
            Log.e("DuoNavigation", "Could not attach the stage", e)
            handler.post { removeWindows(); signature = "" }
            return
        }
        stage = view
        stageParams = lp
        // App zooms and the switcher run at the panel's top refresh rate, then release it.
        stageRefresh = DisplayRefreshBoost.forWindow(view, wm, lp, stageThread.looper).also { it.attach() }
        view.setHomeVisible(NavigationProgressBus.launcherVisible, null)
    }

    /** Stage thread. */
    private fun removeStage() {
        gestureToken++
        stageRefresh?.detach(); stageRefresh = null
        stage?.let { it.finishNow(deliverBack = false); runCatching { wm.removeViewImmediate(it) } }
        stage = null
        stageParams = null
    }

    private fun removeWindows() {
        views.forEach { it.cancel(); runCatching { wm.removeViewImmediate(it) } }
        views.clear()
        stageWanted = false
        stageHandler.post { removeStage() }
    }

    // ------------------------------------------------------------------ NavigationStageHost

    override fun setStageTouchable(touchable: Boolean) {
        val view = stage ?: return
        val lp = stageParams ?: return
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val next = if (touchable) lp.flags and flag.inv() else lp.flags or flag
        if (next == lp.flags) return
        lp.flags = next
        runCatching { wm.updateViewLayout(view, lp) }
    }

    /** The stage already shows the destination; Android's own window animation would only fight it. */
    private fun quietLaunch(): Bundle =
        ActivityOptions.makeCustomAnimation(service, R.anim.nav_hold, R.anim.nav_hold).toBundle()

    /**
     * Starts [task] under the opaque stage. Android 14+ ignores a cross-task custom animation and
     * would slide the window in under us — still sliding when the stage reveals it (doubled or
     * offset content, a late dim). FLAG_ACTIVITY_NO_ANIMATION is honoured: the window simply
     * appears, at rest, exactly where the card already is.
     */
    override fun launchTask(task: RecentTask, onResult: (Boolean) -> Unit) {
        val component = task.component ?: run { onResult(false); return }
        runAction {
            val ok = try {
                // Bound accessibility services may start activities from the background.
                if (task.user == Process.myUserHandle()) service.startActivity(Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_NO_ANIMATION))
                // A dual-app card: HyperOS sends a plain cross-profile start to its own chooser,
                // which would bring the parent copy forward instead (see startXSpaceClone).
                else if (!(profileKind(service.getSystemService(LauncherApps::class.java), task.user) == ProfileKind.CLONE &&
                        service.startXSpaceClone(component, task.user, null, quietLaunch(), noAnimation = true)))
                    service.getSystemService(LauncherApps::class.java).startMainActivity(component, task.user, null, quietLaunch())
                RecentAppsController(service).recordId(task.id)
                // Opened from a card, not from a widget: a swipe home goes to the app's icon again.
                WidgetLaunchTiles.forget()
                // Home's pending return zoom belonged to the app we just left (checked and dropped atomically).
                AppOpenZoomController.cancelOpening()
                true
            } catch (e: Exception) {
                Log.w("DuoNavigation", "Could not launch $component", e)
                handler.post { showNativeBanner(service, service.getString(R.string.app_unavailable, task.label)) }
                false
            }
            stageHandler.post { onResult(ok) }
        }
    }

    /** Runs a binder call on [actions] (never on the stage thread, whose frames it would hold). */
    private fun runAction(block: () -> Unit) {
        try { actions.execute(block) } catch (_: java.util.concurrent.RejectedExecutionException) { /* stopped */ }
    }

    override fun startHome() {
        runAction {
            val intent = Intent(Intent.ACTION_MAIN).setClassName(service, MainActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION).putExtra("duo_destination", "home")
            try { service.startActivity(intent, quietLaunch()) }
            catch (e: Exception) {
                Log.w("DuoNavigation", "Home intent refused; using the system action", e)
                globalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            }
        }
    }

    override fun performSystemHome() { runAction { globalAction(AccessibilityService.GLOBAL_ACTION_HOME) } }

    // Round 12.1: the window list is a binder call; the stage asks every frame during a hand-off, so
    // one answer serves a few frames (40 ms ≈ HyperOS's transition changes nothing faster than that).
    private var windowPresenceAt = 0L
    private var windowPresencePackage: String? = null
    private var windowPresence = false
    override fun appWindowPresent(packageName: String): Boolean {
        val now = SystemClock.uptimeMillis()
        if (packageName == windowPresencePackage && now - windowPresenceAt < 40L) return windowPresence
        windowPresencePackage = packageName; windowPresenceAt = now
        windowPresence = liveWindowLines().any { it.type == WindowLine.APPLICATION && it.packageName == packageName }
        return windowPresence
    }

    /** r12b-switcher: a transient copy of the live Home (blurred under an open switcher); [onDone] on the stage thread. */
    override fun captureHome(onDone: (HomeSnapshot?) -> Unit) {
        handler.post { HomeSnapshotStore.capture(store = false) { home -> stageHandler.post { onDone(home) } } }
    }

    /** On [actions]. */
    private fun globalAction(action: Int) {
        if (!service.performGlobalAction(action))
            handler.post { showNativeBanner(service, service.getString(R.string.navigation_action_failed)) }
    }

    override fun dismissTask(task: RecentTask) {
        RecentAppsController(service).remove(task.id)
        // Swiped away: its pixels are deleted, in memory and on disk.
        AppSnapshotStore.remove(task)
        stageHandler.post { pendingPersist?.takeIf { it.first == snapshotKey(task.packageName, task.user.hashCode()) }?.let { pendingPersist = null } }
    }

    override fun performBack() { runAction { globalAction(AccessibilityService.GLOBAL_ACTION_BACK) } }

    /**
     * [onShown] once [packageName]'s window is really up: its window-state event (focus follows
     * the first draw closely), else its usage RESUMED event plus a short first-draw allowance, else
     * a timeout. Any other app or Home taking the screen meanwhile ends the wait too. Revealing
     * earlier would show Home (or the previous app) for a moment before a slow app appears.
     */
    override fun awaitForeground(packageName: String, sinceWallClock: Long, onShown: (Boolean) -> Unit) {
        // Called on the stage thread; the window events and waiters live on the main thread, the
        // first-frame allowance runs on the stage thread (a busy main thread cannot stretch it).
        handler.post { awaitOnMain(packageName, sinceWallClock, onShown) }
    }

    /** Main thread; [onShown] runs on the stage thread. */
    private fun awaitOnMain(packageName: String, sinceWallClock: Long, onShown: (Boolean) -> Unit) {
        var done = false
        lateinit var waiter: Pair<String, (WindowOwner, String?) -> Unit>
        fun finish(delay: Long, confirmed: Boolean) {
            if (done) return
            done = true
            windowWaiters.remove(waiter)
            stageHandler.postDelayed({ onShown(confirmed) }, delay)
        }
        waiter = packageName to { owner, pkg ->
            val ours = owner == WindowOwner.APP && pkg == packageName
            finish(if (ours) WINDOW_FIRST_FRAME_DELAY else 0L, confirmed = ours)
        }
        windowWaiters += waiter
        val timeout = if (windowEventsSeen) FOREGROUND_TIMEOUT_WITH_EVENTS else FOREGROUND_TIMEOUT
        handler.postDelayed({ finish(0L, confirmed = false) }, timeout)
        if (!source.hasUsageAccess) {
            if (!windowEventsSeen) finish(FALLBACK_SHOW_DELAY, confirmed = false)
            return
        }
        val deadline = SystemClock.uptimeMillis() + timeout
        val usage = service.getSystemService(UsageStatsManager::class.java)
        fun poll() {
            if (probe.isShutdown || done) return
            probe.execute {
                val shown = runCatching {
                    val events = usage.queryEvents(sinceWallClock - 500L, System.currentTimeMillis() + 1_000L)
                    val event = UsageEvents.Event()
                    var found = false
                    while (events.hasNextEvent()) {
                        events.getNextEvent(event)
                        if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && event.packageName == packageName) found = true
                    }
                    found
                }.getOrDefault(false)
                handler.post {
                    when {
                        done -> Unit
                        // Resumed: the first draw follows; with window events, those say it more precisely.
                        shown -> finish(if (windowEventsSeen) RESUMED_FIRST_FRAME_DELAY_EVENTS else FIRST_FRAME_DELAY, confirmed = true)
                        SystemClock.uptimeMillis() >= deadline -> finish(0L, confirmed = false)
                        else -> handler.postDelayed({ poll() }, 50L)
                    }
                }
            }
        }
        poll()
    }

    override val reduceMotion: Boolean get() = service.getSharedPreferences("duo18_core_preferences", 0)
        .getBoolean("reduce_motion", false) || !ValueAnimator.areAnimatorsEnabled()

    override val screenCornerRadius: Float get() = cornerRadius

    // ------------------------------------------------------------------ gesture plumbing

    /** Stage thread. */
    private fun beginBottom(launcherVisible: Boolean) {
        val view = stage ?: return
        // The switcher is open: a swipe on it, not a new gesture. Its task list and screenshot may still
        // be loading, so the token stays (bumping it dropped them and left a one-card placeholder deck).
        if (view.interactive) { view.bottomDown(launcherVisible); return }
        val token = ++gestureToken
        gestureForeground = null; gestureForegroundPackage = null; gestureSnapshot = null
        gestureTasksLoaded = false; gestureSecure = false
        gestureEpoch = AppSnapshotStore.epoch
        val needsCapture = view.bottomDown(launcherVisible)
        if (view.interactive) return
        // A caught transition says where this gesture is (over its app, or over Home it was returning to).
        val launcherInFront = !view.gestureOverApp
        if (needsCapture) captureForeground(token, retry = true)
        else if (!view.keepsBackdrop) handler.post {
            // Home copies its own window on the main thread.
            HomeSnapshotStore.capture { home -> stageHandler.post { if (token == gestureToken) stage?.setHomeSnapshot(home) } }
        }
        val windowHint = window
        worker.execute {
            val list = runCatching { source.load(launcherInFront, windowHint) }.getOrElse {
                Log.w("DuoNavigation", "Recent apps unavailable", it); RecentTaskList(emptyList(), false, false)
            }
            stageHandler.post {
                if (token != gestureToken) return@post
                gestureTasksLoaded = true
                val current = list.current
                gestureForegroundPackage = current?.packageName
                // Round 12.4: which app the gesture is filed under, and from which source (owner logcat).
                Log.i("DuoNavigation", "gesture over ${current?.packageName ?: "Home"} sure=${list.fileable} " +
                    "window=${windowHint?.packageName ?: if (windowHint?.home == true) "Home" else "none"}")
                // A capture is remembered only under an app *and profile* we are sure of.
                gestureForeground = current?.takeIf { list.fileable }?.let { snapshotKey(it.packageName, it.user.hashCode()) }
                stage?.setTasks(list)
                storeSnapshot()
                warmTasks(list.tasks)
            }
        }
    }

    /** Screenshot of the app under the finger, two frames after the indicator was hidden. */
    private fun captureForeground(token: Int, retry: Boolean) {
        val view = stage ?: return
        if (!SnapshotCapability.granted || AppSnapshotStore.epoch != gestureEpoch) { view.setCapture(null, secure = false); return }
        view.postOnAnimation {
            view.postOnAnimation {
                if (token != gestureToken) return@postOnAnimation
                try {
                    service.takeScreenshot(Display.DEFAULT_DISPLAY, stageExecutor,
                        object : AccessibilityService.TakeScreenshotCallback {
                            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                                val buffer = result.hardwareBuffer
                                // Screen went off (store cleared) or a newer gesture began meanwhile: never use these pixels.
                                if (token != gestureToken || AppSnapshotStore.epoch != gestureEpoch) { buffer.close(); return }
                                val bitmap = runCatching { Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) }.getOrNull()
                                buffer.close()
                                stage?.setCapture(bitmap, secure = false)
                                if (bitmap == null) return
                                worker.execute {
                                    val prepared = AppSnapshotStore.prepare(bitmap)
                                    stageHandler.post {
                                        if (token != gestureToken) return@post
                                        when (prepared) {
                                            is PreparedCapture.Ready -> { gestureSnapshot = prepared.snapshot; storeSnapshot() }
                                            // Black: a FLAG_SECURE window. Never show or keep its pixels.
                                            PreparedCapture.Secure -> {
                                                stage?.markCaptureSecure()
                                                gestureSecure = true; storeSnapshot()
                                            }
                                            // A failed copy says nothing about the app: keep what is shown and stored.
                                            PreparedCapture.Failed -> Unit
                                        }
                                    }
                                }
                            }

                            override fun onFailure(errorCode: Int) {
                                Log.i("DuoNavigation", "screenshot unavailable: $errorCode")
                                if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS)
                                    SnapshotCapability.update(service, false)
                                if (token != gestureToken) return
                                // Android allows one screenshot per ~1/3 s: try once more while the
                                // stage is still transparent (it never photographs its own card).
                                if (retry && errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT &&
                                    stage?.awaitingCapture == true) {
                                    stageHandler.postDelayed({
                                        if (token == gestureToken && stage?.awaitingCapture == true) captureForeground(token, retry = false)
                                        else if (token == gestureToken) stage?.setCapture(null, secure = false)
                                    }, SCREENSHOT_INTERVAL)
                                    return
                                }
                                stage?.setCapture(null, secure = false)
                            }
                        })
                } catch (e: RuntimeException) {
                    Log.w("DuoNavigation", "screenshot failed", e)
                    stage?.setCapture(null, secure = false)
                }
            }
        }
    }

    /** Files the gesture's capture under the foreground app once both are known. */
    private fun storeSnapshot() {
        if (!gestureTasksLoaded) return
        val key = gestureForeground
        if (gestureSecure) {
            // A secure screen's older pixels must not keep showing either.
            key?.let { AppSnapshotStore.remove(it) }
            gestureSecure = false
        }
        val snapshot = gestureSnapshot ?: return
        // Refused if the screen went off since the gesture began.
        if (key != null && AppSnapshotStore.put(key, gestureForegroundPackage ?: return, snapshot, gestureEpoch)) {
            persistedAt[key] = System.currentTimeMillis()
            if (pendingPersist?.first == key) pendingPersist = null
        }
        gestureSnapshot = null
    }

    // ------------------------------------------------------------------ snapshots without a gesture

    /** Main thread: photographs the app in front once it has settled, and again now and then ([AutoCapturePolicy]). */
    private fun maybeAutoCapture() {
        val sample = window ?: return
        if (sample.home || sample.packageName == null || autoInFlight) return
        val view = stage ?: return
        val now = System.currentTimeMillis()
        val clear = SnapshotCapability.granted && signature.isNotEmpty() && !NavigationProgressBus.launcherVisible &&
            view.sized && !view.drawsOverApp &&
            !service.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
            displays.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON
        val powerSave = service.getSystemService(android.os.PowerManager::class.java).isPowerSaveMode
        val last = if (autoShotSample === sample) autoShotAt else null
        if (!AutoCapturePolicy.due(now, sample.at, chromeAt, last, clear, powerSave)) return
        autoInFlight = true
        autoShotSample = sample; autoShotAt = now
        val usageAccess = source.hasUsageAccess
        try {
            worker.execute {
                // Filed only under an app (and profile) we are sure of, as for gesture captures.
                val key = if (autoKeySample === sample) autoKey else runCatching { backgroundSnapshotKey(service, sample, usageAccess) }.getOrNull()
                // Only a certain answer is kept for the window's later captures; an uncertain one is asked again.
                if (key != null) { autoKeySample = sample; autoKey = key }
                if (key == null) handler.post { autoInFlight = false }
                else stageHandler.post { captureBackground(sample, key) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { autoInFlight = false }
    }

    /** Stage thread. A full-screen capture of the app with our home indicator painted out. */
    private fun captureBackground(sample: WindowSample, key: String) {
        val done = { handler.post { autoInFlight = false }; Unit }
        val view = stage
        if (view == null || view.drawsOverApp || window !== sample || NavigationProgressBus.launcherVisible) { done(); return }
        val target = sample.packageName ?: run { done(); return }
        // A dialog, toast or floating window over the app must never become its snapshot.
        if (windowsBlockCapture(target, view)) { done(); return }
        val token = gestureToken
        val epoch = AppSnapshotStore.epoch
        val indicator = view.indicatorRectPx()
        val statusBandY = runCatching {
            service.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars()).top
                .coerceAtLeast((26 * service.resources.displayMetrics.density).toInt()) + 3
        }.getOrDefault(-1)
        val first = autoFiledSample !== sample
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, stageExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    // A gesture, a transition, a new window or a screen-off meanwhile: never use these pixels.
                    if (token != gestureToken || epoch != AppSnapshotStore.epoch || window !== sample || stage?.drawsOverApp != false ||
                        windowsBlockCapture(target, view)) {
                        buffer.close(); done(); return
                    }
                    val bitmap = runCatching { Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) }.getOrNull()
                    buffer.close()
                    if (bitmap == null) { done(); return }
                    try {
                        worker.execute {
                            val prepared = AppSnapshotStore.prepare(bitmap, indicator, topBandY = statusBandY)
                            if (prepared is PreparedCapture.Ready) StatusTint.record(target, prepared.topColor)
                            stageHandler.post {
                                if (token == gestureToken && window === sample) when (prepared) {
                                    is PreparedCapture.Ready -> fileBackground(key, sample, prepared.snapshot, epoch, first)
                                    // Black: a FLAG_SECURE window. Its older pixels must not keep showing either.
                                    PreparedCapture.Secure -> AppSnapshotStore.remove(key)
                                    PreparedCapture.Failed -> Unit
                                }
                                done()
                            }
                        }
                    } catch (_: java.util.concurrent.RejectedExecutionException) { done() }
                }

                override fun onFailure(errorCode: Int) {
                    if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS)
                        SnapshotCapability.update(service, false)
                    done()
                }
            })
        } catch (e: RuntimeException) {
            Log.w("DuoNavigation", "background screenshot failed", e); done()
        }
    }

    /**
     * The live window list says something other than [target] (a system dialog, a toast, a floating
     * window, the shade, another app) is in front of or over it ([captureWindows]). Without a window
     * list (the service does not retrieve window content) this says nothing and the timed rules apply.
     */
    private fun windowsBlockCapture(target: String, view: View): Boolean {
        val verdict = captureWindows(liveWindowLines(), target, service.packageName, view.width, view.height)
        return verdict == CaptureWindows.COVERED || verdict == CaptureWindows.NOT_IN_FRONT
    }

    /** The accessibility window list as plain values (empty when Android does not let the service see windows). */
    private fun liveWindowLines(): List<WindowLine> = runCatching {
        val bounds = android.graphics.Rect()
        service.windows.map { info ->
            info.getBoundsInScreen(bounds)
            val owned = info.type == WindowLine.APPLICATION || info.type == WindowLine.ACCESSIBILITY_OVERLAY
            WindowLine(info.type, info.layer, if (owned) info.root?.packageName?.toString() else null, info.title?.toString(),
                bounds.left, bounds.top, bounds.right, bounds.bottom)
        }
    }.getOrDefault(emptyList())

    /** Stage thread. */
    private fun fileBackground(key: String, sample: WindowSample, snapshot: AppSnapshot, epoch: Int, first: Boolean) {
        val pkg = sample.packageName ?: return
        val now = System.currentTimeMillis()
        val persist = AutoCapturePolicy.persistNow(first, now, persistedAt[key])
        if (!AppSnapshotStore.put(key, pkg, snapshot, epoch, persist)) return
        autoFiledSample = sample
        if (persist) { persistedAt[key] = now; if (pendingPersist?.first == key) pendingPersist = null }
        else pendingPersist = key to snapshot
    }

    /** Stage thread: the newest capture not yet on disk is written (the app was left, the screen went off). */
    private fun flushPersist() {
        val (key, snapshot) = pendingPersist ?: return
        pendingPersist = null
        persistedAt[key] = System.currentTimeMillis()
        AppSnapshotStore.disk?.write(key, snapshot)
    }

    /** Loads the most recent snapshots of this panel's shape (then a few of the other) from disk into memory. */
    private fun warmStored() {
        val disk = AppSnapshotStore.disk ?: return
        val bounds = wm.currentWindowMetrics.bounds
        val landscape = bounds.width() >= bounds.height()
        runCatching {
            diskIo.execute {
                val stored = disk.stored()
                val order = stored.filter { it.second == landscape }.take(12) + stored.filter { it.second != landscape }.take(4)
                var loaded = 0
                for ((key, shape) in order) {
                    if (AppSnapshotStore.has(key, shape)) continue
                    val snap = disk.read(key, shape) ?: continue
                    if (AppSnapshotStore.restore(key, key.substringBefore('#'), snap)) loaded++
                }
                if (loaded > 0) onStage { it.invalidate() }
            }
        }
    }

    /** Stage thread: the switcher's cards that are not in memory yet are read from disk (then redrawn). */
    private fun warmTasks(tasks: List<RecentTask>) {
        val disk = AppSnapshotStore.disk ?: return
        val view = stage ?: return
        val landscape = view.width >= view.height
        val missing = tasks.map { snapshotKey(it.packageName, it.user.hashCode()) }
            .filter { !AppSnapshotStore.has(it, landscape) && !AppSnapshotStore.has(it, !landscape) }
        if (missing.isEmpty()) return
        runCatching {
            diskIo.execute {
                var loaded = 0
                for (key in missing) {
                    val snap = disk.read(key, landscape) ?: disk.read(key, !landscape) ?: continue
                    if (AppSnapshotStore.restore(key, key.substringBefore('#'), snap)) loaded++
                }
                if (loaded > 0) onStage { it.invalidate() }
            }
        }
    }

    /**
     * Which app is in front, for the home indicator's light/dark choice only: the package name of
     * the latest resume event in the last few seconds (usage access, which the recents already
     * use). Its splash colour stands in until a first snapshot gives the real brightness.
     */
    private fun pollForeground() {
        val view = stage ?: return
        // Window events name the app in front directly; usage polling is only the fallback.
        if (windowEventsSeen) return
        if (NavigationProgressBus.launcherVisible || view.busy || probe.isShutdown || !source.hasUsageAccess) return
        probe.execute {
            val pkg = runCatching {
                val now = System.currentTimeMillis()
                val events = service.getSystemService(UsageStatsManager::class.java).queryEvents(now - 4_000L, now)
                val event = UsageEvents.Event()
                var last: String? = null
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = event.packageName
                }
                last
            }.getOrNull() ?: return@execute
            if (pkg == service.packageName) return@execute
            if (AppSnapshotStore.luma(pkg) == null && SplashColors.forPackage(pkg) == null) runCatching {
                service.getSystemService(LauncherApps::class.java).getActivityList(pkg, android.os.Process.myUserHandle())
                    .firstOrNull()?.let { SplashColors.color(service, it.componentName) }
            }
            onStage { it.setForegroundPackage(pkg) }
        }
    }

    /**
     * Overlay-drawn icon → app zoom for a launch from Home (see MainActivity.launchApp). Returns
     * false when the stage cannot take it now; otherwise [onStarted] follows on the main thread:
     * true once the card is on screen (Home may hide the icon), false if the stage refused.
     */
    fun launchFromIcon(iconScreenPx: ZoomBounds, task: RecentTask, icon: Bitmap, home: HomeSnapshot?,
        onStarted: (Boolean) -> Unit, widgetCornerPx: Float? = null): Boolean {
        if (!canAnimateLaunch()) return false
        stageHandler.post {
            val started: (Boolean) -> Unit = { ok -> handler.post { onStarted(ok) } }
            if (stage?.launchFromIcon(iconScreenPx, task, icon, home, started, widgetCornerPx) != true) started(false)
        }
        return true
    }

    fun setLaunchBackdrop(home: HomeSnapshot) { onStage { it.setLaunchBackdrop(home) } }

    fun cancelLaunch() { onStage { it.cancelLaunch() } }

    fun showRecovery() {
        if (dialog?.isShowing == true) return
        val canEnable = Settings.Global.getInt(service.contentResolver, "force_fsg_nav_bar", 0) == 1 ||
            service.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        dialog = nativeDialogBuilder(service)
            .setTitle(R.string.navigation_title)
            .setMessage(R.string.navigation_help)
            .setPositiveButton(R.string.navigation_restore) { _, _ ->
                try {
                    check(Settings.Global.putInt(service.contentResolver, "force_fsg_nav_bar", 0))
                    prefs.edit().putBoolean("enabled", false).apply()
                    refresh()
                } catch (_: Exception) {
                    showNativeBanner(service, service.getString(R.string.navigation_restore_settings), long = true)
                    service.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            .setNeutralButton(if (canEnable) R.string.navigation_enable else R.string.wireless_title) { _, _ ->
                enableFromSetup()
            }
            .setNegativeButton(android.R.string.cancel, null).create().also {
                it.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                it.setOnDismissListener { dialog = null }
                it.show(); styleNativeDialog(it)
            }
    }

    internal fun enableFromSetup() {
        when (enableNavigation(
            defaultHome = service.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME),
            gestureMode = { Settings.Global.getInt(service.contentResolver, "force_fsg_nav_bar", 0) == 1 },
            requestGestureMode = { Settings.Global.putInt(service.contentResolver, "force_fsg_nav_bar", 1) },
            enableOverlay = { prefs.edit().putBoolean("enabled", true).apply(); refresh() },
            hideGestureLine = { Settings.Global.putInt(service.contentResolver, "hide_gesture_line", 1) },
        )) {
            NavigationEnableResult.ENABLED -> showNativeBanner(service, service.getString(R.string.navigation_enabled_check), long = true)
            NavigationEnableResult.DEFAULT_HOME_REQUIRED -> showNativeBanner(service, service.getString(R.string.navigation_default_required), long = true)
            NavigationEnableResult.SYSTEM_MODE_REQUIRED -> {
                SystemShadeAccessibilityService.showWirelessSetup()
            }
        }
    }

    private inner class EdgeView(private val edge: NavigationEdge) : View(service) {
        private val gesture = NavigationGesture(edge)
        private val density = resources.displayMetrics.density
        private var engagedReported = false
        /** Panel size in dp, read once per touch (currentWindowMetrics is an IPC on some releases). */
        private var panelW = 0f
        private var panelH = 0f
        private val tick = object : Runnable {
            override fun run() {
                val now = SystemClock.uptimeMillis()
                val action = gesture.tick(now)
                if (action == NavigationAction.RECOVERY) {
                    onStage { it.bottomCancel() }
                    showRecovery()
                    return
                }
                val progress = gesture.progress(now)
                if (!progress.active) return
                if (progress.engaged && !engagedReported) { engagedReported = true; onStage { it.bottomEngaged() } }
                this@EdgeView.postOnAnimation(this)
            }
        }
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        fun cancel() {
            val wasActive = gesture.progress(SystemClock.uptimeMillis()).active
            gesture.cancel()
            // View.removeCallbacks handles both attached and queued callbacks.
            removeCallbacks(tick)
            if (!wasActive) return
            if (edge == NavigationEdge.BOTTOM) onStage { it.bottomCancel() } else onStage { it.releaseBack() }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.rawX / density
            val y = event.rawY / density
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancel()
                    engagedReported = false
                    val metrics = wm.currentWindowMetrics.bounds
                    panelW = metrics.width() / density; panelH = metrics.height() / density
                    gesture.down(x, y, event.eventTime, panelW, panelH)
                    if (edge == NavigationEdge.BOTTOM) {
                        val launcherVisible = NavigationProgressBus.launcherVisible
                        stageHandler.post { beginBottom(launcherVisible) }
                        postOnAnimation(tick)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    gesture.move(x, y, event.eventTime)
                    val progress = gesture.progress(event.eventTime)
                    if (edge == NavigationEdge.BOTTOM) onStage { it.bottomMove(progress) } else onStage { it.showBack(progress) }
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(tick)
                    gesture.move(x, y, event.eventTime)
                    val progress = gesture.progress(event.eventTime)
                    val action = gesture.up(event.eventTime)
                    if (edge == NavigationEdge.BOTTOM) {
                        if (action == NavigationAction.RECOVERY) { onStage { it.bottomCancel() }; showRecovery() }
                        else onStage { it.bottomUp(action, progress) }
                    } else {
                        onStage { it.releaseBack() }
                        if (action == NavigationAction.BACK) back()
                    }
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancel()
            }
            return true
        }

        private fun back() {
            // The stage closes the switcher, or holds the Back for an app still opening under it.
            stageHandler.post { if (stage?.back() != true) performBack() }
        }
    }

    private companion object {
        const val FALLBACK_SHOW_DELAY = 280L
        const val FIRST_FRAME_DELAY = 90L
        /** Window focus arrives with (or just before) the first frame. */
        const val WINDOW_FIRST_FRAME_DELAY = 48L
        /** Resumed but no window event yet: allow the first draw, window events will usually win. */
        const val RESUMED_FIRST_FRAME_DELAY_EVENTS = 260L
        const val FOREGROUND_TIMEOUT = 1_200L
        /** With window events we know exactly when the app is up, so a slow cold start may take longer. */
        const val FOREGROUND_TIMEOUT_WITH_EVENTS = 2_400L
        /** Android's minimum interval between accessibility screenshots (1/3 s) plus a margin. */
        const val SCREENSHOT_INTERVAL = 345L
    }
}
