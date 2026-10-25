package com.jake.duolauncher

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.core.animate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Process-wide state of the unlock → Home arrival ([UnlockArrivalMotion], [UnlockArrivalPolicy]).
 *
 * [progress] is snapshot state written synchronously on the main thread when Home is armed, so the
 * very first frame Home draws after the keyguard leaves is already the start state — nothing pops.
 * Only draw-phase lambdas ([unlockArrivalContent], [unlockArrivalChrome], the wallpaper layer) read
 * it: the arrival never recomposes Home.
 */
internal object UnlockArrival {
    private const val TAG = "DuoUnlockArrival"
    private val policy = UnlockArrivalPolicy()
    private val main = Handler(Looper.getMainLooper())

    /** 0 = start state, 1 = rest. */
    val progress = mutableFloatStateOf(1f)
    /** The Dock/Search layer's own progress (bouncier spring, `UnlockArrivalMotion.chromeSpring`). */
    val chromeProgress = mutableFloatStateOf(1f)
    /** Incremented to ask [UnlockArrivalEffect] to animate [progress] to rest. */
    internal val playRequests = mutableIntStateOf(0)
    @Volatile internal var fastFinish = false
        private set
    internal var velocity = 0f

    internal var homeResumed = false
        private set
    internal var homeStopped = false
        private set
    private var pausedForSleepAt: Long? = null

    val phase: ArrivalPhase get() = policy.phase

    fun frame(): UnlockArrivalFrame = UnlockArrivalMotion.frame(progress.floatValue, LauncherMotion.reduced)
    fun chromeFrame(): UnlockArrivalFrame = UnlockArrivalMotion.chromeFrame(chromeProgress.floatValue, LauncherMotion.reduced)

    /** Round 11: the keyguard reported unlocked (KeyguardManager listener, API 33+). */
    internal fun onKeyguardUnlocked(context: Context) {
        Log.d(TAG, "keyguard unlocked: phase=${policy.phase} resumed=$homeResumed focused=$homeFocused")
        run(policy.keyguardUnlocked(homeResumed, homeFocused), context)
    }

    /** Round 11: DisplayManager says the default display went off/dozing (no broadcast needed). */
    internal fun onDisplayOff(context: Context, homeInFront: Boolean) {
        Log.d(TAG, "display off: homeInFront=$homeInFront stopped=$homeStopped")
        run(policy.displayOff(homeInFront, homeHidden = homeStopped), context)
    }

    /**
     * For the 18Duo lock screen (r8-lock): call from `KeyguardDismissCallback.onDismissSucceeded`.
     * The arrival then plays as soon as Home is resumed (also when that happens after this call).
     * Equivalent to ACTION_USER_PRESENT, which Home also listens for; calling both is harmless.
     */
    fun keyguardDismissed() = main.post { run(policy.lockScreenHandoff(homeResumed, homeFocused)) }

    /** Any touch on Home while it arrives: hand it to the finger now (iOS lets you tap mid-arrival). */
    fun onUserTouch() { if (policy.phase == ArrivalPhase.PLAYING) run(policy.touched()) }

    internal fun finished(request: Int) {
        if (request != playRequests.intValue) return
        policy.finished(); fastFinish = false; velocity = 0f; progress.floatValue = 1f; chromeProgress.floatValue = 1f
    }

    // ---------------------------------------------------------------- events (main thread)

    internal fun onScreenOff(context: Context, homeInFront: Boolean, displayLit: Boolean) {
        Log.d(TAG, "screen off: homeInFront=$homeInFront stopped=$homeStopped lit=$displayLit")
        run(policy.screenOff(homeInFront, homeHidden = homeStopped || !displayLit), context)
    }

    internal fun onScreenOn(context: Context) {
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        Log.d(TAG, "screen on: phase=${policy.phase} keyguardLocked=$locked resumed=$homeResumed")
        run(policy.screenOn(locked, homeResumed), context)
    }

    internal fun onUserPresent(context: Context) { Log.d(TAG, "user present: resumed=$homeResumed"); run(policy.userPresent(homeResumed, homeFocused), context) }

