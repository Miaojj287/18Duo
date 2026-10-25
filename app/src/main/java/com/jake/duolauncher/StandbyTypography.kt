package com.jake.duolauncher

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface

/**
 * The single place StandBy resolves typefaces. Apple draws StandBy in SF Pro (Display, Condensed for the
 * Digital face, Rounded nowhere) and New York for the Calendar face's giant date. Latin letters and
 * digits use the bundled Inter Display (the same SF Pro stand-in as the rest of 18Duo, see
 * [DuoFonts]); Han characters fall back to the system CJK face. Inter has no condensed cut, so the
 * Digital face keeps the system condensed sans, and the calendar numeral keeps the system serif.
 */
internal object StandbyFonts {
    private val cache = HashMap<String, Typeface>()

    /** SF Pro Display stand-in: Inter Display Medium/SemiBold/Bold (system sans if resources are unavailable). */
    fun sans(weight: Int): Typeface = cached("sans", weight) { w ->
        val resources = DuoApplication.uiResources
        val id = when {
            w >= 700 -> R.font.inter_display_bold
            w >= 600 -> R.font.inter_display_semibold
            else -> R.font.inter_display_medium
        }
        resources?.let { runCatching { it.getFont(id) }.getOrNull() } ?: Typeface.create(Typeface.DEFAULT, w, false)
    }

    /** SF Pro Condensed stand-in for the full-screen Digital clock. */
    fun condensed(weight: Int): Typeface = cached("condensed", weight) {
        Typeface.create(Typeface.create("sans-serif-condensed", Typeface.NORMAL), it, false)
    }

    /** New York stand-in for the Calendar face's date numeral. */
    fun serif(weight: Int): Typeface = cached("serif", weight) { Typeface.create(Typeface.SERIF, it, false) }

    private fun cached(family: String, weight: Int, create: (Int) -> Typeface): Typeface = synchronized(cache) {
        val clamped = weight.coerceIn(100, 900)
        cache.getOrPut("$family-$clamped") { create(clamped) }
    }
}

/** Apple specs type by glyph height; Android sizes by em. Ratios are cached per typeface and sample. */
internal object StandbyTextMetrics {
    private val ratios = HashMap<Pair<Typeface, String>, Float>()
    private val probe = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = Rect()

    /** Ink height of [sample] at textSize 1. Digits and capitals share the cap height in SF and Roboto. */
    fun inkRatio(typeface: Typeface, sample: String = "0"): Float = synchronized(ratios) {
        ratios.getOrPut(typeface to sample) {
            probe.typeface = typeface
            probe.textSize = 200f
            probe.textScaleX = 1f
            probe.getTextBounds(sample, 0, sample.length, bounds)
            (bounds.height() / 200f).takeIf { it > 0f } ?: .7f
        }
    }

    /** Sets [paint]'s size so that [sample]'s ink is [capPx] tall. */
    fun sizeForCap(paint: Paint, capPx: Float, sample: String = "0") {
        paint.textSize = (capPx / inkRatio(paint.typeface, sample)).coerceAtLeast(1f)
    }
}
