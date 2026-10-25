package com.jake.duolauncher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.foundation.pager.PagerState

/**
 * Home/drop indices stay zero based; the leading component page is logical page -1.
 * Programmatic page moves (Home button, Back, edge-hold turns while dragging, Today/App Library
 * shortcuts) use the same critically damped paging spring as a released swipe; under Reduce Motion
 * they cross-dissolve instead of sliding (iOS), through [effects].
 */
internal class LauncherPager(
    val state: PagerState,
    private val firstHome: Int,
    private val effects: WorkspaceScrollEffects? = null,
) {
    val currentPage get() = state.currentPage - firstHome
    val settledPage get() = state.settledPage - firstHome
    fun requestScrollToPage(page: Int) = state.requestScrollToPage(page + firstHome)
    suspend fun scrollToPage(page: Int) = state.scrollToPage(page + firstHome)
    suspend fun animateScrollToPage(page: Int) = animateToPhysicalPage(page + firstHome)

    suspend fun animateToPhysicalPage(physicalPage: Int) {
        val target = physicalPage.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0))
        if (LauncherMotion.reduced && effects != null && !LauncherMotion.animationsOff &&
            (target != state.currentPage || state.currentPageOffsetFraction != 0f)) {
            dissolveTo(target, effects)
        } else {
            effects?.overscroll = 0f
            slideTo(target)
        }
    }

    /**
     * One continuous spring across every page in between, like iOS `setContentOffset(animated:)`.
     * (`PagerState.animateScrollToPage` first JUMPS to within three pages of a far target, so the
     * Home button from Home 6 made pages appear out of nowhere.) [ExpandedWorkspace] composes every
     * pane between here and [target] as soon as the target is set, so none is built mid-flight.
     */
    private suspend fun slideTo(target: Int) {
        state.scroll {
            with(state) { updateTargetPage(target) }
            val stride = (state.layoutInfo.pageSize + state.layoutInfo.pageSpacing).toFloat()
            if (stride <= 0f) return@scroll
            fun pixels() = (state.currentPage + state.currentPageOffsetFraction) * stride
            val from = pixels()
            val to = target * stride
            if (from != to) animate(from, to, 0f, LauncherMotion.springSpec(LauncherMotion.page, .5f)) { value, _ ->
                scrollBy(value - pixels())
            }
            // Land exactly on the page (rounding), as animateScrollToPage does.
            scrollBy(to - pixels())
        }
    }

    private suspend fun dissolveTo(target: Int, effects: WorkspaceScrollEffects) {
        val fade = Animatable(effects.alpha)
        try {
            fade.animateTo(0f, LauncherMotion.dissolveOut) { effects.alpha = value }
            effects.overscroll = 0f
            state.scrollToPage(target)
            fade.animateTo(1f, LauncherMotion.dissolveIn) { effects.alpha = value }
        } finally {
            // Interrupted (a touch, a newer move): never leave Home faded.
            effects.alpha = 1f
        }
    }
}

/**
 * What the native pager's physical page index means in one posture. Logical pages are
 * Today = -1 (when shown), Home 1..n = 0..n-1 and the App Library = n.
 * - Single page (cover, inner portrait): physical = logical + [firstHome].
 * - Unfolded spread: physical p shows two panes; with Today, [Today | Home 1] is 0 and the right
 *   pane is logical p; with Today hidden, [Home 1 | Home 2] is 0 and the right pane is p + 1.
 *
 * The posture-independent identity of "the page in view" is the [anchorPage]: the single page,
 * or the spread's RIGHT pane. iPhone Duo keeps it in the same physical place across a fold: the
 * cover page becomes the inner right half ([Today | P1] from P1, [P(k−1) | Pk] from Pk, [Pn |
 * App Library] from the App Library), and folding shows the page that was on the right half.
 */
internal data class PagerPosture(val spread: Boolean, val leading: Boolean) {
    /** Physical index of Home 1 as a single page; [LauncherPager] subtracts it for logical pages. */
    val firstHome: Int get() = if (leading && !spread) 1 else 0

