package com.jake.duolauncher

import androidx.compose.ui.graphics.Color

/**
 * iOS 26 Home Screen ▸ Customize: widgets are opaque cards that follow the system appearance
 * (white in Light, graphite in Dark), can be forced Light or Dark, or switched to the Liquid Glass
 * "Clear" look. The persisted key is the lower-case name. With no choice saved the widgets are Dark, as on
 * the owner's real iPhone Duo (r21, evidence/r21/ref: graphite Calendar, Notes and Reminders over light icons);
 * an unknown key falls back to Automatic.
 */
internal enum class WidgetStyle(val key: String) {
    AUTO("auto"), LIGHT("light"), DARK("dark"), CLEAR("clear");

    val label: String get() = when (this) {
        AUTO -> uxText("自动", "Automatic")
        LIGHT -> uxText("浅色", "Light")
        DARK -> uxText("深色", "Dark")
        CLEAR -> uxText("透明", "Clear")
    }

    companion object {
        fun fromKey(key: String?): WidgetStyle = if (key == null) DEFAULT else entries.firstOrNull { it.key == key } ?: AUTO
        val DEFAULT = DARK
    }
}

/** What a widget actually draws once the style is resolved against the system appearance. */
internal enum class WidgetTone { LIGHT, DARK, CLEAR }

internal fun resolveWidgetTone(style: WidgetStyle, systemDark: Boolean): WidgetTone = when (style) {
    WidgetStyle.AUTO -> if (systemDark) WidgetTone.DARK else WidgetTone.LIGHT
    WidgetStyle.LIGHT -> WidgetTone.LIGHT
    WidgetStyle.DARK -> WidgetTone.DARK
    WidgetStyle.CLEAR -> WidgetTone.CLEAR
}

/**
 * The iOS widget palette for one tone. [background] is opaque for Light/Dark (iOS widgets never
 * let the wallpaper through — that is what made the old cards turn brown over the mountain band);
 * for Clear it is the uniform veil laid over the glass so the content stays legible on both the
 * bright sky and the dark band.
 */
internal data class WidgetColors(
    val background: Color,
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val red: Color,
    val orange: Color,
    val dialFace: Color,
    val dialTick: Color,
    val dialRim: Color,
    val hand: Color,
    /** Grouped fill inside a widget (battery device tile, empty slots). */
    val fill: Color,
    val clear: Boolean,
)

internal fun widgetColors(tone: WidgetTone): WidgetColors = when (tone) {
    // systemBackground / label / secondaryLabel (Light).
    WidgetTone.LIGHT -> WidgetColors(
        background = Color.White, primary = Color.Black, secondary = Color(0xFF3C3C43).copy(alpha = .60f),
        tertiary = Color(0xFF3C3C43).copy(alpha = .30f), red = Color(0xFFFF3B30), orange = Color(0xFFFF9500),
        dialFace = Color(0xFFF2F2F7), dialTick = Color.Black, dialRim = Color.Black.copy(alpha = .08f), hand = Color.Black,
        fill = Color(0xFF767680).copy(alpha = .12f), clear = false)
    // Widgets sit on the elevated dark background (#1C1C1E), the dial on true black.
    WidgetTone.DARK -> WidgetColors(
        background = Color(0xFF1C1C1E), primary = Color.White, secondary = Color(0xFFEBEBF5).copy(alpha = .60f),
        tertiary = Color(0xFFEBEBF5).copy(alpha = .30f), red = Color(0xFFFF453A), orange = Color(0xFFFF9F0A),
        dialFace = Color.Black, dialTick = Color.White, dialRim = Color.White.copy(alpha = .10f), hand = Color.White,
        fill = Color.White.copy(alpha = .10f), clear = false)
    // Clear: monochrome white content on a darkened glass veil (iOS 26 "Clear" widgets).
    WidgetTone.CLEAR -> WidgetColors(
        background = Color.Black.copy(alpha = .32f), primary = Color.White, secondary = Color.White.copy(alpha = .72f),
        tertiary = Color.White.copy(alpha = .40f), red = Color.White, orange = Color.White,
        dialFace = Color.White.copy(alpha = .14f), dialTick = Color.White, dialRim = Color.White.copy(alpha = .22f),
        hand = Color.White, fill = Color.White.copy(alpha = .16f), clear = true)
}

/** WCAG relative luminance of an sRGB colour (alpha ignored). */
internal fun relativeLuminance(color: Color): Float {
    fun channel(c: Float) = if (c <= .03928f) c / 12.92f else Math.pow(((c + .055f) / 1.055f).toDouble(), 2.4).toFloat()
    return .2126f * channel(color.red) + .7152f * channel(color.green) + .0722f * channel(color.blue)
}

internal fun contrastRatio(a: Color, b: Color): Float {
    val la = relativeLuminance(a); val lb = relativeLuminance(b)
    return (maxOf(la, lb) + .05f) / (minOf(la, lb) + .05f)
}
