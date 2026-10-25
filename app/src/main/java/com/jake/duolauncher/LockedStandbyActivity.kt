package com.jake.duolauncher

import android.app.KeyguardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest

/** Contains only the clock. Closing it reveals Android's existing secure keyguard. */
class LockedStandbyActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val guard = object : Runnable {
        override fun run() {
            if (!getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
                !StandbyMonitorService.clockVisible.value) { finish(); return }
            handler.postDelayed(this, 200)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = CorePreferences(this)
        if (!getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            !StandbyMonitorService.launchAllowed || !prefs.standbyEnabled || prefs.standbyScene == StandbyScene.MANUAL_ONLY) { finish(); return }
        StandbyMonitorService.clockVisible.value = true
        setShowWhenLocked(true)
        // Turning on happens only on a charger (the monitor's power gate); staying on is StandbyScreen's
        // power-aware keepScreenOn, so on battery the lock-screen timeout still applies.
        setTurnScreenOn(true)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent { DuoTheme {
            val rotation = StandbyMonitorService.clockRotation.value
            SideEffect {
                requestedOrientation = if (rotation < 0) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                    else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            LaunchedEffect(Unit) { snapshotFlow { StandbyMonitorService.clockVisible.value }
                .collectLatest { if (!it) finish() } }
            RotatedStandbyScreen(rotation = rotation, onExit = ::closeClock)
        } }
        handler.post(guard)
    }
    private fun closeClock() { StandbyMonitorService.dismiss(); finish() }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
