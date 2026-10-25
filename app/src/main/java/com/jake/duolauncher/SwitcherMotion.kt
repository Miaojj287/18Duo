package com.jake.duolauncher

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** What releasing the vertical bottom gesture does. NONE springs the card back into the app (iOS "pull back"). */
internal enum class SwitcherCommit { NONE, HOME, SWITCHER }

/** Horizontal swipe along the home indicator: PREVIOUS = older app (finger moves right), NEXT = newer. */
internal enum class QuickSwitchCommit { NONE, PREVIOUS, NEXT }

/** Where the open switcher was opened from: over an app its card rests on the right, over Home the newest card is centred. */
internal enum class DeckOrigin { APP, HOME }

/** Static geometry of the open app switcher on one panel, in dp. */
internal data class DeckGeometry(
    val screenWidth: Float,
    val screenHeight: Float,
    val cardWidth: Float,
    val cardHeight: Float,
    val scale: Float,
    val cornerRadius: Float,
    /** Left edge of the card that sits at scroll position 0 (the most recent card). */
    val focusLeft: Float,
    val centerY: Float,
    /** The left edge the pile of older cards converges on. */
    val pileLeft: Float = 0f,
    /** Each older card keeps this fraction of the distance the card in front of it has to [pileLeft]. */
    val pileRatio: Float = SwitcherMotion.PILE_RATIO,
    /** At most this many cards show at rest (photo 5: three; photo 10, inner landscape: two). */
    val maxCards: Int = SwitcherMotion.MAX_CARDS,
)

/** One card of the switcher deck; [index] 0 is the most recent app. */
internal data class DeckCard(
    val index: Int,
    val bounds: ZoomBounds,
    /** Width of this card not covered by the newer card lying on top of it. */
    val visibleWidth: Float,
    val iconAlpha: Float,
    val labelAlpha: Float,
    /** 0 for a card that has gone under the pile beyond [DeckGeometry.maxCards]. */
    val alpha: Float = 1f,
)

/** How the frozen Home snapshot behind the cards looks. */
internal data class BackdropStyle(val zoom: Float, val blur: Float, val dim: Float)

/**
 * Pure iOS app-switcher geometry, shared by every renderer (the accessibility overlay draws it; the
 * launcher only mirrors the Home zoom). Measured against the owner's iPhone photos
 * (`reference/user-photos-r8/3–5`, round 8) and Apple's iPhone Duo video
 * (`reference/apple-video/split-view-multitasking.mp4`, 12.4–15.5 s):
 *  - swiping up shrinks the live app into a card whose bottom edge stays under the finger;
 *  - the open switcher is a horizontal deck of full-height snapshots (0.68 of the panel), the newest
 *    on the right lying on top of older ones; the older cards converge geometrically on the left
 *    edge (photo 5: 0.18 of a card shows of the second, a sliver of the third, nothing beyond);
 *  - from Home the newest card enters from the LEFT edge with the finger, up to a cap, and on
 *    release up to three cards spring out into the deck with the newest centred (photos 3–5);
 *  - each card carries its app icon and name above its top-left corner;
 *  - the Home wallpaper is blurred and dimmed behind the deck;
 *  - the inner panel in landscape shows at most two big cards (owner's photo 10).
 * All lengths are dp, velocities dp/s with upward / rightward positive.
 */
internal object SwitcherMotion {
    // ---- vertical release / engage (fractions of the panel height) ----
    /** Below this travel a slow release is a pull back (tap or jitter on the bar). */
    const val MIN_TRAVEL = .05f
    /** A pause above this travel engages the switcher while the finger is still down. */
    const val ENGAGE_TRAVEL = .1f
    const val HOLD_MILLIS = 220L
    /** Absolute minimum before anything can commit, so a sloppy tap never leaves the app. */
    const val MIN_COMMIT_DISTANCE = 18f
    /** A short flick below [MIN_TRAVEL] still goes Home when it is this fast. */
    const val SHORT_FLICK_VELOCITY = 350f
    /** Finger clearly heading back down: return to the app. */
    const val PULL_BACK_VELOCITY = -250f
    /**
     * Round 11 sent an engaged gesture Home above this velocity; round 12 commits an engaged gesture
     * to the switcher on any release (iOS). Kept for the non-engaged paths and the doc's history.
     */
    const val HOME_FLICK_VELOCITY = 1100f
    /** Not engaged but the finger had stopped high up: treat as the switcher, as iOS predicts. */
    const val STOPPED_VELOCITY = 80f
    const val STOPPED_TRAVEL = .2f
    /** Up to this upward speed a drag is deliberate: the deck's neighbours (and the Split View panes) come in. */
    const val REVEAL_MAX_VELOCITY = 600f

