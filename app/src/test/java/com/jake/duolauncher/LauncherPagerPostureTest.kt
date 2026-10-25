package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherPagerPostureTest {
    private val cover = PagerPosture(spread = false, leading = true)
    private val spread = PagerPosture(spread = true, leading = true)
    private val coverNoToday = PagerPosture(spread = false, leading = false)
    private val spreadNoToday = PagerPosture(spread = true, leading = false)

    // Physical page counts for n homes: Today? + homes + App Library, and one stop per pane.
    private fun coverPages(homes: Int) = homes + 2
    private fun spreadPages(homes: Int) = homes + 1

    @Test fun `first home index follows the leading page only on a single page`() {
        assertEquals(1, cover.firstHome)
        assertEquals(0, spread.firstHome)
        assertEquals(0, coverNoToday.firstHome)
        assertEquals(0, spreadNoToday.firstHome)
    }

    @Test fun `unfolding puts the cover page on the right half like iPhone Duo`() {
        val homes = 4
        // Today -> [Today | P1]; P1 -> [Today | P1]; Pk -> [P(k-1) | Pk]; App Library -> [P4 | App Library].
        assertEquals(0, remapPhysicalPageForPosture(0, cover, spread, spreadPages(homes)))
        assertEquals(0, remapPhysicalPageForPosture(1, cover, spread, spreadPages(homes)))
        for (k in 2..homes) assertEquals(k - 1, remapPhysicalPageForPosture(k, cover, spread, spreadPages(homes)))
        assertEquals(homes, remapPhysicalPageForPosture(homes + 1, cover, spread, spreadPages(homes)))
        for (physical in 0 until coverPages(homes)) {
            val target = remapPhysicalPageForPosture(physical, cover, spread, spreadPages(homes))
            // The spread's right pane is the cover's page (Today has no right pane of its own: Home 1).
            assertEquals(maxOf(0, physical - 1), spread.anchorPage(target))
        }
    }

    @Test fun `folding shows the page that was on the right half`() {
        val homes = 4
        assertEquals(1, remapPhysicalPageForPosture(0, spread, cover, coverPages(homes)))  // [Today | P1] -> P1
        for (p in 1 until homes) assertEquals(p + 1, remapPhysicalPageForPosture(p, spread, cover, coverPages(homes)))
        assertEquals(homes + 1, remapPhysicalPageForPosture(homes, spread, cover, coverPages(homes))) // -> App Library
    }

    @Test fun `every home including third fourth and later survives repeated fold cycles`() {
        for (homes in 1..12) {
            for (coverPage in 1..homes + 1) {
                var page = coverPage
                repeat(10) {
                    val open = remapPhysicalPageForPosture(page, cover, spread, spreadPages(homes))
                    assertEquals("Right pane must remain cover page $coverPage with $homes homes", coverPage - 1, spread.anchorPage(open))
                    page = remapPhysicalPageForPosture(open, spread, cover, coverPages(homes))
                    assertEquals(coverPage, page)
                }
            }
        }
    }

    @Test fun `Today folds back to Home 1 because Home 1 was the right half`() {
        val open = remapPhysicalPageForPosture(0, cover, spread, spreadPages(3))
        assertEquals(1, remapPhysicalPageForPosture(open, spread, cover, coverPages(3)))
    }

    @Test fun `with Today hidden the cover page is still the right half`() {
        val homes = 3
        // Cover physical = home index; spread 0 = [P1 | P2], so P1 can only open as [P1 | P2].
        assertEquals(0, remapPhysicalPageForPosture(0, coverNoToday, spreadNoToday, spreadPages(homes)))
        assertEquals(0, remapPhysicalPageForPosture(1, coverNoToday, spreadNoToday, spreadPages(homes)))
        assertEquals(1, remapPhysicalPageForPosture(2, coverNoToday, spreadNoToday, spreadPages(homes)))
        assertEquals(2, remapPhysicalPageForPosture(3, coverNoToday, spreadNoToday, spreadPages(homes))) // [P3 | App Library]
        assertEquals(1, remapPhysicalPageForPosture(0, spreadNoToday, coverNoToday, homes + 1)) // [P1 | P2] -> P2
        assertEquals(3, remapPhysicalPageForPosture(2, spreadNoToday, coverNoToday, homes + 1)) // [P3 | Lib] -> Lib
        assertEquals(3, remapPhysicalPageForPosture(3, spreadNoToday, coverNoToday, homes + 1)) // [Lib | ] -> Lib
    }

    @Test fun `toggling Today keeps the page in view`() {
        assertEquals(1, remapPhysicalPageForPosture(2, cover, coverNoToday, 4))     // Home 2 stays Home 2
        assertEquals(0, remapPhysicalPageForPosture(0, cover, coverNoToday, 4))     // Today -> Home 1
        assertEquals(2, remapPhysicalPageForPosture(1, coverNoToday, cover, 5))
        assertEquals(1, remapPhysicalPageForPosture(0, spreadNoToday, spread, 4))   // [P1 | P2] stays
        assertEquals(0, remapPhysicalPageForPosture(0, spread, spreadNoToday, 4))   // [Today | P1] -> [P1 | P2]
    }

    @Test fun `same posture never moves and positions are clamped to the target pages`() {
        for (p in 0..5) assertEquals(p, remapPhysicalPageForPosture(p, cover, cover, 6))
        for (p in 0..4) assertEquals(p, remapPhysicalPageForPosture(p, spread, spread, 5))
        assertEquals(2, remapPhysicalPageForPosture(9, cover, spread, 3))
        assertEquals(0, remapPhysicalPageForPosture(-3, spread, cover, 3))
        assertEquals(0, spread.physicalPage(5, pageCount = 0))
    }

    @Test fun `cover page navigation follows first home away from leading page`() {
        assertEquals(0f, coverPageNavigationProgress(false, 1, 1f), 0f)
        assertEquals(.5f, coverPageNavigationProgress(false, 1, .5f), 0f)
        assertEquals(1f, coverPageNavigationProgress(false, 1, 0f), 0f)
        assertEquals(0f, coverPageNavigationProgress(true, 0, 0f), 0f)

        assertEquals(true, showPageNavigation(false, 1, 1, false))
        assertEquals(true, showPageNavigation(false, 1, 0, true))
        assertEquals(false, showPageNavigation(false, 1, 0, false))
        assertEquals(true, showPageNavigation(true, 0, 0, false))
    }

    @Test fun `expanded page navigation follows leading page to workspace centre`() {
        assertEquals(.25f, expandedPageNavigationOffsetFraction(true, 0f), 0f)
        assertEquals(.125f, expandedPageNavigationOffsetFraction(true, .5f), 0f)
        assertEquals(0f, expandedPageNavigationOffsetFraction(true, 1f), 0f)
        assertEquals(0f, expandedPageNavigationOffsetFraction(true, 4f), 0f)
        assertEquals(0f, expandedPageNavigationOffsetFraction(false, 0f), 0f)
    }
    @Test fun `unfolding never opens on the Today overlay and folding straight back returns to the cover page`() {
        val cover = PagerPosture(spread = false, leading = true)
        val inner = PagerPosture(spread = true, leading = true)
        val handoff = PagerPostureHandoff()
        // Cover Home 1 (physical 1 behind Today) …
        val coverPager = androidx.compose.foundation.pager.PagerState(currentPage = 1) { 5 }
        handoff.attach(coverPager, cover)
        // … unfolds on [P1 | P2] (physical 1), not on the Today overlay (physical 0).
        val entry = handoff.entryPage(inner, fallbackAnchor = 0, pageCount = 4, todayOverlay = true)
        assertEquals(1, entry)
        val innerPager = androidx.compose.foundation.pager.PagerState(currentPage = entry) { 4 }
        handoff.opened(innerPager, entry, 0)
        handoff.attach(innerPager, inner)
        // Folding without paging shows Home 1 again (the page the spread opened from).
        assertEquals(1, handoff.entryPage(cover, fallbackAnchor = 0, pageCount = 5, todayOverlay = false))
        // Other pages keep the iPhone Duo rule: the cover page becomes the spread's right half.
        val fresh = PagerPostureHandoff()
        val coverHome3 = androidx.compose.foundation.pager.PagerState(currentPage = 3) { 5 }
        fresh.attach(coverHome3, cover)
        assertEquals(2, fresh.entryPage(inner, fallbackAnchor = 0, pageCount = 4, todayOverlay = true))
    }
}
