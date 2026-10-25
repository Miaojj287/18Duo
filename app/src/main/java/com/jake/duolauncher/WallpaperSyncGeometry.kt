package com.jake.duolauncher

/** A panel's size in physical pixels, in its natural (Home) orientation. */
internal data class PanelPixels(val width: Int, val height: Int) {
    val aspect: Float get() = width.toFloat() / height.coerceAtLeast(1)
    fun rotated() = PanelPixels(height, width)
}

/** Xiaomi 18 Fold (lhasa) panels, verified on the phone: cover portrait, inner unfolded landscape. */
internal val XIAOMI_COVER_PANEL = PanelPixels(1168, 1712)
internal val XIAOMI_INNER_PANEL = PanelPixels(2364, 1672)

/**
 * The panels the system wallpaper must fit. On lhasa — or any window that is exactly one of its
 * panels (the QA emulator with `wm size`) — both the cover and the inner panel; elsewhere just the
 * current window, from which Android derives the rest itself.
 */
internal fun duoPanelSizes(current: PanelPixels, lhasa: Boolean): List<PanelPixels> {
    val known = listOf(XIAOMI_COVER_PANEL, XIAOMI_INNER_PANEL)
    if (lhasa || known.any { it == current || it == current.rotated() }) return known
    return listOf(current)
}

/**
 * How the system Home + Lock wallpaper is written so it shows exactly what 18Duo draws.
 * [crops]: per panel, the same source rectangle `DuneWallpaper` crops for that panel (bundled dunes
 * right-anchored, photos centred) — used with Android 15+'s per-display-size crops.
 * [legacyHint]: the single crop for `setBitmap(…, visibleCropHint, …)` where per-size crops are not
 * available: the widest panel's crop. A system that fills and centres that region on every panel
 * then shows exactly 18Duo's crop on the inner panel, and on the cover too for any image at least
 * as wide as the inner panel with a centred crop (photos); see [legacyPanelView].
 */
internal data class SystemWallpaperPlan(
    val crops: Map<PanelPixels, WallpaperSourceCrop>,
    val legacyHint: WallpaperSourceCrop,
)

internal fun systemWallpaperPlan(imageWidth: Int, imageHeight: Int, bundled: Boolean, panels: List<PanelPixels>): SystemWallpaperPlan {
    require(panels.isNotEmpty())
    val crops = panels.associateWith { wallpaperSourceCrop(imageWidth, imageHeight, it.width, it.height, rightAnchored = bundled) }
    val widest = panels.maxBy { it.aspect }
    return SystemWallpaperPlan(crops, crops.getValue(widest))
}

/** What a legacy wallpaper engine shows on [panel] from [hint]: the hint filled and centred. */
internal fun legacyPanelView(hint: WallpaperSourceCrop, panel: PanelPixels): WallpaperSourceCrop {
    val hintAspect = hint.width.toFloat() / hint.height
    return if (hintAspect > panel.aspect) {
        val w = (hint.height * panel.aspect).toInt().coerceIn(1, hint.width)
        WallpaperSourceCrop(hint.x + (hint.width - w) / 2, hint.y, w, hint.height)
    } else {
        val h = (hint.width / panel.aspect).toInt().coerceIn(1, hint.height)
        WallpaperSourceCrop(hint.x, hint.y + (hint.height - h) / 2, hint.width, h)
    }
}

/** Identity of what was last written to the system, so an unchanged wallpaper is never rewritten. */
internal fun wallpaperSyncSignature(identity: String?, imageWidth: Int, imageHeight: Int, panels: List<PanelPixels>): String =
    buildString {
        append(identity ?: "bundled-dunes"); append('@'); append(imageWidth); append('x'); append(imageHeight)
        panels.forEach { append('|'); append(it.width); append('x'); append(it.height) }
    }