    // ---- dragged app card ----
    const val DRAG_SCALE_FLOOR = .36f
    const val DRAG_SCALE_DECAY = .3f
    /** Upward travel beyond this fraction of the height follows the finger with resistance. */
    const val DRAG_FOLLOW_KNEE = .5f
    const val DRAG_FOLLOW_RESISTANCE = .35f
    const val DRAG_X_FOLLOW = .9f

    // ---- deck ----
    /** Photo 5: the settled card is 0.684 W × 0.678 H of the iPhone screen. */
    const val PORTRAIT_SCALE = .68f
    /** Photo 10 (inner landscape): two big cards, the front one ≈ 0.7 of the panel. */
    const val LANDSCAPE_SCALE = .7f
    /**
     * Over an app the card just left rests on the right, this far from the edge, so the previous
     * app shows on its left (Apple's iPhone Duo video 14.0–15.0 s).
     */
    const val FOCUS_MARGIN = .04f
    /** Photo 10: the front card's centre sits at 0.56 W, the previous card peeks out on the left. */
    const val LANDSCAPE_FOCUS_CENTER = .56f
    /** Photo 5: the settled card's centre is at 0.505 H (its label row sits above it). */
    const val PORTRAIT_CENTER_Y = .505f
    /**
     * Older cards converge on [PILE_LEFT] · W: each keeps this fraction of the distance of the card
     * in front of it (photo 5: second card's left edge at 0.028 W under a card at 0.152 W).
     */
    const val PILE_RATIO = .17f
    const val PILE_LEFT = .004f
    const val LANDSCAPE_PILE_RATIO = .05f
    const val LANDSCAPE_PILE_LEFT = .022f
    const val MAX_CARDS = 3
    const val LANDSCAPE_MAX_CARDS = 2
    /** Each step into the pile a card is this much smaller (photo 5: the second card is 0.954 of the first). */
    const val DEPTH_SHRINK = .055f
    /** A card one position newer than the focus lies this many card widths to its right (mostly off screen). */
    const val NEWER_STRIDE = 1.1f
    /** Room for the icon + name row above the cards. */
    const val LABEL_SPACE = 40f
    /** Photo 5: the label icon is 26 pt of a 393 pt screen (0.066 W → 28 dp here), 7 pt above the card, inset 0.06 of the card. */
    const val LABEL_ICON = 28f
    /** Photo 5: the name is SF semibold 17 pt (0.043 W → 18 sp here). */
    const val LABEL_TEXT = 18f
    const val LABEL_GAP = 7f
    const val LABEL_INSET = .06f
    /** Scroll units are card positions; a drag on empty space moves one unit per this fraction of a card. */
    const val SCROLL_UNIT = 1f
    const val SCROLL_OVERSCROLL = .3f
    /** A fling coasts this long before it settles on a card (UIScrollView decelerates over ≈ 0.5 s). */
    const val SCROLL_FLING_SECONDS = .35f
    /** Swiping a card up by this fraction of the height (or flinging) closes it. */
    const val DISMISS_TRAVEL = .18f
    const val DISMISS_VELOCITY = 800f
    /** A card without any snapshot: the app's launch colour with its icon this big (of the card's short side), never a giant logo. */
    const val PLACEHOLDER_ICON = .2f
    const val PLACEHOLDER_ICON_MAX = 60f

    // ---- quick switch ----
    const val QUICK_GAP = 18f
    const val QUICK_SHRINK = .07f
    const val QUICK_COMMIT = .25f
    const val QUICK_FLICK_VELOCITY = 500f
    const val QUICK_FLICK_DISTANCE = 30f

    // ---- Home behind the cards ----
    /** Home starts this much zoomed in behind a leaving app and settles to 1 (iOS ≈ 1.1). */
    const val HOME_RETURN_ZOOM = .1f
    const val DRAG_DIM_START = .6f
    const val DRAG_DIM_END = .25f
    const val SWITCHER_DIM = .25f

    // ---- home indicator (iPhone: 134 × 5 pt, 8 pt above the edge on a 393 pt portrait screen) ----
    const val HOME_BAR_HEIGHT = 5f
    const val HOME_BAR_BOTTOM = 8f
    fun homeBarWidth(screenWidth: Float, screenHeight: Float): Float =
        (screenWidth * if (screenWidth < screenHeight) .34f else .24f).coerceIn(110f, 230f)

