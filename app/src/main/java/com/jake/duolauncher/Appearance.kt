package com.jake.duolauncher

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.*

/**
 * Light or Dark follows the system setting (Configuration.uiMode), as on iOS: settings sheets,
 * alerts, menus, folders, the App Library, Spotlight and widgets all switch with it. Core.16/17
 * derived "dark" from the wallpaper's luminance instead, which kept every sheet and widget dark on
 * the desert wallpaper even in Light mode. The wallpaper still contributes its mean colour. The old
 * "appearance" preference file stays
 * ignored (silent migration).
 */
data class AppearanceState(val dark: Boolean = true)

/** iOS userInterfaceStyle == .dark. */
internal fun systemNightMode(uiMode: Int): Boolean =
    (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

/** Mean relative luminance of sampled ARGB pixels, 0 (black) to 1 (white). */
internal fun wallpaperLuminance(pixels: IntArray): Float {
    if (pixels.isEmpty()) return 0f
    var total = 0.0
    for (pixel in pixels) {
        val r = (pixel shr 16 and 0xFF) / 255.0
        val g = (pixel shr 8 and 0xFF) / 255.0
        val b = (pixel and 0xFF) / 255.0
        total += .2126 * r + .7152 * g + .0722 * b
    }
    return (total / pixels.size).toFloat()
}

/** Luminance split between a "bright" and a "dark" photo (kept for contrast checks and tests). */
internal const val LIGHT_WALLPAPER_LUMINANCE = .62f

internal fun wallpaperIsDark(pixels: IntArray): Boolean =
    pixels.isEmpty() || wallpaperLuminance(pixels) < LIGHT_WALLPAPER_LUMINANCE

/** Mean sRGB colour of sampled ARGB pixels (opaque); mid-grey for an empty sample. */
internal fun wallpaperMeanColor(pixels: IntArray): Int {
    if (pixels.isEmpty()) return 0xFF808080.toInt()
    var r = 0L; var g = 0L; var b = 0L
    for (pixel in pixels) { r += pixel shr 16 and 0xFF; g += pixel shr 8 and 0xFF; b += pixel and 0xFF }
    val n = pixels.size
    return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
}

internal fun wallpaperMeanColor(bitmap: Bitmap?): Int {
    if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return wallpaperMeanColor(IntArray(0))
    return runCatching {
        val sample = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        val pixels = IntArray(sample.width * sample.height)
        sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
        if (sample !== bitmap) sample.recycle()
        wallpaperMeanColor(pixels)
    }.getOrDefault(wallpaperMeanColor(IntArray(0)))
}

class AppearanceStore(private val context: Context) {
    var state by mutableStateOf(AppearanceState(dark = true)); private set
    private var sampledRevision = Int.MIN_VALUE
    init { refresh() }
    /** Re-read the system appearance; re-sample the wallpaper tone only when the background changed. */
    fun refresh() {
        state = AppearanceState(dark = systemNightMode(context.resources.configuration.uiMode))
        DuoAppearanceRuntime.dark = state.dark
        val revision = LauncherBackgroundCache.revision.intValue
        if (revision != sampledRevision) {
            sampledRevision = revision
            DuoAppearanceRuntime.wallpaperMean = wallpaperMeanColor(cachedLauncherBackground(context) ?: bundledDefaultWallpaper(context))
        }
    }
}

object DuoAppearanceRuntime {
    @Volatile var dark: Boolean = true
    /** Snapshot state so the App Library recolours when the wallpaper changes. */
    var wallpaperMean by mutableIntStateOf(0xFF8C8478.toInt())
}

data class DuoPalette(val ink: androidx.compose.ui.graphics.Color, val glass: androidx.compose.ui.graphics.Color,
    val backgroundTop: androidx.compose.ui.graphics.Color, val backgroundBottom: androidx.compose.ui.graphics.Color, val dark: Boolean)
val LightDuoPalette = DuoPalette(androidx.compose.ui.graphics.Color(0xFF202124), androidx.compose.ui.graphics.Color(0xFFF5F5F7),
    androidx.compose.ui.graphics.Color(0xFF41687E), androidx.compose.ui.graphics.Color(0xFFD8CEB6), false)
val DarkDuoPalette = DuoPalette(androidx.compose.ui.graphics.Color(0xFFF5F5F7), androidx.compose.ui.graphics.Color(0xFF242426),
    androidx.compose.ui.graphics.Color(0xFF132832), androidx.compose.ui.graphics.Color(0xFF463F35), true)
val LocalDuoPalette = staticCompositionLocalOf { LightDuoPalette }
