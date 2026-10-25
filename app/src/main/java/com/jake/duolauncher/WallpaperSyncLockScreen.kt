package com.jake.duolauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * The 18Duo lock screen's (r8-lock) view of Home's wallpaper: the very image and crop Home draws on a
 * panel, so the lock screen and the first arrival frame are pixel-identical.
 *
 * - [duoWallpaperFor] — bitmap + source rectangle for a panel of `widthPx × heightPx`; may decode,
 *   call it off the main thread. Draw `source` scaled to the full panel (no further cropping).
 * - [cachedDuoWallpaperFor] — the same without decoding (null when Home has not decoded it yet).
 * - [DuoPanelWallpaper] — a drop-in for the lock screen's `DuneWallpaper(modifier)` call (same first
 *   parameter): exactly Home's wallpaper, with an optional zoom.
 *   While the lock screen leaves, zoom it towards [WALLPAPER_HANDOFF_SCALE] (Home's first arrival
 *   frame is zoomed by that much) and call [UnlockArrival.keyguardDismissed] once the keyguard is
 *   dismissed; Home plays its arrival as soon as it is resumed.
 * Never recycle the bitmap: it may be Home's cached decode or the bundled dunes.
 */
internal data class DuoWallpaperFrame(val bitmap: Bitmap, val source: Rect, val bundledDunes: Boolean)

internal fun duoWallpaperFor(context: Context, widthPx: Int, heightPx: Int): DuoWallpaperFrame? {
    val app = context.applicationContext
    // Home's cached decode when it has one, otherwise a fresh decode of the same file.
    val photo = loadLauncherBackground(app)
    return frameFor(photo ?: bundledDefaultWallpaper(app) ?: return null, photo == null, widthPx, heightPx)
}

internal fun cachedDuoWallpaperFor(context: Context, widthPx: Int, heightPx: Int): DuoWallpaperFrame? {
    val photo = cachedLauncherBackground(context.applicationContext)
    if (photo == null && launcherBackgroundEnabled(context.applicationContext)) return null
    val live = if (photo == null) DuneLive.restBitmap() else null
    if (live != null) return DuoWallpaperFrame(live,
        wallpaperSourceCrop(live.width, live.height, widthPx, heightPx, rightAnchored = false).toRect(), true)
    return frameFor(photo ?: cachedBundledWallpaper() ?: return null, photo == null, widthPx, heightPx)
}

private fun frameFor(bitmap: Bitmap, bundled: Boolean, widthPx: Int, heightPx: Int) = DuoWallpaperFrame(bitmap,
    wallpaperSourceCrop(bitmap.width, bitmap.height, widthPx, heightPx, rightAnchored = bundled).toRect(), bundled)

/** Home's wallpaper (same image, same crop, same palette fallback), zoomed about the centre by [zoom]. */
@Composable
internal fun DuoPanelWallpaper(modifier: Modifier = Modifier.fillMaxSize(), zoom: () -> Float = { 1f }) {
    DuneWallpaper(modifier.graphicsLayer { val z = zoom(); scaleX = z; scaleY = z })
}