    fun commit(travel: Float, velocity: Float, screenHeight: Float, engaged: Boolean): SwitcherCommit {
        val fraction = travel / max(screenHeight, 1f)
        return when {
            travel < MIN_COMMIT_DISTANCE -> SwitcherCommit.NONE
            velocity <= PULL_BACK_VELOCITY -> SwitcherCommit.NONE
            fraction < MIN_TRAVEL && velocity < SHORT_FLICK_VELOCITY -> SwitcherCommit.NONE
            // Round 12 (iOS): once the pause has revealed the cards the gesture is committed to the
            // switcher; any release opens it, only a pull back down (above) cancels. A hard flick no
            // longer goes Home from an engaged drag (the owner: "再快速往上拖，它并不会唤起APP召唤器").
            engaged -> if (fraction < MIN_TRAVEL) SwitcherCommit.NONE else SwitcherCommit.SWITCHER
            fraction >= STOPPED_TRAVEL && abs(velocity) < STOPPED_VELOCITY -> SwitcherCommit.SWITCHER
            else -> SwitcherCommit.HOME
        }
    }

    fun quickCommit(dx: Float, vx: Float, screenWidth: Float, hasPrevious: Boolean, hasNext: Boolean): QuickSwitchCommit {
        val far = abs(dx) >= screenWidth * QUICK_COMMIT
        val flick = abs(dx) >= QUICK_FLICK_DISTANCE && abs(vx) >= QUICK_FLICK_VELOCITY && vx * dx > 0f
        // A flick back against the drag direction cancels even after a long drag.
        if (vx * dx < 0f && abs(vx) >= QUICK_FLICK_VELOCITY) return QuickSwitchCommit.NONE
        if (!far && !flick) return QuickSwitchCommit.NONE
        return when {
            dx > 0f && hasPrevious -> QuickSwitchCommit.PREVIOUS
            dx < 0f && hasNext -> QuickSwitchCommit.NEXT
            else -> QuickSwitchCommit.NONE
        }
    }

    /** Card scale for an upward travel: 1 at rest, easing toward [DRAG_SCALE_FLOOR]. */
    fun dragScale(travel: Float, screenHeight: Float): Float {
        val t = (travel / max(screenHeight, 1f)).coerceAtLeast(0f)
        return DRAG_SCALE_FLOOR + (1f - DRAG_SCALE_FLOOR) * exp(-t / DRAG_SCALE_DECAY)
    }

    /** The finger holds the card's bottom edge; far up it follows with resistance. */
    fun dragFollow(travel: Float, screenHeight: Float): Float {
        val knee = screenHeight * DRAG_FOLLOW_KNEE
        val t = travel.coerceAtLeast(0f)
        return if (t <= knee) t else knee + (t - knee) * DRAG_FOLLOW_RESISTANCE
    }

    /**
     * @param shift extra horizontal offset: the card makes room for the previous app as the
     *   switcher engages (it heads for its place in the deck while the finger still holds it).
     */
    fun dragCard(dx: Float, travel: Float, screenWidth: Float, screenHeight: Float, shift: Float = 0f): ZoomBounds {
        val s = dragScale(travel, screenHeight)
        val w = screenWidth * s
        val h = screenHeight * s
        val bottom = screenHeight - dragFollow(travel, screenHeight)
        return ZoomBounds.centered(screenWidth / 2f + dx * DRAG_X_FOLLOW + shift, bottom - h / 2f, w, h)
    }

    /** 0 while the card is full screen, 1 once it has shrunk to switcher size ([targetScale]). */
    fun dragProgress(scale: Float, screenWidth: Float, screenHeight: Float,
        targetScale: Float = deckScale(screenWidth, screenHeight)): Float {
        val k = targetScale.coerceAtMost(.99f)
        return ((1f - scale) / (1f - k)).coerceIn(0f, 1f)
    }

    fun deckScale(screenWidth: Float, screenHeight: Float): Float =
        if (screenWidth >= screenHeight) LANDSCAPE_SCALE else PORTRAIT_SCALE

    fun deck(screenWidth: Float, screenHeight: Float, screenCornerRadius: Float, origin: DeckOrigin = DeckOrigin.APP): DeckGeometry {
        val landscape = screenWidth >= screenHeight
        val k = deckScale(screenWidth, screenHeight)
        val cw = screenWidth * k
        val ch = screenHeight * k
        val focusLeft = when {
            // Photo 10: the same two-card layout whether it was opened from an app or from Home.
            landscape -> screenWidth * LANDSCAPE_FOCUS_CENTER - cw / 2f
            // Photo 5: from Home the newest card is centred.
            origin == DeckOrigin.HOME -> (screenWidth - cw) / 2f
            else -> screenWidth - cw - screenWidth * FOCUS_MARGIN
        }
        return DeckGeometry(
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            cardWidth = cw,
            cardHeight = ch,
            scale = k,
            cornerRadius = screenCornerRadius * k,
            focusLeft = focusLeft,
            centerY = if (landscape) screenHeight / 2f + LABEL_SPACE / 2f
                else max(screenHeight * PORTRAIT_CENTER_Y, ch / 2f + LABEL_SPACE + 8f),
            pileLeft = screenWidth * if (landscape) LANDSCAPE_PILE_LEFT else PILE_LEFT,
            pileRatio = if (landscape) LANDSCAPE_PILE_RATIO else PILE_RATIO,
            maxCards = if (landscape) LANDSCAPE_MAX_CARDS else MAX_CARDS,
        )
    }

