package com.jake.duolauncher

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import kotlin.math.exp

/**
 * Inter 4.1 (SIL OFL 1.1, rsms/inter, bundled under res/font and assets/licenses/Inter-OFL.txt)
 * is the closest open design to SF Pro for Latin letters and digits. Han, kana and other scripts
 * are absent from Inter, so Android's font fallback renders them with the system CJK face
 * (MiSans on HyperOS, Noto CJK elsewhere) exactly as before.
 */
internal object DuoFonts {
    /** SF Pro Text analogue: everything below 20 sp (labels, status time, menus, body). */
    val text: FontFamily = FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal),
        Font(R.font.inter_medium, FontWeight.Medium),
        Font(R.font.inter_semibold, FontWeight.SemiBold),
        Font(R.font.inter_bold, FontWeight.Bold),
    )

    /** SF Pro Display analogue: tighter optical size for 20 sp and up (titles, StandBy clock). */
    val display: FontFamily = FontFamily(
        Font(R.font.inter_display_medium, FontWeight.Medium),
        Font(R.font.inter_display_semibold, FontWeight.SemiBold),
        Font(R.font.inter_display_bold, FontWeight.Bold),
    )

    /** Apple switches from Text to Display optical size at 20 pt. */
    fun forSize(sizeSp: Float): FontFamily = if (sizeSp >= DISPLAY_SWITCH_SP) display else text
}

internal const val DISPLAY_SWITCH_SP = 20f

/**
 * Size-dependent tracking in em, Inter's published "dynamic metrics" curve
 * (−0.0223 + 0.185·e^(−0.1745·size)): slightly open at caption sizes, tighter for body and
 * titles — the same shape as Apple's SF Pro tracking table, compensating Inter's wider fit.
 */
internal fun duoTrackingEm(sizeSp: Float): Float {
    if (!sizeSp.isFinite() || sizeSp <= 0f) return 0f
    return (-0.0223 + 0.185 * exp(-0.1745 * sizeSp)).toFloat()
}

internal fun duoTracking(sizeSp: Float): TextUnit = duoTrackingEm(sizeSp).em

/** Gives every Material slot the Inter family that matches its size, plus matching tracking. */
internal fun Typography.withDuoFonts(): Typography {
    fun TextStyle.duo(): TextStyle {
        val size = fontSize.takeIf { it.isSp }?.value ?: 16f
        return copy(fontFamily = DuoFonts.forSize(size), letterSpacing = duoTracking(size))
    }
    return Typography(
        displayLarge = displayLarge.duo(), displayMedium = displayMedium.duo(), displaySmall = displaySmall.duo(),
        headlineLarge = headlineLarge.duo(), headlineMedium = headlineMedium.duo(), headlineSmall = headlineSmall.duo(),
        titleLarge = titleLarge.duo(), titleMedium = titleMedium.duo(), titleSmall = titleSmall.duo(),
        bodyLarge = bodyLarge.duo(), bodyMedium = bodyMedium.duo(), bodySmall = bodySmall.duo(),
        labelLarge = labelLarge.duo(), labelMedium = labelMedium.duo(), labelSmall = labelSmall.duo(),
    )
}