    internal fun onHomeResumed(context: Context) {
        homeResumed = true; homeStopped = false; pausedForSleepAt = null
        val locked = keyguardLocked(context)
        Log.d(TAG, "home resumed: phase=${policy.phase} keyguardLocked=$locked")
        run(policy.homeResumed(homeFocused, locked), context)
    }

    /** MainActivity.onWindowFocusChanged. */
    internal fun onHomeFocusChanged(context: Context, focused: Boolean) {
        homeFocused = focused
        Log.d(TAG, "focus=$focused phase=${policy.phase} resumed=$homeResumed")
        if (focused) run(policy.homeFocused(homeResumed), context)
    }

    private var homeFocused = false

    internal fun onHomePaused(context: Context, interactive: Boolean, homeInFront: Boolean) {
        homeResumed = false
        val locked = keyguardLocked(context)
        // Paused for the lock (dark panel or keyguard up): counts as "Home in front" for a while.
        pausedForSleepAt = if (!interactive || locked) SystemClock.uptimeMillis() else null
        Log.d(TAG, "home paused: phase=${policy.phase} interactive=$interactive keyguardLocked=$locked inFront=$homeInFront")
        run(policy.homePaused(homeInFront, locked, interactive), context)
    }

    internal fun onHomeStopped(context: Context, homeInFront: Boolean) {
        homeStopped = true
        val locked = keyguardLocked(context)
        val lit = interactive(context)
        Log.d(TAG, "home stopped: phase=${policy.phase} keyguardLocked=$locked interactive=$lit inFront=$homeInFront")
        run(policy.homeStopped(homeInFront, locked, lit), context)
    }

    internal fun onHomeStarted() { homeStopped = false }

    internal fun homeWasInFrontNow(appOpening: Boolean, standby: Boolean): Boolean =
        homeWasInFront(homeResumed, pausedForSleepAt, SystemClock.uptimeMillis(), appOpening, standby)

    private val armDeadline = Runnable { appContext?.let { run(policy.armDeadline(interactive(it), keyguardLocked(it)), it) } }
    private val presentTimeout = Runnable { run(policy.presentTimeout(homeResumed)) }
    private val focusFallback = Runnable { run(policy.focusFallback(homeResumed)) }
    private var appContext: Context? = null

    private fun run(command: ArrivalCommand, context: Context? = null) {
        if (context != null) appContext = context.applicationContext
        if (command != ArrivalCommand.NONE) Log.d(TAG, "$command → ${policy.phase}")
        when (command) {
            ArrivalCommand.NONE -> Unit
            ArrivalCommand.ARM -> {
                main.removeCallbacks(armDeadline); main.removeCallbacks(presentTimeout)
                fastFinish = false; velocity = 0f
                playRequests.intValue++ // cancels an arrival still settling
                progress.floatValue = 0f; chromeProgress.floatValue = 0f
                policy.lockHandoff = false
                Log.d(TAG, "armed")
            }
            ArrivalCommand.SCHEDULE_ARM -> { main.removeCallbacks(armDeadline); main.postDelayed(armDeadline, ARM_DEADLINE_MS) }
            ArrivalCommand.PLAY -> {
                main.removeCallbacks(armDeadline); main.removeCallbacks(presentTimeout); main.removeCallbacks(focusFallback)
                fastFinish = false
                playRequests.intValue++
                Log.d(TAG, "play")
            }
            ArrivalCommand.SCHEDULE_FOCUS_FALLBACK -> {
                main.removeCallbacks(focusFallback)
                main.postDelayed(focusFallback, (FOCUS_FALLBACK_MS * animatorScale()).toLong())
            }
            // × the animator scale: the 18Duo lock screen lets its slab clear (also slowed) before Home shows.
            ArrivalCommand.SCHEDULE_TIMEOUT -> { main.removeCallbacks(presentTimeout); main.postDelayed(presentTimeout, (PRESENT_TIMEOUT_MS * animatorScale()).toLong()) }
            ArrivalCommand.DISARM -> {
                main.removeCallbacks(armDeadline); main.removeCallbacks(presentTimeout)
                fastFinish = false; velocity = 0f
                playRequests.intValue++ // cancels a running animation
                progress.floatValue = 1f; chromeProgress.floatValue = 1f
                policy.finished()
                Log.d(TAG, "disarmed")
            }
            ArrivalCommand.FINISH_FAST -> { fastFinish = true; playRequests.intValue++ }
        }
    }

