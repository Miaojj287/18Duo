package com.jake.duolauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import androidx.core.graphics.drawable.toBitmap

/** Side of every cached launcher icon bitmap (≈ the 54 dp Home icon at 2.75 px/dp). */
internal const val ICON_BITMAP_PX = 144

/** Legacy artwork that is not a full tile sits on a white iOS tile at this fraction of the side. */
internal const val LEGACY_GLYPH_FRACTION = .72f

/** How a non-adaptive icon's alpha footprint should be fitted onto an iOS tile. */
internal enum class IconArtworkKind {
    /** Opaque to the corners: already a full square, only needs the squircle mask. */
    FullBleed,
    /** A pre-masked tile (rounded square, squircle, OEM-themed icon): crop to it and fill the tile. */
    Tile,
    /** A free-standing glyph or circle: centre it on a white tile. */
    Glyph,
}

internal data class IconArtwork(
    val kind: IconArtworkKind,
    /** Opaque bounds in source pixels: left, top, right (exclusive), bottom (exclusive). */
    val left: Int, val top: Int, val right: Int, val bottom: Int,
    /** Average opaque colour on the artwork's rim; used under [IconArtworkKind.Tile] crops. */
    val rimColor: Int,
)

/**
 * Classifies ARGB [pixels] of a square legacy icon. Pure so the thresholds are unit-tested:
 * a circle covers π/4 ≈ 79 % of its bounds, a 25 %-radius rounded square ≈ 95 %, OEM squircles
 * ≈ 93 % — 86 % separates "already a tile" from "a shape that needs a tile".
 */
internal fun classifyIconArtwork(pixels: IntArray, width: Int, height: Int): IconArtwork {
    require(width > 0 && height > 0 && pixels.size >= width * height)
    fun alpha(x: Int, y: Int) = pixels[y * width + x] ushr 24
    var left = width; var top = height; var right = -1; var bottom = -1
    for (y in 0 until height) for (x in 0 until width) if (alpha(x, y) > 24) {
        if (x < left) left = x; if (x > right) right = x
        if (y < top) top = y; if (y > bottom) bottom = y
    }
    if (right < 0) return IconArtwork(IconArtworkKind.Glyph, 0, 0, width, height, 0)
    right += 1; bottom += 1
    val inset = maxOf(1, (minOf(width, height) * .03f).toInt())
    val corners = listOf(inset to inset, width - 1 - inset to inset, inset to height - 1 - inset,
        width - 1 - inset to height - 1 - inset)
    val rim = rimColor(pixels, width, left, top, right, bottom)
    if (corners.all { (x, y) -> alpha(x, y) > 200 }) return IconArtwork(IconArtworkKind.FullBleed, 0, 0, width, height, rim)
    val boxW = right - left; val boxH = bottom - top
    var solid = 0
    for (y in top until bottom) for (x in left until right) if (alpha(x, y) > 160) solid++
    val fill = solid / (boxW * boxH).toFloat()
    val squareish = minOf(boxW, boxH) / maxOf(boxW, boxH).toFloat() >= .9f
    val large = maxOf(boxW, boxH) >= minOf(width, height) * .6f
    val kind = if (fill >= .86f && squareish && large) IconArtworkKind.Tile else IconArtworkKind.Glyph
    return IconArtwork(kind, left, top, right, bottom, rim)
}

/** Mean colour of opaque pixels in a thin ring just inside the artwork bounds. */
private fun rimColor(pixels: IntArray, width: Int, left: Int, top: Int, right: Int, bottom: Int): Int {
    val ring = maxOf(1, (minOf(right - left, bottom - top) * .08f).toInt())
    var r = 0L; var g = 0L; var b = 0L; var n = 0L
    for (y in top until bottom) for (x in left until right) {
        val edge = x - left < ring || right - 1 - x < ring || y - top < ring || bottom - 1 - y < ring
        if (!edge) continue
        val c = pixels[y * width + x]
        if (c ushr 24 < 200) continue
        r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
    }
    if (n == 0L) return 0xFFFFFFFF.toInt()
    return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
}

/**
 * Draws [drawable] as an iOS app icon: full-bleed artwork in the 22.4 % continuous-corner tile,
 * anti-aliased mask, then a faint glassy rim like iOS 26 icons. Adaptive icons keep their own
 * background layer; legacy icons are normalised by [classifyIconArtwork].
 */
internal fun iosIconBitmap(drawable: Drawable, size: Int = ICON_BITMAP_PX): Bitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val adaptive = drawable as? AdaptiveIconDrawable
        ?: (drawable as? LayerDrawable)?.takeIf { it.numberOfLayers > 0 }?.getDrawable(0) as? AdaptiveIconDrawable
    when {
        adaptive != null -> {
            adaptive.setBounds(0, 0, size, size)
            adaptive.background?.draw(canvas)
            adaptive.foreground?.draw(canvas)
        }
        else -> drawLegacyArtwork(drawable, canvas, size)
    }
    // Rim first, mask second: the mask also trims any anti-aliased rim spill at the corners.
    drawIconRim(canvas, size)
    maskToContinuousTile(canvas, size)
    // Work-profile and other badges sit on top of the finished tile, unmasked, as on iOS.
    if (adaptive != null && drawable is LayerDrawable && drawable.numberOfLayers > 1) {
        drawable.setBounds(0, 0, size, size)
        for (index in 1 until drawable.numberOfLayers) drawable.getDrawable(index)?.draw(canvas)
    }
    return bitmap
}