    /** Slope (card widths per position) with which a card leaves the focus toward the pile. */
    private fun olderSlope0(g: DeckGeometry): Float =
        (g.focusLeft - g.pileLeft) * ln(1f / g.pileRatio) / max(g.cardWidth, 1f)

    /**
     * Newer cards (to the right of the focus) move away along `A·u + B·u²` card widths for the first
     * position (u = −offset), then linearly: A matches the older side's slope at the focus, so a
     * card passing through the focus never changes speed abruptly, and A + B = [NEWER_STRIDE].
     */
    private fun newerCoefficients(g: DeckGeometry): Pair<Float, Float> {
        val a = olderSlope0(g).coerceAtMost(NEWER_STRIDE)
        return a to (NEWER_STRIDE - a)
    }

    /** Left edge of a card [offset] positions older (positive, to the left) or newer (negative) than the focus. */
    fun deckLeft(offset: Float, g: DeckGeometry): Float {
        if (offset >= 0f) return g.pileLeft + (g.focusLeft - g.pileLeft) * g.pileRatio.pow(offset)
        val (a, b) = newerCoefficients(g)
        val u = -offset
        val f = if (u <= 1f) a * u + b * u * u else a + b + (a + 2f * b) * (u - 1f)
        return g.focusLeft + g.cardWidth * f
    }

    /** How fast a card at [offset] moves per scroll unit (dp per position). */
    fun deckSlope(offset: Float, g: DeckGeometry): Float {
        if (offset >= 0f) return (g.focusLeft - g.pileLeft) * ln(1f / g.pileRatio) * g.pileRatio.pow(offset)
        val (a, b) = newerCoefficients(g)
        val u = -offset
        return g.cardWidth * if (u <= 1f) a + 2f * b * u else a + 2f * b
    }

    /** Size of a card at [offset] relative to the front card: older cards sit a little deeper (photo 5). */
    fun depthScale(offset: Float, g: DeckGeometry): Float =
        if (offset <= 0f) 1f else 1f - DEPTH_SHRINK * (1f - g.pileRatio.pow(offset))

    /** Opacity of a card at [offset]: those beyond [DeckGeometry.maxCards] have gone under the pile. */
    fun pileAlpha(offset: Float, g: DeckGeometry): Float = smooth(g.maxCards - offset)

    /**
     * The scroll that puts the card at deck position [slot] with its left edge at [left]: the
     * card under the finger stays under the finger while the others move at their own pace
     * (iOS). Beyond the pile's left limit the card cannot go further; [maxOffset] caps it.
     */
    fun scrollForLeft(slot: Float, left: Float, g: DeckGeometry, maxOffset: Float): Float {
        val offset = if (left > g.focusLeft) {
            val (a, b) = newerCoefficients(g)
            val d = (left - g.focusLeft) / max(g.cardWidth, 1f)
            val u = when {
                d > a + b -> 1f + (d - a - b) / (a + 2f * b)
                b < 1e-5f -> d / max(a, 1e-5f)
                else -> (-a + kotlin.math.sqrt(a * a + 4f * b * d)) / (2f * b)
            }
            -u
        } else {
            val k = (left - g.pileLeft) / max(g.focusLeft - g.pileLeft, 1e-3f)
            if (k <= 1e-4f) maxOffset else min(maxOffset, ln(k) / ln(g.pileRatio))
        }
        return slot - offset
    }

    /**
     * @param slots animated position of each card (normally its index; differs while the deck
     *   closes a gap after a dismissal).
     * @param lift upward dismiss offset of each card.
     */
    fun deckCards(
        count: Int,
        scroll: Float,
        g: DeckGeometry,
        slots: (Int) -> Float = { it.toFloat() },
        lift: (Int) -> Float = { 0f },
    ): List<DeckCard> {
        val offsets = FloatArray(count) { slots(it) - scroll }
        val lefts = FloatArray(count) { deckLeft(offsets[it], g) }
        return (0 until count).map { i ->
            val left = lefts[i]
            val s = depthScale(offsets[i], g)
            val cw = g.cardWidth * s
            val ch = g.cardHeight * s
            val visible = if (i == 0) cw else (lefts[i - 1] - left).coerceIn(0f, cw)
            val top = g.centerY - ch / 2f - lift(i)
            DeckCard(
                index = i,
                // Shrunk about its left edge: the edge that shows is exactly where the deck puts it.
                bounds = ZoomBounds(left, top, left + cw, top + ch),
                visibleWidth = visible,
                iconAlpha = ((visible - cw * LABEL_INSET - 4f) / (LABEL_ICON * .6f)).coerceIn(0f, 1f),
                labelAlpha = ((visible - cw * LABEL_INSET - LABEL_ICON - 32f) / 40f).coerceIn(0f, 1f),
                alpha = pileAlpha(offsets[i], g),
            )
        }
    }