    fun anchorPage(physicalPage: Int): Int = when {
        !spread -> physicalPage - firstHome
        leading -> physicalPage
        else -> physicalPage + 1
    }

    fun physicalPage(anchorPage: Int, pageCount: Int): Int = when {
        !spread -> anchorPage + firstHome
        leading -> anchorPage
        else -> anchorPage - 1
    }.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
}

/** The page a new posture's pager must show on its very first frame (see [PagerPosture]). */
internal fun remapPhysicalPageForPosture(
    physicalPage: Int,
    from: PagerPosture,
    to: PagerPosture,
    toPageCount: Int,
): Int = to.physicalPage(from.anchorPage(physicalPage), toPageCount)

/**
 * Hands the page in view from one posture's pager to the next. A posture change creates a
 * new pager whose INITIAL page is already remapped, in the same composition that switches
 * the layout, so the first frame in the new posture shows the right page. (Core.17 kept one
 * pager and remapped in an effect after that frame: unfolding from Home k first drew
 * [Home k | Home k+1] — the App Library from the last Home — then jumped; folding drew the
 * spread's left page first.) Plain fields: written in a SideEffect, read once per posture.
 */
internal class PagerPostureHandoff {
    private var pager: PagerState? = null
    private var posture: PagerPosture? = null
    /** A spread entered on [P1 | P2] instead of the Today overlay: the pager, its page and the anchor it came from. */
    private var unfoldReturn: Triple<PagerState, Int, Int>? = null

    fun attach(pager: PagerState, posture: PagerPosture) {
        this.pager = pager
        this.posture = posture
    }

    /** Anchor of the previous posture's pager, heading for its target if it was still moving. */
    fun anchor(): Int? {
        val from = posture ?: return null
        val state = pager ?: return null
        val target = androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { state.targetPage }
        // Folding a spread that was never paged since it opened shows the page it opened from.
        unfoldReturn?.let { (owner, page, anchor) -> if (owner === state && page == target) return anchor }
        return from.anchorPage(target)
    }

    /**
     * The first page of [to]'s new pager. Unfolded, Today View is an overlay on physical page 0
     * (iPadOS, [SpreadEnds]); a posture change never opens on it but on the sharp [P1 | P2] spread,
     * and remembers where it came from so folding straight back returns there.
     */
    fun entryPage(to: PagerPosture, fallbackAnchor: Int, pageCount: Int, todayOverlay: Boolean): Int {
        val anchor = anchor() ?: fallbackAnchor
        val page = to.physicalPage(anchor, pageCount)
        return if (todayOverlay && page == 0 && pageCount > 1) 1 else page
    }

    /** Records that [pager] opened on [page] for [anchor] (call with [entryPage]'s result). */
    fun opened(pager: PagerState, page: Int, anchor: Int?) {
        unfoldReturn = if (anchor != null && pager.currentPage == page) Triple(pager, page, anchor) else null
    }
}

/** The cover navigation belongs to Home 1 and follows it to the right. */
internal fun coverPageNavigationProgress(
    expanded: Boolean,
    firstHome: Int,
    physicalPosition: Float,
): Float = if (expanded) 0f else (firstHome - physicalPosition).coerceIn(0f, 1f)

/**
 * The unfolded indicator starts under the right-hand Home pane. As the leading
 * page leaves, it follows the swipe to the centre of the complete icon workspace.
 */
internal fun expandedPageNavigationOffsetFraction(
    expanded: Boolean,
    physicalPosition: Float,
): Float = if (!expanded) 0f else (1f - physicalPosition).coerceIn(0f, 1f) / 4f

/** Keep it composed during a transition so it can slide out and back in. */
internal fun showPageNavigation(
    expanded: Boolean,
    firstHome: Int,
    settledPhysicalPage: Int,
    scrollInProgress: Boolean,
): Boolean = expanded || settledPhysicalPage >= firstHome || scrollInProgress
