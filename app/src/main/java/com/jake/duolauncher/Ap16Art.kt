package com.jake.duolauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.LruCache

/**
 * Artwork from the user-supplied "AP16超级米果" theme (assets/ap16, see SOURCE.txt): Control Center
 * glyphs and the status-bar signal, Wi-Fi and battery images. The theme's own translucent plates
 * are not used; tiles are drawn as [GlassPlate] over the window blur.
 */
internal class Ap16Art(private val context: Context) {
    private val cache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val strips = HashMap<String, BitmapRegionDecoder?>()

    /** A glyph such as `cc/wifi_1` or `status/signal_4_dark`; null if the asset is missing. */
    fun image(name: String): Bitmap? = cache.get(name) ?: runCatching {
        context.assets.open("ap16/$name.png").use { BitmapFactory.decodeStream(it) }
    }.getOrNull()?.also { cache.put(name, it) }

    /** One frame of a 100-frame battery strip (98×60 px each); frame = level − 1. */
    fun batteryFrame(strip: String, level: Int): Bitmap? {
        val frame = level.coerceIn(1, 100) - 1
        val key = "$strip#$frame"
        cache.get(key)?.let { return it }
        val decoder = synchronized(strips) {
            strips.getOrPut(strip) {
                runCatching { context.assets.open("ap16/status/$strip.png").use { BitmapRegionDecoder.newInstance(it) } }.getOrNull()
            }
        } ?: return null
        val frameHeight = decoder.height / 100
        return runCatching {
            decoder.decodeRegion(Rect(0, frame * frameHeight, decoder.width, (frame + 1) * frameHeight), null)
        }.getOrNull()?.also { cache.put(key, it) }
    }
}

/** Status-bar glyph names for the current state; `_dark` picks the theme's dark glyphs for light backgrounds. */
internal object Ap16Status {
    fun signal(level: Int?, airplane: Boolean, dark: Boolean): String {
        val base = when {
            airplane -> "signal_flightmode"
            level == null -> "signal_null"
            else -> "signal_${level.coerceIn(0, 4)}"
        }
        return "status/$base${if (dark) "_dark" else ""}"
    }

    fun wifi(level: Int?, dark: Boolean): String =
        "status/${if (level == null) "wifi_null" else "wifi_${level.coerceIn(0, 4)}"}${if (dark) "_dark" else ""}"

    fun battery(charging: Boolean, powerSave: Boolean, dark: Boolean): String {
        val base = when {
            charging -> "battery_charge_digit"
            powerSave -> "battery_power_save_digit"
            else -> "battery_digital"
        }
        return base + if (dark) "_dark" else ""
    }
}

/**
 * iOS 26 glass tile drawn on top of the panel's window blur: a faint fill, a specular rim that is
 * brightest along the top-left, and an optional solid "on" fill (white or a tint) like the system.
 */
internal class GlassPlate(private val radiusPx: Float, private val density: Float) : Drawable() {
    var active: Int? = null
        set(value) { field = value; invalidateSelf() }
    var fillAlpha = 46
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val box = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val r = radiusPx.coerceAtMost(minOf(b.width(), b.height()) / 2f)
        box.set(b)
        val on = active
        fill.color = on ?: android.graphics.Color.argb(fillAlpha, 255, 255, 255)
        canvas.drawRoundRect(box, r, r, fill)
        val stroke = density * 1.1f
        rim.strokeWidth = stroke
        rim.shader = LinearGradient(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            intArrayOf(android.graphics.Color.argb(if (on != null) 70 else 120, 255, 255, 255),
                android.graphics.Color.argb(18, 255, 255, 255),
                android.graphics.Color.argb(if (on != null) 40 else 64, 255, 255, 255)),
            floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        box.inset(stroke / 2f, stroke / 2f)
        canvas.drawRoundRect(box, r - stroke / 2f, r - stroke / 2f, rim)
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha; rim.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { fill.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