    /** Placeholder icon side: small on a deck card (iOS launch screen look), Android's splash size when full screen. */
    fun placeholderIconSide(cardShortSide: Float, splashSide: Float, maxSide: Float, fullness: Float): Float {
        val deckSide = min(cardShortSide * PLACEHOLDER_ICON, maxSide)
        return lerp(deckSide, max(deckSide, splashSide), smooth((fullness - .7f) / .3f))
    }

    // ---- over Home (owner's photos 3–5) ----
    /**
     * Over Home the newest card enters from the left edge: its visible width grows in proportion to
     * the finger's travel until the finger has risen this fraction of the height, and stays the same
     * beyond it ("手指移动最大高度，多余这个高度 APP 从左出来的宽度也依然保持一致").
     */
    const val HOME_ENTRY_TRAVEL = .3f
    /** Visible width of the entering card at the cap (photo 4: 0.25 W; photo 3, part way: 0.14 W). */
    const val HOME_ENTRY_VISIBLE = .26f
    const val LANDSCAPE_HOME_ENTRY_VISIBLE = .22f
    /** The entering card is this fraction of its deck size (photos 3–4: 0.575 → 0.59 of the screen vs 0.68). */
    const val HOME_ENTRY_SCALE_FROM = .84f
    const val HOME_ENTRY_SCALE_TO = .87f
    const val HOME_ENTRY_CENTER_Y = .492f
    /** Cards behind the newest one wait this much further left (fraction of the width) before they spring out. */
    const val HOME_ENTRY_STACK = .1f
    /** Not engaged, a release this far in opens the switcher unless the finger is still flying up. */
    const val HOME_OPEN_PROGRESS = .3f
    const val HOME_OPEN_VELOCITY = 700f
    /** Home is fully blurred once the card is this far in (photo 3: already heavily blurred at half way). */
    const val HOME_BLUR_PROGRESS = .45f
    /**
     * On release up to three cards spring out (SwiftUI response 0.5 s, bounce ≈ 0.16 = `.snappy`:
     * a light settle, 1–2 % overshoot like the owner's recordings; round 8's 0.24 wobbled twice)…
     */
    const val ARRIVE_RESPONSE = .5f
    const val ARRIVE_DAMPING = .84f
    /** …each older card a little later (slower) than the one in front of it, so they fan out. */
    const val ARRIVE_STAGGER = .06f
    const val ARRIVE_CARDS = 3

    /** Over Home, how far the newest card has come in (0 … 1) for an upward [travel]; constant beyond the cap. */
    fun homeEntryProgress(travel: Float, screenHeight: Float): Float =
        ((travel - MIN_COMMIT_DISTANCE) / max(screenHeight * HOME_ENTRY_TRAVEL, 1f)).coerceIn(0f, 1f)

    /**
     * The entering card at [progress] (a spring value, so it may overshoot a little): its right edge
     * is [HOME_ENTRY_VISIBLE] · progress of the width in from the left edge, the rest off screen.
     */
    fun homeEntryCard(progress: Float, g: DeckGeometry): ZoomBounds {
        val landscape = g.screenWidth >= g.screenHeight
        val p = progress.coerceAtLeast(0f)
        val s = g.scale * lerp(HOME_ENTRY_SCALE_FROM, HOME_ENTRY_SCALE_TO, p.coerceAtMost(1f))
        val w = g.screenWidth * s
        val h = g.screenHeight * s
        val right = g.screenWidth * (if (landscape) LANDSCAPE_HOME_ENTRY_VISIBLE else HOME_ENTRY_VISIBLE) * p
        val cy = if (landscape) g.centerY else g.screenHeight * HOME_ENTRY_CENTER_Y
        return ZoomBounds(right - w, cy - h / 2f, right, cy + h / 2f)
    }

    /** Where card [index] starts its spring into the deck: behind and left of the entering card. */
    fun homeEntryStart(index: Int, entry: ZoomBounds, g: DeckGeometry): ZoomBounds =
        entry.offset(-index * g.screenWidth * HOME_ENTRY_STACK, 0f)

    /** Home behind the entering card: blurred and dimmed with the finger, both ways. */
    fun homeBackdrop(progress: Float): BackdropStyle {
        val b = (progress / HOME_BLUR_PROGRESS).coerceIn(0f, 1f)
        return BackdropStyle(zoom = 1f, blur = b, dim = SWITCHER_DIM * b)
    }

