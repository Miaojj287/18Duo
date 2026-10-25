package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpreadEndsTest {
    // The iPad ends (rounds 5–20), kept for their own values: unfolded with Today, physical 0 = Today,
    // 1..3 = Home spreads, 4 = App Library (four Home pages). SpreadEnds.of no longer builds them (r21).
    private val ends = SpreadEnds(todayOverlay = true, fullLibrary = true, libraryPage = 4)

    @Test fun `iPhone Duo ends in every posture -- both ends are panes, the chrome stays`() {
        // r21, the owner's screenshots: [Today | P1] sharp (4.webp), [Pn blurred | App Library] (3.webp).
        val inner = SpreadEnds.of(spread = true, leading = true, physicalPages = 5)
        assertFalse(inner.todayOverlay)
        assertFalse(inner.fullLibrary)
        assertTrue(inner.libraryVeil)
        assertTrue(inner.duoChrome)
        assertEquals(4, inner.libraryPage)
        assertEquals(0f, inner.todayProgress(0f), 0f)
        assertEquals(.4f, inner.homePosition(.4f), 0f)
        // Nothing slides away with the last page; the Dock blurs where it is, dots and Search fade.
        assertEquals(0f, inner.libraryChromeShift(3.5f) { it * 400f }, 0f)
        assertEquals(0f, inner.dockBlurDp(3f), 0f)
        assertEquals(duoLibraryBlurDp(.5f), inner.dockBlurDp(3.5f), 1e-4f)
        assertEquals(DUO_LIBRARY_ICON_BLUR_DP, inner.homePaneBlurDp(4f), 1e-4f)
        assertEquals(.25f, inner.chromeAlpha(3.75f), 1e-6f)
        assertEquals(1f, inner.chromeAlpha(2f), 0f)
        val cover = SpreadEnds.of(spread = false, leading = true, physicalPages = 6)
        assertFalse(cover.todayOverlay)
        assertFalse(cover.fullLibrary)
        assertTrue(cover.duoChrome)
        // The cover's last Home page is off screen at rest: only the wallpaper and the Dock blur.
        assertEquals(0f, cover.homePaneBlurDp(5f), 0f)
        assertEquals(DUO_LIBRARY_ICON_BLUR_DP, cover.dockBlurDp(5f), 1e-4f)
        assertEquals(SpreadEnds.None, SpreadEnds.of(spread = true, leading = false, physicalPages = 5))
        // [Today | P1] then [P1 | App Library]: the single page is blurred beside the library.
        val one = SpreadEnds.of(spread = true, leading = true, physicalPages = 2)
        assertEquals(1, one.libraryPage)
        assertEquals(DUO_LIBRARY_ICON_BLUR_DP, one.homePaneBlurDp(1f), 1e-4f)
    }

    @Test fun `a single Home page keeps the App Library as the right pane so a Home stop remains`() {
        val one = SpreadEnds(todayOverlay = true, fullLibrary = false, libraryPage = 1)
        assertTrue(one.todayOverlay)
        assertFalse(one.fullLibrary)
        assertEquals(1f, one.homePosition(0f), 0f)
        assertEquals(1f, one.homePosition(1f), 0f)
        assertEquals(0f, one.libraryProgress(1f), 0f)
    }

    @Test fun `Today progress grows from Home 1 to the Today page`() {
        assertEquals(0f, ends.todayProgress(1f), 0f)
        assertEquals(.25f, ends.todayProgress(.75f), 1e-6f)
        assertEquals(1f, ends.todayProgress(0f), 0f)
        assertEquals(0f, ends.todayProgress(2.5f), 0f)
        assertEquals(0f, SpreadEnds.None.todayProgress(0f), 0f)
    }

    @Test fun `Home stays put under Today View but pages rigidly into the App Library`() {
        assertEquals(1f, ends.homePosition(0f), 0f)
        assertEquals(1f, ends.homePosition(.4f), 0f)
        assertEquals(2.3f, ends.homePosition(2.3f), 1e-6f)
        // Round 6: the library is one more page, so Home follows the strip (no clamp at the last stop).
        assertEquals(3.6f, ends.homePosition(3.6f), 1e-6f)
        assertEquals(4f, ends.homePosition(4f), 0f)
        assertEquals(.4f, SpreadEnds.None.homePosition(.4f), 0f)
    }

    @Test fun `the Home chrome slides out with the last Home page by the page's own offset`() {
        assertEquals(0f, ends.libraryProgress(3f), 0f)
        assertEquals(.5f, ends.libraryProgress(3.5f), 1e-6f)
        assertEquals(1f, ends.libraryProgress(4f), 0f)
        // Unfolded: Home stride 400 px, the library step one whole spread (900 px).
        val motion = WorkspacePageMotion(firstHome = 0, homePages = 4, pageWidth = 800f, homeStride = 400f, libraryStride = 900f)
        assertEquals(0f, ends.libraryChromeShift(2f, motion::offset), 0f)
        assertEquals(0f, ends.libraryChromeShift(3f, motion::offset), 0f)
        assertEquals(-225f, ends.libraryChromeShift(3.25f, motion::offset), 1e-3f)
        assertEquals(-900f, ends.libraryChromeShift(4f, motion::offset), 1e-3f)
        // The rubber band past the library never drags the chrome further.
        assertEquals(-900f, ends.libraryChromeShift(4.2f, motion::offset), 1e-3f)
    }

    @Test fun `the cover library blurs the wallpaper and hides the Dock progressively too`() {
        // Cover with Today: physical 0 = Today, 1..3 = Home 1..3, 4 = App Library (the r13 chrome carry).
        val cover = SpreadEnds(todayOverlay = false, fullLibrary = false, libraryPage = 4, libraryVeil = true)
        assertTrue(cover.libraryVeil)
        assertEquals(4, cover.libraryPage)
        assertEquals(0f, cover.libraryProgress(3f), 0f)
        assertEquals(.4f, cover.libraryProgress(3.4f), 1e-6f)
        assertEquals(veilBlurDp(.4f), cover.wallpaperBlurDp(3.4f), 1e-4f)
        // Cover with Today: physical 1 = Home 1, the Dock column leaves with Home 3 one screen (390 px) left.
        val coverMotion = WorkspacePageMotion(firstHome = 1, homePages = 3, pageWidth = 390f, homeStride = 390f)
        assertEquals(-156f, cover.libraryChromeShift(3.4f, coverMotion::offset), 1e-3f)
        assertEquals(-390f, cover.libraryChromeShift(4f, coverMotion::offset), 1e-3f)
        // Never Today's overlay blur, and the cover pages are not clamped.
        assertEquals(0f, cover.todayBlurDp(0f), 0f)
        assertEquals(.5f, cover.homePosition(.5f), 0f)
        // The half-pane library beside a single unfolded Home page keeps Home sharp.
        val one = SpreadEnds(todayOverlay = true, fullLibrary = false, libraryPage = 1)
        assertFalse(one.libraryVeil)
        assertEquals(0f, one.libraryChromeShift(1f) { it * 400f }, 0f)
    }

    @Test fun `blur eases out and dims only behind Today`() {
        assertEquals(0f, veilBlurDp(0f), 0f)
        assertEquals(SPREAD_VEIL_BLUR_DP, veilBlurDp(1f), 0f)
        // Half way through the swipe the blur is already three quarters there (9.webp).
        assertEquals(.75f * SPREAD_VEIL_BLUR_DP, veilBlurDp(.5f), 1e-4f)
        assertEquals(SPREAD_VEIL_BLUR_DP, ends.wallpaperBlurDp(4f), 0f)
        assertEquals(0f, ends.todayBlurDp(4f), 0f)
        assertEquals(TODAY_OVERLAY_DIM, ends.todayDim(0f), 0f)
        assertEquals(0f, ends.todayDim(1f), 0f)
    }

    @Test fun `Today column slides in from beyond the left edge`() {
        assertEquals(-360f, todayColumnShift(0f, 360f), 0f)
        assertEquals(-180f, todayColumnShift(.5f, 360f), 0f)
        assertEquals(0f, todayColumnShift(1f, 360f), 0f)
    }

    @Test fun `full library sits on the rigid strip and is parked off screen while out`() {
        // Last Home stop at offset 1500, the library one spread (2 × 500) further at 2500.
        assertEquals(3000f, fullLibraryShift(0f, 2500f, 1500f, parked = 3000f), 0f)
        assertEquals(500f, fullLibraryShift(.5f, 2500f, 2000f, parked = 3000f), 0f)
        assertEquals(0f, fullLibraryShift(1f, 2500f, 2500f, parked = 3000f), 0f)
    }

    @Test fun `library search survives only a launch and return on the library`() {
        assertTrue(keepsLibrarySearchOnHome(returningFromApp = true, onLibrary = true, searching = true))
        assertFalse(keepsLibrarySearchOnHome(returningFromApp = false, onLibrary = true, searching = true))
        assertFalse(keepsLibrarySearchOnHome(returningFromApp = true, onLibrary = false, searching = true))
        assertFalse(keepsLibrarySearchOnHome(returningFromApp = true, onLibrary = true, searching = false))
    }
}
