package com.jake.duolauncher

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class WallpaperSyncStatus { IDLE, WORKING, DONE, UNSUPPORTED, FAILED }

/**
 * Keeps the system Home + Lock wallpaper equal to 18Duo's own wallpaper (the bundled iPhone Duo
 * dunes or the user's photo), cropped for each panel exactly as 18Duo crops it, so the lock screen
 * hands over to Home's first arrival frame with the same image and crop.
 *
 * Writes happen only on explicit actions — the setup wizard's wallpaper step, a wallpaper change in
 * Settings while "sync" is on (default on), turning sync on — never at start-up or after an update,
 * so a system wallpaper the user picked elsewhere is never replaced behind their back.
 */
internal object WallpaperSync {
    private const val TAG = "DuoWallpaperSync"
    private const val PREFS = "wallpaper_sync"
    private const val ENABLED = "systemSync"
    private const val LAST = "lastSignature"
    private const val LAST_MODE = "lastMode"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = Mutex()

    val status = mutableStateOf(WallpaperSyncStatus.IDLE)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(ENABLED, true)

    /** Turning sync on writes the current wallpaper at once (Settings); the wizard writes on "Use". */
    fun setEnabled(context: Context, enabled: Boolean, syncNow: Boolean = true) {
        prefs(context).edit().putBoolean(ENABLED, enabled).apply()
        if (enabled && syncNow) syncNow(context, force = true)
    }

    /** The in-app wallpaper was committed or reset (LauncherBackgroundController). */
    fun launcherWallpaperChanged(context: Context) { if (enabled(context)) syncNow(context, force = false) }

    /** Writes the current 18Duo wallpaper to the system now (async); [onDone] runs on the main thread. */
    fun syncNow(context: Context, force: Boolean, onDone: ((WallpaperSyncStatus) -> Unit)? = null) {
        val app = context.applicationContext
        status.value = WallpaperSyncStatus.WORKING
        scope.launch {
            val result = writes.withLock { runCatching { write(app, force) }.getOrElse {
                Log.w(TAG, "system wallpaper write failed", it); WallpaperSyncStatus.FAILED } }
            withContext(Dispatchers.Main) { status.value = result; onDone?.invoke(result) }
        }
    }

    /** "multi-crop" (per-panel crops), "legacy" (one crop hint) or null; for diagnostics and the doc. */
    fun lastMode(context: Context): String? = prefs(context).getString(LAST_MODE, null)

    private fun write(context: Context, force: Boolean): WallpaperSyncStatus {
        val manager = WallpaperManager.getInstance(context)
        if (!manager.isWallpaperSupported || !manager.isSetWallpaperAllowed) return WallpaperSyncStatus.UNSUPPORTED
        // A private decode: the cached Home bitmap stays untouched and can be recycled independently.
        val photo = if (launcherBackgroundEnabled(context))
            BitmapFactory.decodeFile(launcherBackgroundFile(context).absolutePath) else null
        val image = photo ?: bundledDefaultWallpaper(context) ?: return WallpaperSyncStatus.FAILED
        try {
            val panels = duoPanelSizes(currentPanel(context), Build.DEVICE == "lhasa")
            val signature = wallpaperSyncSignature(launcherBackgroundIdentity(context), image.width, image.height, panels)
            if (!force && prefs(context).getString(LAST, null) == signature) return WallpaperSyncStatus.DONE
            val plan = systemWallpaperPlan(image.width, image.height, bundled = photo == null, panels)
            val which = WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            val mode = if (setWithPanelCrops(manager, image, plan, which)) "multi-crop" else {
                manager.setBitmap(image, plan.legacyHint.toRect(), true, which)
                "legacy"
            }
            prefs(context).edit().putString(LAST, signature).putString(LAST_MODE, mode).apply()
            Log.i(TAG, "system home+lock wallpaper written ($mode) for $panels")
            return WallpaperSyncStatus.DONE
        } finally {
            photo?.recycle()
        }
    }

    /**
     * Android 15+ stores one crop per display size (`setBitmapWithCrops`, multi-crop). It is not in the
     * public SDK, so it is reached reflectively and any refusal (hidden-API policy, OEM change) falls
     * back to the single-hint [WallpaperManager.setBitmap].
     */
    private fun setWithPanelCrops(manager: WallpaperManager, image: Bitmap, plan: SystemWallpaperPlan, which: Int): Boolean {
        if (Build.VERSION.SDK_INT < 35) return false
        return runCatching {
            val method = WallpaperManager::class.java.getMethod("setBitmapWithCrops", Bitmap::class.java,
                Map::class.java, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val crops = plan.crops.entries.associate { (panel, crop) -> Point(panel.width, panel.height) to crop.toRect() }
            method.invoke(manager, image, crops, true, which)
            true
        }.getOrElse {
            Log.i(TAG, "per-panel crops unavailable (${it.javaClass.simpleName}); using one crop hint")
            false
        }
    }

    private fun currentPanel(context: Context): PanelPixels {
        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        return PanelPixels(bounds.width(), bounds.height())
    }
}

internal fun WallpaperSourceCrop.toRect() = Rect(x, y, x + width, y + height)