    private fun animatorScale(): Float = appContext?.let {
        runCatching { android.provider.Settings.Global.getFloat(it.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrNull()
    }?.coerceIn(.5f, 10f) ?: 1f

    private fun keyguardLocked(context: Context) =
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private fun interactive(context: Context) =
        context.getSystemService(PowerManager::class.java)?.isInteractive != false
}

/**
 * Wires [UnlockArrival] to Home's activity: its lifecycle (paused/resumed/stopped, read together with
 * the keyguard and display state), DisplayManager's display-state changes, KeyguardManager's
 * locked-state listener (API 33+) and — as an extra source only — the screen and keyguard
 * broadcasts, which HyperOS does not deliver to the launcher. Attached once from
 * MainActivity.onCreate; unregisters itself on destroy.
 */
internal class UnlockArrivalWatcher private constructor(private val activity: MainActivity) : DefaultLifecycleObserver {
    private fun homeInFront(): Boolean = UnlockArrival.homeWasInFrontNow(
        appOpening = AppOpenZoomController.state.value != null,
        standby = activity.panelOrientation.surface.value != LauncherSurface.HOME)
    private fun displayOn(): Boolean = activity.window?.decorView?.display?.state == android.view.Display.STATE_ON

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> UnlockArrival.onScreenOff(activity, homeInFront(), displayLit = displayOn())
                Intent.ACTION_USER_PRESENT -> UnlockArrival.onUserPresent(activity)
                // Without a keyguard Home never pauses: it arrives when the panel lights up again.
                Intent.ACTION_SCREEN_ON -> UnlockArrival.onScreenOn(activity)
            }
        }
    }
    private var lastDisplayState = android.view.Display.STATE_UNKNOWN
    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != android.view.Display.DEFAULT_DISPLAY) return
            val state = activity.getSystemService(android.hardware.display.DisplayManager::class.java)
                ?.getDisplay(displayId)?.state ?: return
            if (state == lastDisplayState) return
            val wasOn = lastDisplayState == android.view.Display.STATE_ON || lastDisplayState == android.view.Display.STATE_UNKNOWN
            lastDisplayState = state
            when {
                state != android.view.Display.STATE_ON && wasOn -> UnlockArrival.onDisplayOff(activity, homeInFront())
                state == android.view.Display.STATE_ON -> UnlockArrival.onScreenOn(activity)
            }
        }
    }
    private var keyguardListener: Any? = null

    override fun onCreate(owner: LifecycleOwner) {
        // Protected system broadcasts: only the system can send them, so exporting the receiver is safe
        // and gives HyperOS one more chance to deliver them.
        ContextCompat.registerReceiver(activity, receiver, IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
            addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_ON)
        }, ContextCompat.RECEIVER_EXPORTED)
        runCatching {
            activity.getSystemService(android.hardware.display.DisplayManager::class.java)
                ?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) runCatching {
            val listener = KeyguardManager.KeyguardLockedStateListener { locked ->
                if (!locked) UnlockArrival.onKeyguardUnlocked(activity)
            }
            activity.getSystemService(KeyguardManager::class.java)
                ?.addKeyguardLockedStateListener(ContextCompat.getMainExecutor(activity), listener)
            keyguardListener = listener
        }
    }
    override fun onStart(owner: LifecycleOwner) = UnlockArrival.onHomeStarted()
    override fun onResume(owner: LifecycleOwner) = UnlockArrival.onHomeResumed(activity)
    override fun onPause(owner: LifecycleOwner) {
        // Read "in front" before the resumed flag drops: a pause from a resumed Home is in front
        // unless an app is zooming open or StandBy is up.
        val inFront = homeInFront()
        UnlockArrival.onHomePaused(activity, activity.getSystemService(PowerManager::class.java)?.isInteractive != false, inFront)
    }
    override fun onStop(owner: LifecycleOwner) = UnlockArrival.onHomeStopped(activity, homeInFront())
    override fun onDestroy(owner: LifecycleOwner) {
        runCatching { activity.unregisterReceiver(receiver) }
        runCatching { activity.getSystemService(android.hardware.display.DisplayManager::class.java)?.unregisterDisplayListener(displayListener) }
        if (android.os.Build.VERSION.SDK_INT >= 33) (keyguardListener as? KeyguardManager.KeyguardLockedStateListener)?.let {
            runCatching { activity.getSystemService(KeyguardManager::class.java)?.removeKeyguardLockedStateListener(it) }
        }
        activity.lifecycle.removeObserver(this)
    }

    companion object {
        /** Call from MainActivity.onCreate; the observer receives ON_CREATE itself. */
        fun attach(activity: MainActivity) { activity.lifecycle.addObserver(UnlockArrivalWatcher(activity)) }
    }
}

