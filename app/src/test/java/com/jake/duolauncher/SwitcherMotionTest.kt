package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class SwitcherMotionTest {
    private val coverW = 425f
    private val coverH = 622f
    private val innerW = 860f
    private val innerH = 608f

    // ---------------------------------------------------------------- release classification

    @Test fun `taps and jitter never leave the app`() {
        assertEquals(SwitcherCommit.NONE, SwitcherMotion.commit(0f, 3_000f, coverH, false))
        assertEquals(SwitcherCommit.NONE, SwitcherMotion.commit(17f, 3_000f, coverH, false))
        // Above the absolute minimum but short and slow: a pull back.
        assertEquals(SwitcherCommit.NONE, SwitcherMotion.commit(25f, 200f, coverH, false))
    }

    @Test fun `a short fast flick and an ordinary swipe go home`() {
        assertEquals(SwitcherCommit.HOME, SwitcherMotion.commit(25f, 400f, coverH, false))
        assertEquals(SwitcherCommit.HOME, SwitcherMotion.commit(120f, 900f, coverH, false))
        // A long fast swipe is still Home, not the switcher (iOS).
        assertEquals(SwitcherCommit.HOME, SwitcherMotion.commit(400f, 2_000f, coverH, false))
    }

    @Test fun `moving back down cancels`() {
        assertEquals(SwitcherCommit.NONE, SwitcherMotion.commit(200f, -250f, coverH, false))
        assertEquals(SwitcherCommit.NONE, SwitcherMotion.commit(200f, -900f, coverH, true))
        assertEquals(SwitcherCommit.HOME, SwitcherMotion.commit(200f, -249f, coverH, false))
    }

    /** Round 12 (iOS): once the pause has revealed the cards, any release opens the switcher; only a pull-back cancels. */
    @Test fun `an engaged switcher opens on any release except a pull-back`() {
        assertEquals(SwitcherCommit.SWITCHER, SwitcherMotion.commit(120f, 0f, coverH, true))
        assertEquals(SwitcherCommit.SWITCHER, SwitcherMotion.commit(120f, 1_099f, coverH, true))
        // Round 11 sent this hard flick Home ("先慢慢拖…再快速往上拖，它并不会唤起APP召唤器"): now the switcher.
        assertEquals(SwitcherCommit.SWITCHER, SwitcherMotion.commit(120f, SwitcherMotion.HOME_FLICK_VELOCITY, coverH, true))
        assertEquals(SwitcherCommit.SWITCHER, SwitcherMotion.commit(120f, 4_000f, coverH, true))
        // Pulling back down still cancels; engaged, then dragged back to the bottom edge: back to the app.
        assertEquals(SwitcherCommit.NONE, SwitcherMotion.commit(200f, SwitcherMotion.PULL_BACK_VELOCITY, coverH, true))
        assertEquals(SwitcherCommit.NONE, SwitcherMotion.commit(25f, 0f, coverH, true))
        // Not engaged, a hard flick still goes Home.
        assertEquals(SwitcherCommit.HOME, SwitcherMotion.commit(120f, 1_100f, coverH, false))
    }

    @Test fun `a finger stopped high up without the hold timer still opens the switcher`() {
        assertEquals(SwitcherCommit.SWITCHER, SwitcherMotion.commit(coverH * .25f, 40f, coverH, false))
        assertEquals(SwitcherCommit.HOME, SwitcherMotion.commit(coverH * .25f, 200f, coverH, false))
        assertEquals(SwitcherCommit.HOME, SwitcherMotion.commit(coverH * .15f, 0f, coverH, false))
    }

    @Test fun `quick switch commits on distance or a flick toward an existing neighbour`() {
        assertEquals(QuickSwitchCommit.PREVIOUS, SwitcherMotion.quickCommit(coverW * .3f, 0f, coverW, true, true))
        assertEquals(QuickSwitchCommit.NONE, SwitcherMotion.quickCommit(coverW * .3f, 0f, coverW, false, true))
        assertEquals(QuickSwitchCommit.NEXT, SwitcherMotion.quickCommit(-coverW * .3f, 0f, coverW, true, true))
        assertEquals(QuickSwitchCommit.NONE, SwitcherMotion.quickCommit(-coverW * .3f, 0f, coverW, true, false))
        assertEquals(QuickSwitchCommit.PREVIOUS, SwitcherMotion.quickCommit(40f, 700f, coverW, true, true))
        assertEquals(QuickSwitchCommit.NONE, SwitcherMotion.quickCommit(20f, 700f, coverW, true, true))
        assertEquals(QuickSwitchCommit.NONE, SwitcherMotion.quickCommit(40f, 300f, coverW, true, true))
        // A long drag thrown back the other way cancels.
        assertEquals(QuickSwitchCommit.NONE, SwitcherMotion.quickCommit(coverW * .4f, -800f, coverW, true, true))
    }

    // ---------------------------------------------------------------- dragged card

    @Test fun `drag scale starts at one and eases toward its floor`() {
        assertEquals(1f, SwitcherMotion.dragScale(0f, coverH), .0001f)
        var last = 1f
        for (t in listOf(20f, 60f, 120f, 240f, 480f, 2_000f)) {
            val s = SwitcherMotion.dragScale(t, coverH)
            assertTrue("monotonic at $t", s < last)
            assertTrue(s > SwitcherMotion.DRAG_SCALE_FLOOR)
            last = s
        }
        // Around a third of the height the app is roughly switcher-sized (iOS).
        assertEquals(.6f, SwitcherMotion.dragScale(coverH * .3f, coverH), .03f)
    }

    @Test fun `the finger holds the card's bottom edge and its horizontal position`() {
        val card = SwitcherMotion.dragCard(30f, 150f, coverW, coverH)
        assertEquals(coverH - 150f, card.bottom, .01f)
        assertEquals(coverW / 2f + 30f * SwitcherMotion.DRAG_X_FOLLOW, card.centerX, .01f)
        assertEquals(coverW / coverH, card.width / card.height, .0001f)
        val rest = SwitcherMotion.dragCard(0f, 0f, coverW, coverH)
        assertEquals(ZoomBounds(0f, 0f, coverW, coverH), rest)
        // Past the knee the card lags the finger.
        val far = SwitcherMotion.dragCard(0f, coverH * .8f, coverW, coverH)
        assertTrue(far.bottom > coverH - coverH * .8f)
    }

    @Test fun `drag progress reaches one at switcher size`() {
        assertEquals(0f, SwitcherMotion.dragProgress(1f, coverW, coverH), 0f)
        assertEquals(1f, SwitcherMotion.dragProgress(SwitcherMotion.PORTRAIT_SCALE, coverW, coverH), .0001f)
        assertEquals(1f, SwitcherMotion.dragProgress(.3f, coverW, coverH), 0f)
    }

    // ---------------------------------------------------------------- deck

    @Test fun `deck cards keep the panel shape at the iOS scale`() {
        // Photo 5: the settled card is 0.68 of the screen; photo 10: 0.7 on the inner panel in landscape.
        val cover = SwitcherMotion.deck(coverW, coverH, 34f)
        assertEquals(coverW * .68f, cover.cardWidth, .01f)
        assertEquals(coverH * .68f, cover.cardHeight, .01f)
        assertEquals(34f * .68f, cover.cornerRadius, .001f)
        val inner = SwitcherMotion.deck(innerW, innerH, 28f)
        assertEquals(innerW * .7f, inner.cardWidth, .01f)
        // Room for the icon + name row above the cards.
        assertTrue(cover.centerY - cover.cardHeight / 2f > SwitcherMotion.LABEL_SPACE)
        assertTrue(inner.centerY - inner.cardHeight / 2f > SwitcherMotion.LABEL_ICON)
        // Both the focused card and the label stay on screen.
        assertTrue(cover.focusLeft > 0f && cover.focusLeft + cover.cardWidth < coverW)
    }

    @Test fun `older cards bunch up on the left under newer ones`() {
        val g = SwitcherMotion.deck(coverW, coverH, 34f)
        val cards = SwitcherMotion.deckCards(5, 0f, g)
        assertEquals(g.focusLeft, cards[0].bounds.left, .001f)
        assertEquals(g.cardWidth, cards[0].visibleWidth, .001f)
        assertTrue(cards[2].visibleWidth < cards[1].visibleWidth)
        assertTrue(cards[3].visibleWidth < cards[2].visibleWidth)
        // The pile converges on its left limit instead of marching off screen.
        cards.forEach { assertTrue(it.bounds.left >= g.pileLeft - 1e-3f) }
        // Geometric: each older card keeps the same fraction of the distance to the limit.
        val r1 = (cards[1].bounds.left - g.pileLeft) / (cards[0].bounds.left - g.pileLeft)
        val r2 = (cards[2].bounds.left - g.pileLeft) / (cards[1].bounds.left - g.pileLeft)
        assertEquals(g.pileRatio, r1, 1e-4f)
        assertEquals(g.pileRatio, r2, 1e-4f)
        // Deeper cards are a little smaller, all on one centre line.
        assertTrue(cards[1].bounds.height < cards[0].bounds.height)
        cards.forEach { assertEquals(g.centerY, it.bounds.centerY, .001f) }
    }

    @Test fun `scrolling brings older cards to the focus and pushes newer ones right`() {
        val g = SwitcherMotion.deck(coverW, coverH, 34f)
        val cards = SwitcherMotion.deckCards(3, 1f, g)
        assertEquals(g.focusLeft, cards[1].bounds.left, .001f)
        assertEquals(g.focusLeft + g.cardWidth * SwitcherMotion.NEWER_STRIDE, cards[0].bounds.left, .01f)
        assertEquals(g.cardWidth, cards[1].visibleWidth.coerceAtMost(g.cardWidth), .001f)
    }

    @Test fun `labels fade as cards get covered and slots and lifts move cards`() {
        val g = SwitcherMotion.deck(coverW, coverH, 34f)
        val cards = SwitcherMotion.deckCards(6, 0f, g)
        assertEquals(1f, cards[0].labelAlpha, 0f)
        assertEquals(1f, cards[0].iconAlpha, 0f)
        assertTrue(cards[4].labelAlpha < 1f)
        val lifted = SwitcherMotion.deckCards(2, 0f, g, lift = { if (it == 1) 50f else 0f })
        assertEquals(g.centerY - 50f, lifted[1].bounds.centerY, .001f)
        val slotted = SwitcherMotion.deckCards(2, 0f, g, slots = { if (it == 1) 2f else 0f })
        assertEquals(SwitcherMotion.deckLeft(2f, g), slotted[1].bounds.left, .001f)
    }

    @Test fun `the previous app shows well and the older pile stays on screen`() {
        val g = SwitcherMotion.deck(coverW, coverH, 34f)
        val cards = SwitcherMotion.deckCards(8, 0f, g)
        // The card just left rests on the right, close to the edge.
        assertEquals(coverW * (1f - SwitcherMotion.FOCUS_MARGIN), cards[0].bounds.right, .01f)
        // A third of the previous card shows, with its icon and the start of its name (Core.21 showed a sliver).
        assertTrue(cards[1].visibleWidth >= g.cardWidth * .3f)
        assertEquals(1f, cards[1].iconAlpha, 0f)
        assertTrue(cards[1].labelAlpha > 0f)
        // Even the oldest card sits on screen at the left edge; beyond the third nothing shows (photo 5).
        cards.forEach { assertTrue(it.bounds.left > -coverW * .05f) }
        assertEquals(1f, cards[2].alpha, 0f)
        assertEquals(0f, cards[3].alpha, 0f)
    }

    @Test fun `deck speed has no kink at the focus`() {
        val g = SwitcherMotion.deck(coverW, coverH, 34f)
        val older = SwitcherMotion.deckSlope(0f, g)
        val newer = SwitcherMotion.deckSlope(-.001f, g)
        assertTrue(kotlin.math.abs(older - newer) / newer < .05f)
        // Numerically the same as the layout's own derivative.
        val numeric = (SwitcherMotion.deckLeft(.5f, g) - SwitcherMotion.deckLeft(.501f, g)) / .001f
        assertEquals(SwitcherMotion.deckSlope(.5f, g), numeric, 1f)
    }

    @Test fun `the card under the finger stays under the finger`() {
        val g = SwitcherMotion.deck(coverW, coverH, 34f)
        for (slot in listOf(0f, 1f, 3f)) for (scroll in listOf(0f, .4f, 1.3f)) for (dx in listOf(-60f, -5f, 20f, 90f)) {
            val left0 = SwitcherMotion.deckLeft(slot - scroll, g)
            val s = SwitcherMotion.scrollForLeft(slot, left0 + dx, g, 20f)
            val left = SwitcherMotion.deckLeft(slot - s, g)
            if (slot - s < 19.9f) assertEquals(left0 + dx, left, .05f)
        }
        // Past the pile's left limit the card stops instead of producing nonsense.
        val s = SwitcherMotion.scrollForLeft(0f, -coverW, g, 7f)
        assertEquals(-7f, s, 0f)
    }

    @Test fun `home under the dragged card clears with the finger and re-blurs when the switcher engages`() {
        val start = SwitcherMotion.dragBackdrop(0f)
        val half = SwitcherMotion.dragBackdrop(.5f)
        val end = SwitcherMotion.dragBackdrop(1f)
        assertTrue(start.blur > half.blur && half.blur > end.blur)
        assertTrue(start.dim > half.dim && half.dim > end.dim)
        val engaged = SwitcherMotion.dragBackdrop(1f, reveal = 1f)
        assertEquals(1f, engaged.blur, 1e-4f)
        assertEquals(SwitcherMotion.SWITCHER_DIM, engaged.dim, 1e-4f)
        assertEquals(1f, engaged.zoom, 1e-4f)
        // Continuous in between.
        val part = SwitcherMotion.dragBackdrop(1f, reveal = .5f)
        assertTrue(part.blur > end.blur && part.blur < 1f)
        // Progress toward a smaller target (the grid) is measured against that target.
        assertEquals(1f, SwitcherMotion.dragProgress(.27f, innerW, innerH, targetScale = .27f), 1e-4f)
        assertTrue(SwitcherMotion.dragProgress(.5f, innerW, innerH, targetScale = .27f) < .7f)
    }

    @Test fun `scroll rubber-bands past the ends and snaps to a card`() {
        assertEquals(1f, SwitcherMotion.clampScroll(1f, 4), 0f)
        assertEquals(-.3f, SwitcherMotion.clampScroll(-1f, 4), .0001f)
        assertEquals(3.3f, SwitcherMotion.clampScroll(4f, 4), .0001f)
        assertEquals(1f, SwitcherMotion.snapScroll(1.2f, 0f, 4), 0f)
        assertEquals(2f, SwitcherMotion.snapScroll(1.2f, 3f, 4), 0f)
        assertEquals(3f, SwitcherMotion.snapScroll(2.5f, 40f, 4), 0f)
        assertEquals(0f, SwitcherMotion.snapScroll(.4f, -40f, 4), 0f)
        assertEquals(0f, SwitcherMotion.snapScroll(2f, 0f, 0), 0f)
    }

    @Test fun `cards close on a long swipe up or an upward fling`() {
        assertTrue(SwitcherMotion.dismissCommit(coverH * .2f, 0f, coverH))
        assertTrue(SwitcherMotion.dismissCommit(20f, 900f, coverH))
        assertFalse(SwitcherMotion.dismissCommit(8f, 900f, coverH))
        assertFalse(SwitcherMotion.dismissCommit(60f, 300f, coverH))
    }

    // ---------------------------------------------------------------- quick switch, backdrop, flight

    @Test fun `quick switch cards are full size at every rest position`() {
        assertEquals(1f, SwitcherMotion.quickScale(0f, coverW), .0001f)
        assertEquals(1f, SwitcherMotion.quickScale(SwitcherMotion.quickTarget(1, coverW), coverW), .0001f)
        assertEquals(1f, SwitcherMotion.quickScale(SwitcherMotion.quickTarget(-1, coverW), coverW), .0001f)
        assertEquals(1f - SwitcherMotion.QUICK_SHRINK, SwitcherMotion.quickScale(coverW / 2f, coverW), .001f)
        val previous = SwitcherMotion.quickCard(1, SwitcherMotion.quickTarget(1, coverW), 0f, coverW, coverH)
        assertEquals(ZoomBounds(0f, 0f, coverW, coverH).centerX, previous.centerX, .01f)
        val current = SwitcherMotion.quickCard(0, 0f, 0f, coverW, coverH)
        assertEquals(ZoomBounds(0f, 0f, coverW, coverH), current)
    }

    @Test fun `home settles from zoomed and blurred to rest while the card flies back`() {
        val from = SwitcherMotion.dragBackdrop(.5f)
        assertTrue(from.zoom > 1f && from.blur > 0f && from.dim > 0f)
        assertEquals(from, SwitcherMotion.homeReturnBackdrop(from, 0f))
        val landed = SwitcherMotion.homeReturnBackdrop(from, 1f)
        assertEquals(1f, landed.zoom, .0001f)
        assertEquals(0f, landed.blur, .0001f)
        assertEquals(0f, landed.dim, .0001f)
        // Blur and dim are gone well before the zoom finishes.
        val mid = SwitcherMotion.homeReturnBackdrop(from, .6f)
        assertEquals(0f, mid.blur, .0001f)
        assertTrue(mid.zoom > 1f)
        assertEquals(1f + SwitcherMotion.HOME_RETURN_ZOOM, SwitcherMotion.dragBackdrop(0f).zoom, .0001f)
        assertEquals(SwitcherMotion.DRAG_DIM_END, SwitcherMotion.dragBackdrop(1f).dim, .0001f)
    }

    @Test fun `flight progress follows the card height and the icon dissolves in near the end`() {
        val from = ZoomBounds(0f, 0f, 300f, 400f)
        val to = ZoomBounds(100f, 100f, 160f, 160f)
        assertEquals(0f, SwitcherMotion.flightProgress(from, from, to), 0f)
        assertEquals(1f, SwitcherMotion.flightProgress(to, from, to), 0f)
        assertEquals(.5f, SwitcherMotion.flightProgress(ZoomBounds(0f, 0f, 1f, 230f), from, to), .001f)
        // Overshoot past the icon clamps.
        assertEquals(1f, SwitcherMotion.flightProgress(ZoomBounds(0f, 0f, 1f, 50f), from, to), 0f)
        assertEquals(0f, SwitcherMotion.iconMix(.5f), 0f)
        assertEquals(1f, SwitcherMotion.iconMix(.95f), 0f)
        assertEquals(10f, SwitcherMotion.cornerRadius(to, from, 30f, to, 10f), .0001f)
    }

    @Test fun `home indicator matches the iPhone proportions on both panels`() {
        // iPhone: 134 pt of a 393 pt portrait screen; landscape ≈ a quarter of the width.
        assertEquals(144.5f, SwitcherMotion.homeBarWidth(coverW, coverH), .01f)
        assertEquals(206.4f, SwitcherMotion.homeBarWidth(innerW, innerH), .01f)
        assertEquals(5f, SwitcherMotion.HOME_BAR_HEIGHT, 0f)
        assertEquals(8f, SwitcherMotion.HOME_BAR_BOTTOM, 0f)
        assertEquals(230f, SwitcherMotion.homeBarWidth(2_000f, 900f), 0f)
    }

    @Test fun `a launching card is the icon at first and opaque once the icon dissolves`() {
        assertEquals(1f, SwitcherMotion.launchIconMix(0f), 1e-4f)
        assertEquals(0f, SwitcherMotion.launchIconMix(.3f), 1e-4f)
        // The window under the icon: invisible while the icon is solid, opaque before it is half gone.
        assertEquals(0f, SwitcherMotion.windowUnderIcon(1f), 1e-4f)
        assertEquals(1f, SwitcherMotion.windowUnderIcon(.7f), 1e-4f)
        for (i in 0..20) {
            val p = i / 20f
            val icon = SwitcherMotion.launchIconMix(p)
            // Never a see-through moment: icon + window together always cover the card.
            assertTrue(icon + SwitcherMotion.windowUnderIcon(icon) >= .99f)
        }
    }

    @Test fun `retargeting tolerates sub-pixel layout noise only`() {
        val a = ZoomBounds(10f, 10f, 64f, 64f)
        assertTrue(SwitcherMotion.sameRect(a, a.offset(1.5f, -1f), 2f))
        assertFalse(SwitcherMotion.sameRect(a, a.offset(30f, 0f), 2f))
    }

    @Test fun `a card with no icon to land in stays opaque until the very end (N6)`() {
        assertEquals(0f, SwitcherMotion.fallbackFade(.5f), 1e-4f)
        assertEquals(0f, SwitcherMotion.fallbackFade(.75f), 1e-4f)
        assertEquals(1f, SwitcherMotion.fallbackFade(1f), 1e-4f)
    }

    // ---------------------------------------------------------------- round 8: owner's photos 3–5 and 10

    /** Photo 5 (a normal iPhone, opened from Home): the newest card centred, two older ones on the left. */
    @Test fun `from Home the settled deck matches the iPhone photo`() {
        val g = SwitcherMotion.deck(coverW, coverH, 34f, DeckOrigin.HOME)
        val cards = SwitcherMotion.deckCards(6, 0f, g)
        // Photo: card 0.152 … 0.836 W (centre 0.494 W), centre 0.505 H.
        assertEquals(coverW / 2f, cards[0].bounds.centerX, .5f)
        assertEquals(coverH * .505f, cards[0].bounds.centerY, coverH * .01f)
        assertEquals(.68f, cards[0].bounds.width / coverW, .01f)
        // Photo: the second card's left edge at 0.028 W, 0.954 of the first card's size.
        assertEquals(.028f, cards[1].bounds.left / coverW, .008f)
        assertEquals(.954f, cards[1].bounds.height / cards[0].bounds.height, .01f)
        // A sliver of the third; nothing beyond it.
        assertTrue(cards[2].visibleWidth in 2f..30f)
        assertEquals(1f, cards[2].alpha, 0f)
        assertEquals(0f, cards[3].alpha, 0f)
        // The label row (icon 26 pt ≈ 28 dp, 7 pt gap) fits above the card, under the top edge.
        assertTrue(cards[0].bounds.top - SwitcherMotion.LABEL_GAP - SwitcherMotion.LABEL_ICON > 0f)
        assertTrue(cards[0].bounds.bottom < coverH)
    }

    /** Owner: "如果是横屏状态，内屏就会变成类似图十的样子，至多展示两个". */
    @Test fun `inner landscape shows at most two big cards`() {
        for (origin in DeckOrigin.values()) {
            val g = SwitcherMotion.deck(innerW, innerH, 28f, origin)
            val cards = SwitcherMotion.deckCards(6, 0f, g)
            assertEquals(.56f, cards[0].bounds.centerX / innerW, .005f)
            assertTrue(cards[0].bounds.right < innerW)
            // The previous card peeks out on the left: about a fifth of the screen, icon and name readable.
            assertTrue(cards[1].visibleWidth / innerW in .15f..0.25f)
            assertEquals(1f, cards[1].labelAlpha, 0f)
            assertEquals(1f, cards[1].alpha, 0f)
            cards.drop(2).forEach { assertEquals(0f, it.alpha, 0f) }
            // A newer card (scrolled) sits almost entirely off the right edge.
            assertTrue(SwitcherMotion.deckLeft(-1f, g) > innerW * .95f)
        }
    }

    @Test fun `inner portrait and the cover use the iOS deck`() {
        val portrait = SwitcherMotion.deck(innerH, innerW, 28f, DeckOrigin.HOME)
        assertEquals(3, portrait.maxCards)
        assertEquals(innerH * .68f, portrait.cardWidth, .01f)
        assertEquals(innerH / 2f, portrait.focusLeft + portrait.cardWidth / 2f, .5f)
    }

    @Test fun `the newer side starts at the older side's speed and the inverse holds everywhere`() {
        for (g in listOf(SwitcherMotion.deck(coverW, coverH, 34f, DeckOrigin.HOME), SwitcherMotion.deck(coverW, coverH, 34f),
                SwitcherMotion.deck(innerW, innerH, 28f))) {
            val older = SwitcherMotion.deckSlope(0f, g)
            val newer = SwitcherMotion.deckSlope(-1e-4f, g)
            assertEquals(older, newer, older * .01f)
            // One position newer: NEWER_STRIDE card widths right of the focus.
            assertEquals(g.focusLeft + g.cardWidth * SwitcherMotion.NEWER_STRIDE, SwitcherMotion.deckLeft(-1f, g), .01f)
            // Continuous beyond the first position, and monotonic everywhere.
            var last = Float.MAX_VALUE
            var o = -3f
            while (o <= 4f) {
                val left = SwitcherMotion.deckLeft(o, g)
                assertTrue(left < last); last = left
                // (Deep in the pile the distances underflow float precision; the finger never gets there.)
                if (g.pileRatio.pow(o.coerceAtLeast(0f)) > 1e-3f) assertEquals(-o, SwitcherMotion.scrollForLeft(0f, left, g, 20f), .002f)
                o += .05f
            }
        }
    }

    /** Photos 3–4: from Home the newest card enters from the LEFT, with the finger, up to a cap. */
    @Test fun `from Home the card enters from the left in proportion to the finger up to a cap`() {
        val g = SwitcherMotion.deck(coverW, coverH, 34f, DeckOrigin.HOME)
        assertEquals(0f, SwitcherMotion.homeEntryProgress(SwitcherMotion.MIN_COMMIT_DISTANCE, coverH), 0f)
        val cap = SwitcherMotion.MIN_COMMIT_DISTANCE + coverH * SwitcherMotion.HOME_ENTRY_TRAVEL
        assertEquals(1f, SwitcherMotion.homeEntryProgress(cap, coverH), 1e-4f)
        // Beyond the cap the visible width stays the same.
        assertEquals(1f, SwitcherMotion.homeEntryProgress(cap + 200f, coverH), 0f)
        val half = SwitcherMotion.homeEntryCard(SwitcherMotion.homeEntryProgress((SwitcherMotion.MIN_COMMIT_DISTANCE + cap) / 2f, coverH), g)
        val full = SwitcherMotion.homeEntryCard(1f, g)
        // Proportional: half the travel, half the visible width; always hanging off the LEFT edge.
        assertEquals(full.right / 2f, half.right, 1f)
        assertTrue(half.left < 0f && full.left < 0f)
        assertEquals(SwitcherMotion.HOME_ENTRY_VISIBLE, full.right / coverW, 1e-4f)
        assertEquals(0f, SwitcherMotion.homeEntryCard(0f, g).right, 0f)
        // Photos 3–4: ≈ 0.58 of the screen, centred at 0.49 H, smaller than at rest.
        assertEquals(.59f, full.height / coverH, .01f)
        assertEquals(.492f, full.centerY / coverH, .001f)
        // Home blurs with it, both ways.
        assertTrue(SwitcherMotion.homeBackdrop(.2f).blur < SwitcherMotion.homeBackdrop(.4f).blur)
        assertEquals(1f, SwitcherMotion.homeBackdrop(1f).blur, 0f)
        assertEquals(0f, SwitcherMotion.homeBackdrop(0f).dim, 0f)
    }

    @Test fun `on release up to three cards spring out from behind the entering one`() {
        val g = SwitcherMotion.deck(coverW, coverH, 34f, DeckOrigin.HOME)
        val entry = SwitcherMotion.homeEntryCard(.8f, g)
        val starts = (0 until SwitcherMotion.ARRIVE_CARDS).map { SwitcherMotion.homeEntryStart(it, entry, g) }
        assertEquals(entry, starts[0])
        // The older ones wait further left, hidden behind it (or off screen), so they come out after it.
        assertTrue(starts[1].right < starts[0].right && starts[2].right < starts[1].right)
        starts.forEach { assertTrue(it.left <= entry.left && it.height == entry.height) }
        assertTrue(SwitcherMotion.arriveResponse(2) > SwitcherMotion.arriveResponse(1))
        // A light settle: underdamped, but not wobbly (r11: SwiftUI `.snappy`, bounce ≈ 0.16).
        assertTrue(SwitcherMotion.ARRIVE_DAMPING in .8f..0.88f)
    }

    /** Photo 5 on a 393 pt iPhone: icon 26 pt (6.6 % W), name 17 pt (4.3 % W); fractions transfer to the 425 dp cover. */
    @Test fun `label typography keeps the iPhone's fractions of the width`() {
        assertEquals(26f / 393f * coverW, SwitcherMotion.LABEL_ICON, .5f)
        assertEquals(17f / 393f * coverW, SwitcherMotion.LABEL_TEXT, .5f)
    }

    @Test fun `releasing a Home swipe opens the switcher once the card is a third in`() {
        assertEquals(SwitcherCommit.SWITCHER, SwitcherMotion.homeCommit(.6f, 200f, engaged = false))
        assertEquals(SwitcherCommit.SWITCHER, SwitcherMotion.homeCommit(.3f, 100f, engaged = false))
        assertEquals(SwitcherCommit.SWITCHER, SwitcherMotion.homeCommit(.1f, 0f, engaged = true))
        assertEquals(SwitcherCommit.NONE, SwitcherMotion.homeCommit(.2f, 100f, engaged = false))
        assertEquals(SwitcherCommit.NONE, SwitcherMotion.homeCommit(.9f, -400f, engaged = true))
        assertEquals(SwitcherCommit.HOME, SwitcherMotion.homeCommit(.4f, 1500f, engaged = false))
        // Round 12 (iOS): engaged = committed to the switcher, however fast the release.
        assertEquals(SwitcherCommit.SWITCHER, SwitcherMotion.homeCommit(1f, 1500f, engaged = true))
        assertEquals(SwitcherCommit.SWITCHER, SwitcherMotion.homeCommit(.2f, 4000f, engaged = true))
        assertEquals(SwitcherCommit.NONE, SwitcherMotion.homeCommit(1f, SwitcherMotion.PULL_BACK_VELOCITY, engaged = true))
    }

    // ---- round 12: the Home page shrinks under the finger; cards only on a slow or paused drag ----

    @Test fun `the Home page shrinks from full size to the switcher card scale at the travel cap and no further`() {
        val cap = coverH * SwitcherMotion.HOME_ENTRY_TRAVEL
        assertEquals(1f, SwitcherMotion.homeShrink(0f, coverH), 1e-4f)
        assertEquals(SwitcherMotion.PORTRAIT_SCALE, SwitcherMotion.homeShrink(cap, coverH), 1e-4f)
        assertEquals(SwitcherMotion.PORTRAIT_SCALE, SwitcherMotion.homeShrink(cap * 3f, coverH), 1e-4f)
        assertEquals(SwitcherMotion.LANDSCAPE_SCALE, SwitcherMotion.homeShrink(cap * 2f, coverH, SwitcherMotion.LANDSCAPE_SCALE), 1e-4f)
        // Monotonic, and (round 12.4) gentle at first: the owner's Core.29 recording showed a light flick
        // dropping the page to 0.86 in one frame when most of the shrink came early.
        var last = 1f
        for (i in 1..30) { val v = SwitcherMotion.homeShrink(cap * i / 30f, coverH); assertTrue(v < last); last = v }
        val early = 1f - SwitcherMotion.homeShrink(cap * .5f, coverH)
        val late = SwitcherMotion.homeShrink(cap * .5f, coverH) - SwitcherMotion.homeShrink(cap, coverH)
        assertTrue("$early < $late", early < late)
        // A light flick (≈ 0.05 H) barely moves the page; an 80 ms one (≈ 0.1 H) gathers it to ≈ 0.95.
        assertEquals(.986f, SwitcherMotion.homeShrink(coverH * .05f, coverH), .005f)
        assertEquals(.947f, SwitcherMotion.homeShrink(coverH * .1f, coverH), .01f)
    }

    @Test fun `cards blur and dim come only with a pause or a slow deliberate drag, and go away when the finger speeds up`() {
        // A flick: fast and shallow — never.
        assertFalse(SwitcherMotion.homeReveal(.2f, 2_500f, engaged = false))
        assertFalse(SwitcherMotion.homeReveal(1f, 2_500f, engaged = false))
        // Far in and slow: yes; the same depth but fast again: no (reversible).
        assertTrue(SwitcherMotion.homeReveal(.6f, 300f, engaged = false))
        assertFalse(SwitcherMotion.homeReveal(.6f, SwitcherMotion.REVEAL_MAX_VELOCITY, engaged = false))
        assertFalse(SwitcherMotion.homeReveal(SwitcherMotion.HOME_REVEAL_PROGRESS, 0f, engaged = false))
        // The pause (haptic moment) reveals at any depth and stays revealed whatever the finger does.
        assertTrue(SwitcherMotion.homeReveal(.1f, 0f, engaged = true))
        assertTrue(SwitcherMotion.homeReveal(.1f, 3_000f, engaged = true))
        // The same rule as an app card (bottomMove's APP_DRAG line).
        assertEquals(SwitcherMotion.REVEAL_MAX_VELOCITY, 600f)
    }

    @Test fun `the flick bounce is the bouncy spring`() {
        assertEquals(LauncherMotion.bouncySpring.response, SwitcherMotion.HOME_BOUNCE_RESPONSE, 1e-6f)
        assertEquals(LauncherMotion.bouncySpring.dampingFraction, SwitcherMotion.HOME_BOUNCE_DAMPING, 1e-6f)
        assertEquals(26f, SwitcherMotion.HOME_BLUR_DP)
    }

    /** Owner: "一个大大的logo在上面，巨丑无比". A card without a snapshot shows a modest icon. */
    @Test fun `a card without a snapshot never shows a giant logo`() {
        val g = SwitcherMotion.deck(coverW, coverH, 34f)
        val splash = 160f
        val side = SwitcherMotion.placeholderIconSide(g.cardWidth, splash, SwitcherMotion.PLACEHOLDER_ICON_MAX, g.scale)
        assertTrue(side <= SwitcherMotion.PLACEHOLDER_ICON_MAX)
        assertTrue(side <= g.cardWidth * .21f)
        // Full screen (opening) it grows to the splash icon the app will show.
        assertEquals(splash, SwitcherMotion.placeholderIconSide(coverW, splash, SwitcherMotion.PLACEHOLDER_ICON_MAX, 1f), 1e-3f)
    }
}
