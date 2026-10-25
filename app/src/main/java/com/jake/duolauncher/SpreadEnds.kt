package com.jake.duolauncher

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * iPadOS presentation of the two ends of the unfolded workspace (round 5, owner: "左边今日视图，右边资源库
 * 对齐 iPadOS 的样式"; reference photos user-photos-r5/5, 8, 9 from a real iPad).
 *
 * - Today View is not a pane beside Home 1 but a widget column that slides in from the left edge OVER the
 *   Home Screen, which stays where it was ([P1 | P2]) and is Gaussian-blurred and slightly dimmed in
 *   proportion to the swipe (5.webp at rest, 9.webp mid-swipe).
 * - The App Library is a full-screen page over the blurred wallpaper (8.webp), not the right pane beside
 *   the last Home page. Round 6 (owner: "资源库应该是+1页那种感觉"): it is one more page of the rigid strip —
 *   the Home spread slides out to the left with the finger while the library slides in from the right,
 *   one whole spread ([WorkspacePageMotion.libraryStride]), and the wallpaper blur, the Dock, the page
 *   dots and the Search button follow the swipe progress continuously (the Today View principle).
 *
 * The pager's physical pages are unchanged (see [PagerPosture]: 0 = Today, k = [P(k) | P(k+1)], last =
 * App Library), so a fold still hands over the same anchor; only how the two end pages are DRAWN changes.
 * Positions are the native pager's physical position (page + offset fraction).
 *
 * The full-screen App Library needs a Home stop between the two ends, i.e. at least two Home pages; with a
 * single page the library stays the right pane of [P1 | App Library] (still in iPad style, see AppLibrary).
 * The cover keeps iPhone paging (the library is the pane after the last Home page, which already is a
 * page), but its wallpaper blur and chrome fade follow the swipe as well: [libraryVeil]. The rare unfolded
 * layout with Today hidden keeps the old half-pane library: [None].
 *
 * Round 21 (owner's real iPhone Duo screenshots, evidence/r21/ref): [of] no longer builds the iPad ends.
 * iPhone Duo pages the unfolded strip one iPhone page at a time with BOTH ends as ordinary panes: Today
 * View is the sharp left pane of [Today | P1] (4.webp) and the App Library the right pane of [Pn | App
 * Library] (3.webp), where Pn is blurred. On both screens the status rail stays and the Dock stays where
 * it is, blurred, while the library is in (1.webp / 2.webp): [duoChrome]. The iPad paths above remain for
 * the fields' other values.
 */
internal data class SpreadEnds(
    val todayOverlay: Boolean,
    val fullLibrary: Boolean,
    /** Physical index of the App Library page (the pager's last page). */
    val libraryPage: Int,
    /**
     * The wallpaper blurs and the Dock, page dots and Search button fade with the swipe into the App
     * Library (iPhone and iPad both hide the Dock there and blur the wallpaper). False for the half-pane
     * library beside a Home page, where Home stays in view.
     */
    val libraryVeil: Boolean = fullLibrary,
    /**
     * iPhone Duo chrome on the way into the App Library: the status rail stays, the Dock stays in place and
     * blurs ([dockBlurDp]), the page dots and Search fade out ([chromeAlpha]); nothing slides away
     * ([libraryChromeShift] is 0).
     */
    val duoChrome: Boolean = false,
    /** Unfolded spread: the Home pane left of the App Library stays on screen, blurred ([homePaneBlurDp]). */
    val spread: Boolean = false,
) {
    /** 0 on Home, 1 with the Today column fully in. */
    fun todayProgress(position: Float): Float = if (todayOverlay) (1f - position).coerceIn(0f, 1f) else 0f

    /** 0 on the last Home stop, 1 with the App Library fully in. */
    fun libraryProgress(position: Float): Float =
        if (libraryVeil && libraryPage >= 1) (position - (libraryPage - 1)).coerceIn(0f, 1f) else 0f

    /**
     * Pager position the Home panes are drawn at: they stay put while Today View slides over them, and
     * move rigidly with the strip (1:1 with the finger) when the App Library page comes in.
     */
    fun homePosition(position: Float): Float = if (todayOverlay) maxOf(position, 1f) else position

    /** Blur radius (dp) of the Home layer behind the Today column. */
    fun todayBlurDp(position: Float): Float = veilBlurDp(todayProgress(position))

    /** Blur radius (dp) of the wallpaper behind either end. */
    fun wallpaperBlurDp(position: Float): Float = veilBlurDp(maxOf(todayProgress(position), libraryProgress(position)))

    /** Black dim over the blurred Home behind the Today column (5.webp: Home is only slightly darker). */
    fun todayDim(position: Float): Float = TODAY_OVERLAY_DIM * todayProgress(position)

    /**
     * Horizontal shift (px, <= 0) of the Home chrome that belongs to the last Home stop -- the Dock (side
     * column or bottom bar), the status rail, the page dots and the Search button -- on the way into the App
     * Library (owner, r13: the right bar / bottom bar slide left with the icon page as one whole page, never
     * cross-fade; the App Library and the status rail are never on screen together at rest). It is exactly
     * the distance the last Home page itself has travelled ([offset] = [WorkspacePageMotion.offset]), so the
     * chrome and the icons move as one sheet, 1:1 with the finger, and come back the same way. 0 up to the
     * last Home stop, and without a library veil (the half-pane library beside a Home page keeps its chrome).
     */
    fun libraryChromeShift(position: Float, offset: (Float) -> Float): Float {
        if (!libraryVeil || duoChrome || libraryPage < 1) return 0f
        val lastHome = (libraryPage - 1).toFloat()
        return -(offset(position.coerceIn(lastHome, libraryPage.toFloat())) - offset(lastHome))
    }

    /** Blur radius (dp) of the Dock, which stays in place while the App Library comes in (iPhone Duo). */
    fun dockBlurDp(position: Float): Float = if (duoChrome) duoLibraryBlurDp(libraryProgress(position)) else 0f

    /** Blur radius (dp) of the Home panes: unfolded, the page beside the App Library is blurred (3.webp). */
    fun homePaneBlurDp(position: Float): Float = if (duoChrome && spread) duoLibraryBlurDp(libraryProgress(position)) else 0f

    /** Opacity of the page dots and Search, which iPhone Duo does not show over the App Library. */
    fun chromeAlpha(position: Float): Float = if (duoChrome) 1f - libraryProgress(position) else 1f

    companion object {
        val None = SpreadEnds(todayOverlay = false, fullLibrary = false, libraryPage = 0)

        /**
         * [physicalPages] = the native pager's page count in this posture. iPhone Duo in every posture: both
         * ends are panes of the strip (see the class comment); the library veil needs a Home stop before it.
         */
        fun of(spread: Boolean, leading: Boolean, physicalPages: Int): SpreadEnds {
            val last = (physicalPages - 1).coerceAtLeast(0)
            // The rare spread with Today hidden pages [P1 | P2] … and keeps its plain half-pane library.
            if (spread && !leading) return None
            return SpreadEnds(todayOverlay = false, fullLibrary = false, libraryPage = last,
                libraryVeil = last >= 1, duoChrome = true, spread = spread)
        }
    }
}

/**
 * iPad blurs Home heavily (the icons in 5.webp are unrecognisable blobs) and the blur is already most of the
 * way there half-way through the swipe (9.webp), so it eases out rather than growing linearly.
 */
internal fun veilBlurDp(progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    return SPREAD_VEIL_BLUR_DP * (1f - (1f - p) * (1f - p))
}

internal const val SPREAD_VEIL_BLUR_DP = 30f

/**
 * The icons beside iPhone Duo's App Library stay coloured blobs, not a haze: measured edge spread σ ≈ 0.15–0.22 of
 * an icon on the owner's 2.webp (Dock) and 3.webp (left page), ≈ 11 dp, i.e. an 18 dp blur radius (Android's
 * radius → σ = 0.577 r + 0.5). Same easing as the wallpaper veil.
 */
internal const val DUO_LIBRARY_ICON_BLUR_DP = 18f

internal fun duoLibraryBlurDp(progress: Float): Float = veilBlurDp(progress) * (DUO_LIBRARY_ICON_BLUR_DP / SPREAD_VEIL_BLUR_DP)
internal const val TODAY_OVERLAY_DIM = .2f

/**
 * Horizontal shift of the Today column for [progress]: it comes in from beyond the left edge and travels
 * [travel] (its right edge plus the draw margin), which is within 1 % of one pane stride, so it follows
 * the finger as the iPad column does.
 */
internal fun todayColumnShift(progress: Float, travel: Float): Float = -(1f - progress.coerceIn(0f, 1f)) * travel

/**
 * Horizontal shift of the full-screen App Library page: its place on the rigid strip, i.e. the visual
 * distance still to travel to it ([libraryOffset] − [currentOffset], one whole spread at the last Home stop,
 * 0 at rest), so it moves exactly with the finger and the Home spread it pushes out. While it is not in at
 * all it is parked beyond the right edge so its (transparent) surface never takes the touches meant for Home.
 */
internal fun fullLibraryShift(progress: Float, libraryOffset: Float, currentOffset: Float, parked: Float): Float =
    if (progress <= .001f) parked else libraryOffset - currentOffset

/**
 * A pointer target that consumes nothing: Compose's hit test stops here, so whatever lies beneath (the
 * blurred Home behind Today View, the faded Home under the App Library) is not offered the touch, while
 * the ancestors' paging gesture still sees it.
 */
internal fun Modifier.inertPointerTarget(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent() }
}