/** Runs the arrival springs in Home's composition (so the system animator scale applies to them). */
@Composable
internal fun UnlockArrivalEffect() {
    val request = UnlockArrival.playRequests.intValue
    LaunchedEffect(request) {
        if (request == 0 || UnlockArrival.phase != ArrivalPhase.PLAYING) return@LaunchedEffect
        val spring = when {
            UnlockArrival.fastFinish -> UnlockArrivalMotion.finishSpring
            LauncherMotion.reduced -> UnlockArrivalMotion.reducedSpring
            else -> UnlockArrivalMotion.spring
        }
        val chromeSpring = when {
            UnlockArrival.fastFinish -> UnlockArrivalMotion.finishSpring
            LauncherMotion.reduced -> UnlockArrivalMotion.reducedSpring
            else -> UnlockArrivalMotion.chromeSpring
        }
        kotlinx.coroutines.coroutineScope {
            launch {
                animate(UnlockArrival.chromeProgress.floatValue, 1f, 0f,
                    LauncherMotion.springSpec(chromeSpring, visibilityThreshold = .0005f)) { value, _ ->
                    UnlockArrival.chromeProgress.floatValue = value
                }
            }
            animate(UnlockArrival.progress.floatValue, 1f, UnlockArrival.velocity,
                LauncherMotion.springSpec(spring, visibilityThreshold = .0005f)) { value, velocity ->
                UnlockArrival.progress.floatValue = value
                UnlockArrival.velocity = velocity
            }
        }
        UnlockArrival.finished(request)
    }
}

/** Pages, widgets and Today: one layer scaled about its centre (the panel centre) and faded in. */
internal fun Modifier.unlockArrivalContent(): Modifier = graphicsLayer {
    val frame = UnlockArrival.frame()
    scaleX = frame.contentScale
    scaleY = frame.contentScale
    alpha = frame.contentAlpha
}

/**
 * The Dock and the Search button, drawn in their own layers: moved away from the panel centre and
 * scaled exactly like the content layer, so they arrive together with the icons (from beyond the
 * right edge on the cover, as in photo 2). [offsetFromPanelCenterDp] is the element centre minus
 * the panel centre, in dp.
 */
internal fun Modifier.unlockArrivalChrome(offsetFromPanelCenterDp: () -> Offset): Modifier = graphicsLayer {
    val frame = UnlockArrival.chromeFrame()
    if (frame.contentScale != 1f) {
        val offset = offsetFromPanelCenterDp()
        translationX = frame.displacement(offset.x.dp.toPx(), 0f)
        translationY = frame.displacement(offset.y.dp.toPx(), 0f)
    }
    scaleX = frame.contentScale
    scaleY = frame.contentScale
    alpha = frame.contentAlpha
}

/** The status rail (time + ring): in place, fading in at once (recording f107: there on the first Home frame). */
internal fun Modifier.unlockArrivalStatus(): Modifier = graphicsLayer { alpha = UnlockArrival.frame().statusAlpha }
