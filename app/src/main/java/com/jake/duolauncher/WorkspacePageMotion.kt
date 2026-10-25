package com.jake.duolauncher

import kotlin.math.abs

/**
 * Maps the native full-width pager position to the scroll distance shown by the
 * expanded workspace. Homes and the trailing All Apps page each advance by one
 * pane, so the last stop pairs the final home with All Apps.
 *
 * Unfolded with the full-screen App Library ([SpreadEnds.fullLibrary]) the last step — from the
 * last Home stop to the library — advances by [libraryStride] instead: one whole spread, so the
 * two Home panes leave the screen rigidly while the library page takes their place, 1:1 with the
 * finger (iPadOS: the App Library is one more page).
 */
internal data class WorkspacePageMotion(
    val firstHome: Int,
    val homePages: Int,
    val pageWidth: Float,
    val homeStride: Float,
    val libraryStride: Float = homeStride,
) {
    init {
        require(homePages > 0)
        require(pageWidth.isFinite() && pageWidth > 0f)
        require(homeStride.isFinite() && homeStride > 0f)
        require(libraryStride.isFinite() && libraryStride > 0f)
    }

    /** Logical index of the last Home stop: the library step starts here. */
    private val lastHome: Float get() = (homePages - 1).toFloat()

    /** Visual scroll offset for a physical (and possibly fractional) pager position. */
    fun offset(position: Float): Float {
        val logical = position - firstHome
        return when {
            logical < 0f -> logical * pageWidth
            logical <= lastHome -> logical * homeStride
            else -> lastHome * homeStride + (logical - lastHome) * libraryStride
        }
    }

    /** Physical pager position for a visual scroll offset. */
    fun position(offset: Float): Float {
        val lastHomeOffset = lastHome * homeStride
        val logical = when {
            offset < 0f -> offset / pageWidth
            offset <= lastHomeOffset -> offset / homeStride
            else -> lastHome + (offset - lastHomeOffset) / libraryStride
        }
        return logical + firstHome
    }

    fun positionAfterVisualDelta(position: Float, delta: Float): Float =
        position(offset(position) + delta)

    fun stride(fromPosition: Int, towardPosition: Int): Float =
        abs(offset(towardPosition.toFloat()) - offset(fromPosition.toFloat()))
}

/**
 * Where each Home pane (Today View, a Home page, the App Library) is DRAWN while the workspace
 * pages. iOS pages are opaque and rigid: every pane moves exactly with the finger, so the gaps
 * between panes never change during a swipe, and a page comes in from beyond the screen edge.
 *
 * History (owner, rounds 3–6): with Apple's equal gaps the pane after the right-hand one rests under the
 * Dock column. Rounds 3–4 parked it off screen and made it catch up (≈1.3× the finger): the right page
 * "chased" the left one. Round 5 clipped every pane at the Dock column instead: icons then appeared and
 * vanished in mid-screen ("谁让你加一个遮罩了"). Now the unfolded gap between the two pages is wide enough
 * ([innerSpreadGaps]) for the next pane to rest beyond the screen edge, so the strip is rigid, nothing is
 * clipped and nothing catches up.
 *
 * All values are in one pixel unit. A pane's content x is the left edge of its cell grid.
 */
internal data class WorkspacePaneTrack(
    /** Screen x of the leading resting pane's content (the grid inset). */
    val restLeft: Float,
    /** Distance between consecutive panes. */
    val stride: Float,
    /** Panes resting on screen: two unfolded, one on the cover. */
    val restingPanes: Int,
    /** Drawn width of a pane from its content x (the cell grid). */
    val paneWidth: Float,
    /** The whole window, including the Dock column. */
    val screenWidth: Float,
    /** Jiggle badges, widget rotation and shadows reach this far beyond the cell grid ([PANE_DRAW_MARGIN_DP]). */
    val margin: Float,
) {
    init {
        require(stride.isFinite() && stride > 0f)
        require(restingPanes >= 1)
        require(paneWidth.isFinite() && paneWidth >= 0f && screenWidth.isFinite() && screenWidth > 0f)
    }

    /** Screen x of a pane whose rigid-strip position (content x − scroll) is [stripX]: always the strip itself. */
    fun screenX(stripX: Float): Float = stripX

    /** Whether any of a pane drawn at [screenX] is on screen (half a pixel absorbs float rounding at rest). */
    fun onScreen(screenX: Float): Boolean =
        screenX - margin < screenWidth - .5f && screenX + paneWidth + margin > .5f

    /** A fixed off-screen x for a pane that cannot be seen, so its layer stops moving every frame. */
    fun parkedX(screenX: Float): Float = if (screenX > 0f) screenWidth + margin else -(paneWidth + margin)

    /**
     * Panes (index i at content x = [origin] + i · stride) drawn for some scroll in
     * [scrollLow, scrollHigh]. Screen x falls monotonically as the scroll grows, so a pane is
     * needed iff its x range over the interval meets the screen.
     */
    fun panesSeenBetween(scrollLow: Float, scrollHigh: Float, origin: Float, first: Int, last: Int): List<Int> {
        val low = minOf(scrollLow, scrollHigh); val high = maxOf(scrollLow, scrollHigh)
        return (first..last).filter { pane ->
            val content = origin + pane * stride
            screenX(content - high) - margin < screenWidth - .5f && screenX(content - low) + paneWidth + margin > .5f
        }
    }
}

/**
 * How far a pane's drawing can reach beyond its cell grid, in dp. Widgets are inset ≈8.8 dp in their
 * cells, so the furthest pixels are a jiggling widget's − badge (2.3 dp inside, plus its 1.5 dp shadow and
 * ≈1.8 dp of rotation) and the resize handle (≈1 dp outside): ≤ 1.5 dp, so 4 dp is a safe bound.
 */
internal const val PANE_DRAW_MARGIN_DP = 4f

/**
 * Physical pager pages whose panes must be composed: [reach] pages either side of the settled page
 * (2: one gesture moves at most one page, and its rubber band can reveal the pane after that), plus
 * every page a programmatic move passes. Panes are composed once and only translated afterwards, so
 * a swipe never builds a page — or attaches a provider widget's view — while it comes on screen.
 */
internal fun composedPhysicalPages(settled: Int, target: Int, current: Int, pageCount: Int, reach: Int = 2): IntRange {
    val last = (pageCount - 1).coerceAtLeast(0)
    val low = minOf(settled - reach, target - 1, current - 1).coerceIn(0, last)
    val high = maxOf(settled + reach, target + 1, current + 1).coerceIn(0, last)
    return low..high
}

/**
 * The panes to compose: everything [needed] now, plus whatever was already composed and is still
 * within [keep] (a wider window). Swiping back and forth between neighbouring pages then never
 * disposes and rebuilds a page (and its provider widgets) at the end of every settle.
 */
internal fun retainedPanes(needed: List<Int>, previous: List<Int>, keep: List<Int>): List<Int> =
    (needed + previous.filter { it in keep }).distinct().sorted()