    // ---- round 12: the Home page itself shrinks under the finger (iOS) ----
    /**
     * Home blur radius in dp, shared by the stage's frozen copy and the live Home's own blur
     * (round 12 blurs the live Home during a Home swipe, so there is nothing to hand over).
     */
    const val HOME_BLUR_DP = 26f
    /**
     * While the finger drags up from Home the whole page (icons, widgets, Dock) shrinks toward its
     * centre, reaching the switcher card scale at the travel cap ([HOME_ENTRY_TRAVEL]) and staying
     * there beyond it, like the entering card. The wallpaper does not shrink (iOS keeps it full-bleed
     * and blurs it).
     *
     * Round 12.4: the shrink starts gently and gathers pace (a Gaussian onset over [HOME_SHRINK_ONSET]
     * of the height) instead of the app card's front-loaded resistance. The owner's recording of
     * Core.29 (2026-10-21 15:02, f131–f133) showed what the front-loaded curve does to a light flick:
     * the page dropped from 1.0 to 0.86 in one frame and 0.835 the next, then bounced back over
     * 0.3 s — read as "闪一下". Now a 0.1 H flick reaches ≈ 0.95 and a 0.05 H one ≈ 0.99, the
     * "icons gather toward the centre elastically" the owner asked for.
     */
    fun homeShrink(travel: Float, screenHeight: Float, restScale: Float = PORTRAIT_SCALE): Float {
        val cap = HOME_ENTRY_TRAVEL
        val t = (travel / max(screenHeight, 1f)).coerceIn(0f, cap)
        fun onset(x: Float): Float = 1f - exp(-(x / HOME_SHRINK_ONSET) * (x / HOME_SHRINK_ONSET))
        val p = onset(t) / onset(cap)
        return 1f - (1f - restScale) * p
    }

    /** Width (fraction of the height) of the Gaussian onset of [homeShrink]. */
    const val HOME_SHRINK_ONSET = .3f

    /** A Home swipe shows the cards, blur and dim only once the drag is this far in and slow. */
    const val HOME_REVEAL_PROGRESS = .55f
    /**
     * The APP_DRAG rule applied to a Home swipe: the newest cards slide in and Home blurs and dims
     * only once the finger has paused ([engaged]) or the drag is deliberate — far in and slower
     * than [REVEAL_MAX_VELOCITY]. Reversible: a finger that speeds up again (not engaged) takes
     * them away. A 60–120 ms flick never reaches either, so it never shows a card, blur or dim.
     */
    fun homeReveal(progress: Float, velocity: Float, engaged: Boolean): Boolean =
        engaged || progress > HOME_REVEAL_PROGRESS && velocity < REVEAL_MAX_VELOCITY

    /**
     * A flick (or pull-back) released before any reveal springs the page back to full size with a
     * visible bounce: SwiftUI `.bouncy` (response 0.5 s, bounce 0.3 → damping 0.7), the same spring
     * as [LauncherMotion.bouncySpring].
     */
    const val HOME_BOUNCE_RESPONSE = .5f
    const val HOME_BOUNCE_DAMPING = .7f

    /** Releasing a swipe that began on Home: the switcher, Home's first page (a flick), or nothing. */
    fun homeCommit(progress: Float, velocity: Float, engaged: Boolean): SwitcherCommit = when {
        velocity <= PULL_BACK_VELOCITY || progress <= 0f -> SwitcherCommit.NONE
        // Round 12 (iOS): engaged = committed to the switcher; only the pull-back above cancels.
        engaged -> SwitcherCommit.SWITCHER
        progress >= HOME_OPEN_PROGRESS && velocity < HOME_OPEN_VELOCITY -> SwitcherCommit.SWITCHER
        velocity >= SHORT_FLICK_VELOCITY -> SwitcherCommit.HOME
        else -> SwitcherCommit.NONE
    }

    /** Spring response of card [index] springing out of the left edge into the deck. */
    fun arriveResponse(index: Int): Float = ARRIVE_RESPONSE + ARRIVE_STAGGER * index

    /** Clamp with iOS rubber-banding beyond the first and last card. */
    fun clampScroll(scroll: Float, count: Int): Float {
        val max = max(0, count - 1).toFloat()
        return when {
            scroll < 0f -> scroll * SCROLL_OVERSCROLL
            scroll > max -> max + (scroll - max) * SCROLL_OVERSCROLL
            else -> scroll
        }
    }

    /** Where a released deck comes to rest: the card nearest the fling's projection. */
    fun snapScroll(scroll: Float, velocityUnitsPerSecond: Float, count: Int): Float {
        val projected = scroll + velocityUnitsPerSecond.coerceIn(-MAX_FLING_UNITS, MAX_FLING_UNITS) * SCROLL_FLING_SECONDS
        return kotlin.math.round(projected).coerceIn(0f, max(0, count - 1).toFloat())
    }

