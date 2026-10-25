package com.jake.duolauncher

import android.app.Activity
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The 18Duo lock screen: an ordinary activity shown OVER Android's keyguard (showWhenLocked). It holds
 * no secret and never unlocks anything itself. Swipe up asks the system to dismiss the keyguard
 * ([KeyguardManager.requestDismissKeyguard]: HyperOS's own PIN/pattern bouncer, fingerprint, face).
 * It leaves the moment the device is unlocked by any path, and Back always reveals the system keyguard
 * (emergency call, HyperOS shortcuts) — the user can never be trapped here.
 */
class LockScreenActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var keyguard: KeyguardManager
    private lateinit var prefs: LockScreenPreferences
    private var torch: LockScreenTorch? = null
    private var leaving = false
    private var dismissPending = false
    /** Round 11: the customisation editor ("自定锁屏") — from Settings (no keyguard) or after a long-press unlock. */
    private val editing = mutableStateOf(false)
    private var editAfterDismiss = false
    /** The keyguard is gone; Home takes over once the slab has cleared (or [SLAB_CLEAR_GRACE_MS] × animator scale). */
    private var dismissed = false
    private var slabCleared = false
    private val slabGrace = Runnable { if (dismissed) leave("dismissed, slab grace") }
    private val config = mutableStateOf(LockScreenConfig.DEFAULT)
    /** Bumped when a dismiss request ends without unlocking: the UI springs its content back. */
    internal val cancelledRequests = mutableIntStateOf(0)
    /** Round 13: bumped when the keyguard went away by another path (fingerprint): the slab rides out first. */
    internal val exitRequests = mutableIntStateOf(0)
    internal val torchOn = mutableStateOf(false)
    internal val torchAvailable = mutableStateOf(false)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT) { if (!editing.value && !dismissPending && !dismissed) unlockedElsewhere("user present") }
            else if (intent.action == Intent.ACTION_SCREEN_ON) applySystemBars()
        }
    }
    private val guard = object : Runnable {
        override fun run() {
            if (editing.value) return
            // While our own dismiss request is in flight (or granted and the slab is clearing), the
            // callback and its grace decide; the guard only catches unlocks by other paths.
            if (!dismissPending && !dismissed && lockScreenShouldLeave(keyguard.isKeyguardLocked, prefs.enabled)) {
                if (prefs.enabled) unlockedElsewhere("keyguard unlocked") else leave("disabled")
                return
            }
            handler.postDelayed(this, 200L)
        }
    }
    /** A dismiss request that shows no bouncer and reports nothing: hand over to the system keyguard. */
    private val dismissWatchdog = Runnable {
        if (dismissPending && hasWindowFocus()) { Log.w(TAG, "No keyguard response; revealing the system keyguard"); leave("watchdog") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keyguard = getSystemService(KeyguardManager::class.java)
        prefs = LockScreenPreferences(this)
        config.value = prefs.config
        val editMode = intent?.getBooleanExtra(EXTRA_EDIT, false) == true
        if (editMode) editing.value = true
        else if (!keyguard.isKeyguardLocked || !prefs.enabled) { prefs.markSeen("not locked at start"); leave("not locked at start"); return }
        if (!editMode) LockScreenController.showing.value = true
        // Home normally set these already (same process); a cold start through the lock screen must too.
        LauncherMotion.reduced = CorePreferences(this).reduceMotion
        LauncherMotion.attachSystemSettings(this)
        if (!editMode) setShowWhenLocked(true)
        // No close animation, registered up front (finish() collects the transition before leave() could override it).
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        applySystemBars()
        torch = LockScreenTorch(this, torchOn, torchAvailable)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (editing.value) finishEditor() else leave("back") }
        })
        if (!editMode) ContextCompat.registerReceiver(this, receiver,
            IntentFilter(Intent.ACTION_USER_PRESENT).apply { addAction(Intent.ACTION_SCREEN_ON) }, ContextCompat.RECEIVER_EXPORTED)
        val dark = AppearanceStore(this).state.dark
        setContent {
            DuoTheme(dark) {
                val editor = if (editing.value) remember {
                    LockScreenEditorState(config.value, onDone = { saved ->
                        prefs.config = saved; config.value = saved; finishEditor()
                    }, onCancel = ::finishEditor)
                } else null
                LockScreen(
                    config = editor?.draft ?: config.value,
                    torchOn = torchOn.value, torchAvailable = torchAvailable.value,
                    cancelledRequests = cancelledRequests.intValue,
                    onFirstFrame = { if (!editing.value) prefs.markSeen("first frame") },
                    onUnlock = ::requestUnlock,
                    onFlashlight = { torch?.toggle() },
                    onCamera = ::openCamera,
                    onEditRequest = ::requestEditor,
                    onSlabCleared = ::onSlabCleared,
                    exitRequests = exitRequests.intValue,
                    editor = editor,
                )
            }
        }
    }

    /** Long-press on the live lock screen: the system unlocks first (iOS: Face ID), then the editor opens here. */
    private fun requestEditor() {
        if (leaving || editing.value || dismissPending) return
        if (!keyguard.isKeyguardLocked) { editing.value = true; return }
        editAfterDismiss = true
        requestUnlock()
    }

    private fun finishEditor() {
        if (leaving) return
        leaving = true
        Log.i(TAG, "Lock screen editor closes")
        handler.removeCallbacksAndMessages(null)
        finish()
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(guard)
        handler.post(guard)
    }

    /**
     * Round 12.4: the lock screen keeps the system status bar (iOS shows carrier, signal and battery over
     * the Lock Screen; the owner's recording f001) and hides only the navigation bar; the editor hides
     * both, its Cancel / Done pills take that row (iOS 26). A swipe from the top still brings the shade.
     */
    private fun applySystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (editing.value) hide(WindowInsetsCompat.Type.systemBars())
            else { hide(WindowInsetsCompat.Type.navigationBars()); show(WindowInsetsCompat.Type.statusBars()) }
        }
    }

    override fun onResume() {
        super.onResume()
        applySystemBars()
        // Round 12: resuming over a locked keyguard already proves the launch path (a frame may be
        // throttled while the panel is dark); the self-protection no longer waits for the first draw.
        if (!editing.value && !leaving && keyguard.isKeyguardLocked) prefs.markSeen("resumed over keyguard")
    }

    override fun onStop() {
        handler.removeCallbacks(guard)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBars()
    }

    /** Swipe up released past the threshold (or a long-press asking for the editor). */
    private fun requestUnlock() {
        if (leaving) return
        if (!keyguard.isKeyguardLocked) { if (editAfterDismiss) openEditorUnlocked() else leave("already unlocked"); return }
        dismissPending = true
        handler.removeCallbacks(dismissWatchdog)
        handler.postDelayed(dismissWatchdog, 2500L)
        try {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    dismissPending = false
                    if (editAfterDismiss) { openEditorUnlocked(); return }
                    UnlockArrival.keyguardDismissed()
                    // The recording hands over while the slab is still clearing the top (f106): let the
                    // exit finish, bounded by a short grace so a stalled animation never holds the keyguard's exit.
                    dismissed = true
                    if (slabCleared) leave("dismissed") else handler.postDelayed(slabGrace, (SLAB_CLEAR_GRACE_MS * animatorScale()).toLong())
                }
                override fun onDismissCancelled() {
                    dismissPending = false; editAfterDismiss = false; handler.removeCallbacks(dismissWatchdog)
                    if (!keyguard.isKeyguardLocked) leave("unlocked after cancel") else cancelledRequests.intValue++
                }
                override fun onDismissError() { dismissPending = false; editAfterDismiss = false; leave("dismiss error") }
            })
        } catch (error: RuntimeException) {
            Log.w(TAG, "requestDismissKeyguard failed", error)
            leave("dismiss threw")
        }
    }

    /**
     * Round 13: unlocked without our swipe — the side-key fingerprint (4 of 5 exits in the Core.30.1 phone log
     * end "user present" / "keyguard unlocked"). Core.30 finished in one frame; iOS (Touch ID) lifts the Lock
     * Screen away exactly as a swipe does. Play the same exit, then hand over (bounded by [AUTO_EXIT_GRACE_MS]).
     */
    private fun unlockedElsewhere(reason: String) {
        if (leaving || dismissed) return
        UnlockArrival.keyguardDismissed()
        if (!hasWindowFocus()) { leave(reason); return }
        Log.i(TAG, "Unlocked elsewhere ($reason): slab exits")
        dismissed = true
        exitRequests.intValue++
        handler.removeCallbacks(slabGrace)
        handler.postDelayed(slabGrace, (AUTO_EXIT_GRACE_MS * animatorScale()).toLong())
    }

    private fun onSlabCleared() {
        slabCleared = true
        if (dismissed) { handler.removeCallbacks(slabGrace); leave("dismissed, slab cleared") }
    }

    private fun animatorScale(): Float = runCatching {
        android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f).coerceIn(.5f, 10f)

    /** The keyguard is gone after a long-press: stay, as an ordinary activity, and show the editor. */
    private fun openEditorUnlocked() {
        editAfterDismiss = false
        handler.removeCallbacks(dismissWatchdog); handler.removeCallbacks(guard)
        runCatching { unregisterReceiver(receiver) }
        LockScreenController.showing.value = false
        editing.value = true
        applySystemBars()
        Log.i(TAG, "Lock screen editor opens after unlock")
    }

    /** iOS: the camera opens over the Lock Screen without unlocking (the secure camera intent). */
    private fun openCamera() {
        val secure = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE)
        val target = if (keyguard.isKeyguardSecure && secure.resolveActivity(packageManager) != null) secure
            else Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        try { startActivity(target) }
        catch (error: ActivityNotFoundException) { Log.w(TAG, "No camera", error) }
        catch (error: SecurityException) { Log.w(TAG, "Camera refused", error) }
    }

    private fun leave(reason: String) {
        if (leaving) return
        leaving = true
        Log.i(TAG, "Lock screen leaves: $reason")
        handler.removeCallbacksAndMessages(null)
        finish()
        // No close animation: Home (same wallpaper) is already there underneath and plays its arrival.
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        else @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (LockScreenController.showing.value && ::prefs.isInitialized) {
            // A normal finish (unlock, Back) proves the path works even if no frame was drawn yet.
            if (isFinishing) prefs.markSeen("finished normally")
            LockScreenController.showing.value = false
        }
        runCatching { unregisterReceiver(receiver) }
        torch?.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "18DuoLockScreen"
        /** Longest the activity stays after the keyguard is dismissed while the slab finishes its exit. */
        const val SLAB_CLEAR_GRACE_MS = 600L // round 13: the exit now runs to 0.97 × 0.90 H (≈ 0.5 s)
        /** Longest a fingerprint unlock keeps the activity while the slab rides out by itself. */
        const val AUTO_EXIT_GRACE_MS = 700L
        /** Start with this extra to open the customisation editor without a keyguard (Settings → 锁屏 → 自定锁屏). */
        const val EXTRA_EDIT = "com.jake.duolauncher.LOCK_SCREEN_EDIT"
    }
}

/** Flashlight via [CameraManager.setTorchMode] (no permission needed); state follows the system's torch. */
internal class LockScreenTorch(context: Context, private val on: androidx.compose.runtime.MutableState<Boolean>,
    available: androidx.compose.runtime.MutableState<Boolean>) {
    private val cameras = context.getSystemService(CameraManager::class.java)
    private val id: String? = runCatching {
        cameras.cameraIdList.firstOrNull {
            val c = cameras.getCameraCharacteristics(it)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameras.cameraIdList.firstOrNull {
            cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()
    private val callback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { if (cameraId == id) on.value = enabled }
        override fun onTorchModeUnavailable(cameraId: String) { if (cameraId == id) on.value = false }
    }
    init {
        available.value = id != null
        if (id != null) cameras.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
    }
    fun toggle() {
        val camera = id ?: return
        runCatching { cameras.setTorchMode(camera, !on.value) }
            .onFailure { Log.w("18DuoLockScreen", "Torch unavailable", it) }
    }
    fun release() { runCatching { cameras.unregisterTorchCallback(callback) } }
}
