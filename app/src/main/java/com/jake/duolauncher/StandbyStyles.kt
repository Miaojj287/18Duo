package com.jake.duolauncher

import androidx.compose.ui.graphics.Color

/**
 * Colour options per face. iOS: "Swipe up or down to scroll between options for each of these views", and
 * Night Mode "displays items with a red tint". The first option of each face is the one Apple shows:
 * black Analog = owner photo 4, green Digital / yellow Analog / white Calendar = standby.mp4.
 */
internal data class StandbyDigitalStyle(val background: Color, val digits: Color, val date: Color)

internal data class StandbyAnalogStyle(
    val background: Color,
    val primary: Color,
    /** Second hand, hub ring, weekday, month label and today's disc. */
    val accent: Color,
    /** Digit drawn on today's disc. */
    val onAccent: Color,
    val minorTick: Color = primary.copy(alpha = .42f),
    val secondaryText: Color = primary.copy(alpha = .55f),
)

internal data class StandbyCalendarStyle(
    val background: Color,
    val title: Color,
    val weekday: Color,
    val day: Color,
    val outside: Color,
    val numeral: Color,
    val dayOnNumeral: Color,
    val today: Color,
    val onToday: Color,
    /** Lunar date after the title (Chinese locale). */
    val lunar: Color = weekday,
    /**
     * iPhone Duo's cover (round 24, owner photo 2): a translucent rounded-sans date spanning most of the grid, centred
     * on it, with the digits drawn on top unchanged. False: the iPhone StandBy video's New York date behind today's column.
     */
    val wideNumeral: Boolean = false,
)

internal object StandbyStyles {
    val digital = listOf(
        StandbyDigitalStyle(Color(0xFF0A9A55), Color(0xFF050505), Color(0xFF050505)),
        StandbyDigitalStyle(Color.Black, Color(0xFF30D158), Color(0xFF30D158)),
        StandbyDigitalStyle(Color(0xFFFF9F0A), Color(0xFF0A0A0A), Color(0xFF0A0A0A)),
        StandbyDigitalStyle(Color.Black, Color.White, Color.White.copy(alpha = .85f)),
    )
    val analog = listOf(
        StandbyAnalogStyle(Color.Black, Color.White, Color(0xFFFFCC33), Color.Black),
        StandbyAnalogStyle(Color(0xFFF2AE3B), Color(0xFF16130C), Color.White, Color(0xFFC88A22)),
        StandbyAnalogStyle(Color(0xFFF2F2F7), Color(0xFF111114), Color(0xFFFF9500), Color.White),
    )
    val calendar = listOf(
        // Round 24 (owner photo 2, iPhone Duo cover): navy sheet, blue grid, red title and time, pink "26".
        StandbyCalendarStyle(background = Color(0xFF03107A), title = Color(0xFFFF5A72), weekday = Color(0xFF2E6BFF),
            day = Color(0xFF2E6BFF), outside = Color(0xFF1C3BB4), numeral = Color(0xFFE0469C).copy(alpha = .62f),
            dayOnNumeral = Color(0xFF2E6BFF), today = Color(0xFF1F5CFF), onToday = Color(0xFF07154F), wideNumeral = true),
        StandbyCalendarStyle(background = Color(0xFFF2F2F7), title = Color(0xFFFF3B30), weekday = Color(0xFF1A5BC2),
            day = Color(0xFF1A5BC2), outside = Color(0xFFB2BFD3), numeral = Color(0xFFEE3A33).copy(alpha = .55f),
            dayOnNumeral = Color(0xFF1A5BC2), today = Color(0xFF0B2150), onToday = Color.White, wideNumeral = true),
        StandbyCalendarStyle(background = Color.Black, title = Color(0xFFFF453A), weekday = Color(0xFF64A8FF),
            day = Color(0xFF64A8FF), outside = Color(0xFF2C3F5C), numeral = Color(0xFFE5352B).copy(alpha = .6f),
            dayOnNumeral = Color(0xFF64A8FF), today = Color(0xFF0A84FF), onToday = Color.White, wideNumeral = true),
    )

    /** Night Mode: black ground, every mark in one dim red (no green/blue light at all). */
    val nightRed = Color(0xFFE81808)
    val nightDigital = StandbyDigitalStyle(Color.Black, nightRed, nightRed.copy(alpha = .8f))
    val nightAnalog = StandbyAnalogStyle(Color.Black, nightRed, nightRed, Color.Black,
        minorTick = nightRed.copy(alpha = .45f), secondaryText = nightRed.copy(alpha = .6f))
    val nightCalendar = StandbyCalendarStyle(background = Color.Black, title = nightRed, weekday = nightRed.copy(alpha = .75f),
        day = nightRed, outside = nightRed.copy(alpha = .3f), numeral = Color(0xFF4A0B07), dayOnNumeral = nightRed,
        today = nightRed, onToday = Color.Black, wideNumeral = true)

    fun variants(face: StandbyFace, photos: Int): Int = when (face) {
        StandbyFace.DIGITAL -> digital.size
        StandbyFace.ANALOG -> analog.size
        StandbyFace.CALENDAR -> calendar.size
        StandbyFace.PHOTO -> photos.coerceAtLeast(1)
        StandbyFace.WIDGETS, StandbyFace.WEATHER -> 1
    }
    fun digital(variant: Int, night: Boolean) = if (night) nightDigital else digital[Math.floorMod(variant, digital.size)]
    fun analog(variant: Int, night: Boolean) = if (night) nightAnalog else analog[Math.floorMod(variant, analog.size)]
    fun calendar(variant: Int, night: Boolean) = if (night) nightCalendar else calendar[Math.floorMod(variant, calendar.size)]

    /** Background the face paints edge to edge; chrome picks light/dark glass from it. */
    fun background(face: StandbyFace, variant: Int, night: Boolean): Color = when (face) {
        StandbyFace.DIGITAL -> digital(variant, night).background
        StandbyFace.ANALOG -> analog(variant, night).background
        StandbyFace.CALENDAR -> calendar(variant, night).background
        StandbyFace.PHOTO, StandbyFace.WIDGETS -> Color.Black
        // Sky blue under the chrome (its glass picks the dark tint either way).
        StandbyFace.WEATHER -> if (night) Color.Black else Color(0xFF14309E)
    }
}

/** Night red tint for photographs: luminance into the red channel only, dimmed. Row-major 4×5 matrix. */
internal val standbyNightPhotoMatrix = floatArrayOf(
    .21f * .4f, .72f * .4f, .07f * .4f, 0f, 0f,
    0f, 0f, 0f, 0f, 0f,
    0f, 0f, 0f, 0f, 0f,
    0f, 0f, 0f, 1f, 0f,
)