/**
 * Launch-and-return keeps the App Library search (owner: "搜索App后返回桌面，应该还在搜索app的界面才对").
 * iOS brings you back to the App Library with the query and results still there when you return from an
 * app you opened from its search; pressing Home while you are already looking at Home leaves the App
 * Library for the Home Screen and the next visit starts fresh.
 */
internal fun keepsLibrarySearchOnHome(returningFromApp: Boolean, onLibrary: Boolean, searching: Boolean): Boolean =
    returningFromApp && onLibrary && searching

/**
 * The Home chrome on the way into the App Library (r13, replaces round 6's fade): the Dock, the status
 * rail, the page dots and Search slide out with the last Home page by [shift] ([SpreadEnds.libraryChromeShift])
 * and back in with it, as on iPhone. Once [hidden] it stops taking touches and long presses (it stays
 * composed, so the Dock's icons and drop regions are not rebuilt on every visit).
 */
internal fun Modifier.libraryChromeCarry(shift: () -> Float, hidden: Boolean): Modifier =
    graphicsLayer { translationX = shift() }.then(if (!hidden) Modifier else Modifier.overlayInputShield()
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        })

/**
 * The Dock's blur under the App Library (iPhone Duo, r21): glass and icons blur together where they stand.
 * Decal and unclipped, so the icons soften into blobs instead of smearing against the Dock's bounds.
 */
internal fun Modifier.dockLibraryBlur(radiusPx: androidx.compose.ui.graphics.GraphicsLayerScope.() -> Float): Modifier =
    graphicsLayer {
        val radius = radiusPx()
        renderEffect = if (radius > .5f) androidx.compose.ui.graphics.BlurEffect(radius, radius,
            androidx.compose.ui.graphics.TileMode.Decal) else null
        clip = false
    }