private fun drawLegacyArtwork(drawable: Drawable, canvas: Canvas, size: Int) {
    val source = runCatching { drawable.toBitmap(size, size, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return
    val pixels = IntArray(size * size)
    source.getPixels(pixels, 0, size, 0, 0, size, size)
    val art = classifyIconArtwork(pixels, size, size)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val sourceRect = Rect(art.left, art.top, art.right, art.bottom)
    when (art.kind) {
        IconArtworkKind.FullBleed -> canvas.drawBitmap(source, 0f, 0f, paint)
        IconArtworkKind.Tile -> {
            // The source's own (rounder) corners may not reach ours; its rim colour fills them.
            canvas.drawColor(art.rimColor)
            val over = size * .02f
            canvas.drawBitmap(source, sourceRect, RectF(-over, -over, size + over, size + over), paint)
        }
        IconArtworkKind.Glyph -> {
            canvas.drawColor(android.graphics.Color.WHITE)
            val boxW = (art.right - art.left).toFloat(); val boxH = (art.bottom - art.top).toFloat()
            val scale = size * LEGACY_GLYPH_FRACTION / maxOf(boxW, boxH)
            val w = boxW * scale; val h = boxH * scale
            canvas.drawBitmap(source, sourceRect, RectF((size - w) / 2f, (size - h) / 2f, (size + w) / 2f, (size + h) / 2f), paint)
        }
    }
    // No recycle(): toBitmap() may hand back the drawable's own (resource-cached) bitmap.
}

/** Clears everything outside the squircle with an anti-aliased edge (Canvas.clipPath is aliased). */
private fun maskToContinuousTile(canvas: Canvas, size: Int) {
    val outside = continuousRoundedPath(0f, 0f, size.toFloat(), size.toFloat(), size * IOS_ICON_CORNER_FRACTION)
        .apply { fillType = Path.FillType.INVERSE_WINDING }
    canvas.drawPath(outside, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    })
}

/**
 * iOS 26 icons catch light on their rim: brightest along the top-left, a softer echo bottom-right.
 * Baked once per icon, so it costs nothing per frame.
 */
private fun drawIconRim(canvas: Canvas, size: Int) {
    val stroke = size * .016f
    val inset = stroke / 2f
    val rim = continuousRoundedPath(inset, inset, size - stroke, size - stroke, size * IOS_ICON_CORNER_FRACTION - inset)
    val white = android.graphics.Color.WHITE
    fun white(alpha: Float) = android.graphics.Color.argb((alpha * 255).toInt(), 255, 255, 255)
    canvas.drawPath(rim, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(),
            intArrayOf(white(.42f), white(.10f), white(0f), white(.05f), white(.20f)),
            floatArrayOf(0f, .28f, .5f, .72f, 1f), Shader.TileMode.CLAMP)
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        color = white
    })
}

/** Calendar apps whose Apple icon shows today's date (iOS draws it live; the MTZ's art is a fixed "周六 28"). */
internal val LIVE_CALENDAR_PACKAGES = setOf("com.android.calendar", "com.google.android.calendar",
    "com.samsung.android.calendar", "com.miui.calendar")

/** iOS Calendar icon's weekday line: "周日" in Chinese, "Sunday" otherwise. [dayOfWeek] is ISO (1 = Monday). */
internal fun calendarIconWeekday(dayOfWeek: Int, chinese: Boolean): String =
    if (chinese) "周" + "一二三四五六日"[(dayOfWeek - 1).coerceIn(0, 6)]
    else java.time.DayOfWeek.of(dayOfWeek.coerceIn(1, 7)).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)

/**
 * iOS 26 Calendar icon for [date], measured on the owner's iPhone Duo (r21, 4.webp, 125 px tile): white tile,
 * the weekday in system red with its glyphs 14.4–30.4 % down, the day number in black 39.2–83.2 % down
 * (0.44 of the side tall), both centred; the same rim and squircle as every other icon.
 */
internal fun calendarIconBitmap(date: java.time.LocalDate, chinese: Boolean, size: Int = ICON_BITMAP_PX): Bitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)
    fun fitted(text: String, paint: Paint, top: Float, bottom: Float) {
        // Scale the face so its ink (not its line box) spans [top, bottom] of the side, then centre it.
        val probe = Rect()
        paint.textSize = 100f
        paint.getTextBounds(text, 0, text.length, probe)
        if (probe.height() <= 0) return
        paint.textSize = 100f * (bottom - top) * size / probe.height()
        paint.getTextBounds(text, 0, text.length, probe)
        val x = (size - probe.width()) / 2f - probe.left
        canvas.drawText(text, x, top * size - probe.top, paint)
    }
    val weekday = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = 0xFFFF3B30.toInt()
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    fitted(calendarIconWeekday(date.dayOfWeek.value, chinese), weekday, if (chinese) .144f else .17f, if (chinese) .304f else .29f)
    val day = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = 0xFF111111.toInt()
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }
    fitted(date.dayOfMonth.toString(), day, .392f, .832f)
    drawIconRim(canvas, size)
    maskToContinuousTile(canvas, size)
    return bitmap
}
