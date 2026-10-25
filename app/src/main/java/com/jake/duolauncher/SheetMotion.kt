package com.jake.duolauncher

/**
 * iOS sheet pull-to-dismiss: the sheet follows the finger 1:1 downward and rubber-bands upward,
 * the backdrop un-dims in proportion to how far it has travelled, and a release is projected
 * (UIScrollView normal deceleration) so a flick dismisses from a short pull while a slow drag
 * must pass about a third of the sheet.
 */
internal object SheetPullMath {
    /** Visible sheet offset for a raw finger offset (px, positive = down). */
    fun visibleOffset(raw: Float, sheetHeight: Float): Float =
        if (raw >= 0f) raw else IosScrollPhysics.rubberBand(raw, sheetHeight.coerceAtLeast(1f))

    /** Distance a slow release must pass to dismiss. */
    fun threshold(sheetHeight: Float, density: Float): Float =
        (sheetHeight * .3f).coerceIn(100f * density, 260f * density)

    fun dismisses(offset: Float, velocity: Float, sheetHeight: Float, density: Float): Boolean {
        if (offset <= 0f && velocity <= 0f) return false
        val projected = offset + IosScrollPhysics.projectedDistance(velocity, IosScrollPhysics.DECELERATION_NORMAL)
        return projected >= threshold(sheetHeight, density)
    }

    /** How much of the backdrop dim remains with the sheet [offset] px down (1 at rest, 0 off screen). */
    fun backdropFraction(arrival: Float, offset: Float, sheetHeight: Float): Float =
        (arrival.coerceIn(0f, 1f) * (1f - offset.coerceAtLeast(0f) / sheetHeight.coerceAtLeast(1f))).coerceIn(0f, 1f)

    /**
     * A centred form sheet (the unfolded panel's settings / widget gallery) presents like an iPad
     * form sheet: it rises from below the bottom edge into the centre, never scaling or fading
     * (round 11, goal row B10). The sheet is 90 % of the window tall and centred, so its top sits
     * 5 % down and the bottom edge is 95 % H = 1.056 × its height away; [FORM_SHEET_RISE] clears it.
     */
    fun formSheetTranslation(arrival: Float, sheetHeight: Float): Float =
        (1f - arrival.coerceIn(0f, 1f)) * sheetHeight.coerceAtLeast(0f) * FORM_SHEET_RISE

    const val FORM_SHEET_RISE = 1.08f
}