    /** Fastest fling, in cards per second, a deck release hands to its spring. */
    const val MAX_FLING_UNITS = 14f

    fun dismissCommit(lift: Float, velocityUp: Float, screenHeight: Float): Boolean =
        lift >= screenHeight * DISMISS_TRAVEL || (velocityUp >= DISMISS_VELOCITY && lift > 12f)

    /** Quick-switch card [offset] (0 current, +1 previous/left, −1 next/right) for a horizontal drag. */
    fun quickCard(offset: Int, dx: Float, lift: Float, screenWidth: Float, screenHeight: Float): ZoomBounds {
        val s = quickScale(dx, screenWidth)
        val w = screenWidth * s
        val h = screenHeight * s
        val cx = screenWidth / 2f + dx - offset * (w + QUICK_GAP)
        return ZoomBounds.centered(cx, screenHeight / 2f - lift, w, h)
    }

    /** Cards shrink slightly between rest positions and are full size again at each one. */
    fun quickScale(dx: Float, screenWidth: Float): Float {
        val period = screenWidth + QUICK_GAP
        val phase = abs(dx) % period
        val fromRest = kotlin.math.min(phase, period - phase)
        return 1f - QUICK_SHRINK * smooth(fromRest / max(screenWidth * QUICK_COMMIT, 1f))
    }

    /** Horizontal travel at which the previous (or next) card is centred. */
    fun quickTarget(direction: Int, screenWidth: Float): Float = direction * (screenWidth + QUICK_GAP)

    /**
     * Home behind a card being dragged up: it comes into view as the card shrinks — the blur and
     * the dim fade with the finger, both ways, like the Today View swipe the owner likes. Once the
     * switcher engages ([reveal] → 1) it settles to the switcher's backdrop instead.
     */
    fun dragBackdrop(progress: Float, reveal: Float = 0f): BackdropStyle {
        val p = progress.coerceIn(0f, 1f)
        val e = reveal.coerceIn(0f, 1f)
        val blur = 1f - (1f - DRAG_BLUR_END) * p
        val dim = DRAG_DIM_START + (DRAG_DIM_END - DRAG_DIM_START) * p
        return BackdropStyle(
            zoom = 1f + HOME_RETURN_ZOOM * (1f - .3f * p) * (1f - e),
            blur = lerp(blur, 1f, e),
            dim = lerp(dim, SWITCHER_DIM, e),
        )
    }

    /** Fraction of the blur left when the dragged card has shrunk to switcher size. */
    const val DRAG_BLUR_END = .45f

    /** Home settling under a card that flies back into its icon; [progress] 0 = release, 1 = landed. */
    fun homeReturnBackdrop(from: BackdropStyle, progress: Float): BackdropStyle {
        val p = progress.coerceIn(0f, 1f)
        val settle = 1f - (1f - p) * (1f - p)
        return BackdropStyle(
            zoom = from.zoom + (1f - from.zoom) * settle,
            blur = from.blur * (1f - smooth((p / .6f).coerceIn(0f, 1f))),
            dim = from.dim * (1f - smooth((p / .5f).coerceIn(0f, 1f))),
        )
    }

    /** Fraction of the way from [from] to [to] measured on the card's height, robust to overshoot. */
    fun flightProgress(current: ZoomBounds, from: ZoomBounds, to: ZoomBounds): Float {
        val span = from.height - to.height
        if (abs(span) < 1f) return 1f
        return ((from.height - current.height) / span).coerceIn(0f, 1f)
    }

    /**
     * Fraction of a flight home already done, measured only on how far the card has shrunk toward
     * the icon's height: unchanged when the aim moves (Home relaid out under it), so the corner
     * radius, the icon cross-fade and Home's un-blurring stay continuous through a re-aim.
     */
    fun shrinkProgress(currentHeight: Float, fromHeight: Float, endHeight: Float): Float {
        val span = fromHeight - endHeight
        if (abs(span) < 1f) return 1f
        return ((fromHeight - currentHeight) / span).coerceIn(0f, 1f)
    }

    /** A held card shrinks this much while it waits for Home to say where its icon is. */
    const val HOLD_SHRINK = .85f

    /**
     * Where a released card waits, keeping its momentum, until Home (still laid out for the other
     * panel after a fold) reports its icon: a little above and smaller than where it was let go,
     * never a guessed spot it would have to turn away from.
     */
    fun holdCard(from: ZoomBounds, screenWidth: Float, screenHeight: Float): ZoomBounds =
        ZoomBounds.centered(
            from.centerX.coerceIn(screenWidth * .3f, screenWidth * .7f),
            (from.centerY - screenHeight * .04f).coerceIn(screenHeight * .3f, screenHeight * .62f),
            from.width * HOLD_SHRINK, from.height * HOLD_SHRINK,
        )

    /**
     * Round 13: how much of its slot's own movement (a Home page swiped under it) a card flying home
     * takes on at once: all of it once icon-sized ([progress] 1, it rides with its page), none while it
     * still fills the screen. Its rect is a blend of the two, so the rest arrives through its spring.
     */
    fun slotFollowWeight(progress: Float): Float = progress.coerceIn(0f, 1f)

    /** Home is sharp and undimmed by this point of a flight home ([homeReturnBackdrop]). */
    const val HANDOFF_PROGRESS = .6f

    /**
     * The frozen Home may give way to the live launcher only once it has finished un-blurring and
     * un-dimming: the live Home is sharp, so an earlier swap snapped from blurred to sharp while the
     * card was still in flight (Core.17 review N3). Zoom needs no gate, the launcher mirrors it.
     */
    fun backdropHandoffReady(progress: Float, blur: Float, dim: Float): Boolean =
        progress >= HANDOFF_PROGRESS && blur <= .01f && dim <= .01f

    /** A card flying home with no icon to land in stays opaque and only fades at the very end. */
    fun fallbackFade(progress: Float): Float = smooth((progress - .75f) / .25f)

    /** Snapshot → icon cross-fade near the end of a flight home (iOS dissolves the app into its icon). */
    fun iconMix(progress: Float): Float = smooth(((progress - .55f) / .35f).coerceIn(0f, 1f))

    /**
     * Icon → window at the start of a launch: at 0 the card *is* the icon, and the artwork has
     * dissolved into the (opaque) launch screen by a quarter of the way, while still small.
     */
    fun launchIconMix(progress: Float): Float = 1f - smooth((progress - .02f) / .22f)

    /** Opacity of the window under an icon at [iconMix]: opaque as soon as the icon starts to dissolve. */
    fun windowUnderIcon(iconMix: Float): Float = smooth((1f - iconMix) / .25f)

    fun sameRect(a: ZoomBounds, b: ZoomBounds, tolerance: Float): Boolean =
        abs(a.left - b.left) <= tolerance && abs(a.top - b.top) <= tolerance &&
            abs(a.right - b.right) <= tolerance && abs(a.bottom - b.bottom) <= tolerance

    fun cornerRadius(current: ZoomBounds, from: ZoomBounds, fromRadius: Float, to: ZoomBounds, toRadius: Float): Float {
        val p = flightProgress(current, from, to)
        return fromRadius + (toRadius - fromRadius) * p
    }

    internal fun smooth(value: Float): Float {
        val p = value.coerceIn(0f, 1f)
        return p * p * (3f - 2f * p)
    }

    internal fun lerp(from: Float, to: Float, fraction: Float) = from + (to - from) * fraction

    internal fun lerp(from: ZoomBounds, to: ZoomBounds, fraction: Float) = ZoomBounds(
        lerp(from.left, to.left, fraction), lerp(from.top, to.top, fraction),
        lerp(from.right, to.right, fraction), lerp(from.bottom, to.bottom, fraction),
    )

}

/** What a card flying home is aimed at; see [flightAim]. */
internal enum class FlightAim { HOLD, PROVISIONAL, ICON, FALLBACK }

/**
 * Where a card swiped home aims, re-decided every frame of the flight (pure, JVM-tested):
 *  - ICON: the live Home, laid out for this panel, shows the app's icon;
 *  - FALLBACK: that live layout is settled and has no such icon on screen (another page, App
 *    Library): fly up, shrink and fade (iOS);
 *  - PROVISIONAL: Home is still laid out for the other panel (the phone was folded or unfolded
 *    while the app was in front) but the frozen Home of *this* panel — the backdrop on screen —
 *    shows the icon: aim there from the first frame; the live answer corrects it if it moved;
 *  - HOLD: nothing known yet (task list or layout pending): the card keeps its momentum where it is
 *    instead of heading for a guessed spot and turning (Core.17 review R3).
 * A layout that is still changing (Home relaying out, a page settling) never pulls a card off an
 * icon it is already flying into.
 */
internal fun flightAim(
    current: FlightAim,
    taskKnown: Boolean,
    liveFresh: Boolean,
    liveFound: Boolean,
    liveSettled: Boolean,
    frozenFound: Boolean,
    waitedTooLong: Boolean,
): FlightAim = when {
    liveFresh && liveFound -> FlightAim.ICON
    liveFresh && liveSettled && taskKnown -> FlightAim.FALLBACK
    current == FlightAim.ICON -> FlightAim.ICON
    frozenFound -> FlightAim.PROVISIONAL
    current == FlightAim.FALLBACK || waitedTooLong -> FlightAim.FALLBACK
    else -> FlightAim.HOLD
}
