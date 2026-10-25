package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test

class LayoutModelTest {
    private val cover = duoHomeFrame(DuoHomePose.COVER_PORTRAIT, 425f, 622f)
    private val inner = duoHomeFrame(DuoHomePose.INNER_LANDSCAPE, 860f, 608f)
    private val coverLandscape = duoHomeFrame(DuoHomePose.COVER_LANDSCAPE, 622f, 425f)
    private val innerPortrait = duoHomeFrame(DuoHomePose.INNER_PORTRAIT, 608f, 860f)
    /** Percent of a panel dimension, for comparing with the BRIEF's measurements. */
    private fun pct(value: Float, of: Float) = value / of * 100f

    @Test fun `cover grid matches the measured Duo cover`() {
        val g = cover.grid
        assertEquals(4, g.columns); assertEquals(6, g.rows)
        assertEquals(12.7f, pct(g.icon, 425f), .05f)            // ≈ 54 dp
        assertEquals(54f, g.icon, .5f)
        assertEquals(18.9f, pct(g.columnPitch, 425f), .05f)     // ≈ 80 dp
        assertEquals(5.9f, pct(g.iconLeft, 425f), .05f)         // grid left edge
        assertEquals(75.2f, pct(g.iconLeft + g.iconGridWidth, 425f), .2f) // grid right edge
        assertEquals(8.2f, pct(g.firstRowTop, 622f), .05f)      // rows anchored at the top
        assertEquals(14.55f, pct(g.rowPitch, 622f), .05f)
        assertEquals(33.7f, pct(g.widgetSide, 425f), .05f)      // small widget ≈ 143 dp
        assertEquals(g.icon, inner.grid.icon, 1f)               // same physical icon on both panels
        assertEquals(425f, g.pageStride, 0f)
    }

    @Test fun `two small widgets fill a row pair with the measured gap`() {
        val g = cover.grid
        val frame = widgetPlacementFrame(0, 2, 2, g.columnPitch, g.icon, 0f, 2f * g.rowPitch, g.widgetSide)
        assertEquals(g.widgetSide, frame.width, .01f)
        assertEquals(g.widgetSide, frame.height, .01f)
        val leftCard = g.cellLeft + frame.insetX
        val rightCard = g.cellLeft + 2f * g.columnPitch + frame.insetX
        assertEquals("gap between the two widgets ≈ 3.9 %W", 3.9f, pct(rightCard - leftCard - frame.width, 425f), .3f)
        // A medium card spans the full row pair: two small cards plus the gap.
        val medium = widgetPlacementFrame(0, 4, 2, g.columnPitch, g.icon, 0f, 2f * g.rowPitch, g.widgetSide)
        assertEquals(2f * frame.width + (rightCard - leftCard - frame.width), medium.width, .01f)
        assertEquals(frame.height, medium.height, .01f)
        // A one-row card is about an icon tall; cards start on the row top like icons.
        val strip = widgetPlacementFrame(0, 4, 1, g.columnPitch, g.icon, 0f, g.rowPitch, g.widgetSide)
        assertEquals(g.icon, strip.height, 3f)
    }

    @Test fun `cover control column matches the measured positions`() {
        val s = cover.status
        assertEquals(89.6f, pct(s.timeCenterX, 425f), .05f)
        assertEquals(12.8f, pct(s.timeCenterY, 622f), .05f)
        assertEquals(17.9f, pct(s.ringCenterY, 622f), .05f)
        assertEquals(8.6f, pct(s.ringDiameter, 425f), .05f)
        assertEquals(15f, s.timeSize, .1f)
        val d = cover.dock
        assertFalse(d.horizontal)
        // Centred on the 89.6 %W column line (Apple's 82.1–97.4 %W measures 0.15 %W to the right).
        assertEquals(82.1f, pct(d.box.left, 425f), .2f)
        assertEquals(97.4f, pct(d.box.right, 425f), .2f)
        // Round 3 (owner): vertically centred on the panel instead of Apple's 55.0 %H (35.7–74.3 %H).
        assertEquals(50.0f, pct(d.box.centerY, 622f), .05f)
        assertEquals(38.6f, pct(d.box.height, 622f), .5f)
        assertEquals(.82f * cover.grid.icon, d.icon, .5f)
        assertEquals(9.46f, pct(d.pitch, 622f), .05f)
        assertEquals("concentric: icon radius + inset", d.icon * IOS_ICON_CORNER_FRACTION + d.padding, d.cornerRadius, .001f)
        assertEquals(.31f, d.cornerRadius / d.box.width, .03f)
        assertTrue("not a capsule", d.cornerRadius < d.box.width / 2f - 5f)
        assertEquals(s.timeCenterX, d.box.centerX, .5f)
        val search = cover.search
        assertEquals(89.6f, pct(search.centerX, 425f), .05f)
        assertEquals(92.9f, pct(search.centerY, 622f), .05f)
        assertEquals(10.3f, pct(search.width, 425f), .05f)
        assertEquals(40.6f, pct(cover.dots.centerX, 425f), .3f)
        assertEquals(96.6f, pct(cover.dots.centerY, 622f), .05f)
        assertEquals(6.3f, cover.dots.dot, .1f)
        assertEquals(16f, cover.dots.pitch, .1f)
    }

    @Test fun `cover elements never collide`() {
        val icons = cover.grid.iconLeft + cover.grid.iconGridWidth
        assertTrue("grid stays left of the Dock column", icons < cover.dock.box.left)
        assertTrue(cover.status.bounds.bottom < cover.dock.box.top)
        assertTrue(cover.dock.box.bottom < cover.search.top)
        assertTrue("six rows and labels end above the page dots", cover.gridBottom < cover.dots.centerY - cover.dots.dot)
        assertTrue(cover.search.bottom <= 622f)
    }

    /**
     * Round 6: Apple's equal 67 dp gaps left the next page under the Dock; the hinge gap is now wide enough
     * for it to rest beyond the screen edge (WorkspacePageMotionTest), the Dock gap is the cover's 29 dp.
     */
    @Test fun `inner spread widens the hinge gap so the next page rests off screen`() {
        val g = inner.grid
        val leftStart = g.iconLeft
        val leftEnd = leftStart + g.iconGridWidth
        val rightStart = leftStart + g.pageStride
        val rightEnd = rightStart + g.iconGridWidth
        val edgeGap = leftStart
        val hingeGap = rightStart - leftEnd
        val dockGap = inner.dock.box.left - rightEnd
        assertEquals(52.6f, edgeGap, 1f)
        assertEquals(120.5f, hingeGap, 1f)
        assertEquals(28.9f, dockGap, 1.5f)
        assertTrue("next page off screen", g.cellLeft + 2f * g.pageStride - PANE_DRAW_MARGIN_DP >= 860f - .01f)
        assertEquals(8.1f, pct(g.firstRowTop, 608f), .05f)
        assertEquals(14.6f, pct(g.rowPitch, 608f), .05f)
        assertEquals(6, g.rows)
        assertEquals(6.24f, pct(g.icon, 860f), .05f)
    }

    @Test fun `inner control column and dots match the measured positions`() {
        assertEquals(94.9f, pct(inner.status.timeCenterX, 860f), .05f)
        assertEquals(6.6f, pct(inner.status.timeCenterY, 608f), .05f)
        assertEquals(12.3f, pct(inner.status.ringCenterY, 608f), .05f)
        assertEquals(cover.status.ringDiameter, inner.status.ringDiameter, .5f)
        assertEquals(91.3f, pct(inner.dock.box.left, 860f), .1f)
        assertEquals(98.5f, pct(inner.dock.box.right, 860f), .1f)
        assertEquals("round 3: centred (Apple 55.1 %H)", 50.0f, pct(inner.dock.box.centerY, 608f), .05f)
        assertEquals(94.9f, pct(inner.search.centerX, 860f), .05f)
        assertEquals(92.7f, pct(inner.search.centerY, 608f), .05f)
        assertEquals(cover.search.width, inner.search.width, .5f)
        // Centred under BOTH grids (Apple 45.5 %W with its equal gaps; round 6 widened the hinge gap).
        val g = inner.grid
        assertEquals("dots centred under BOTH grids", (g.iconLeft + g.iconLeft + g.pageStride + g.iconGridWidth) / 2f,
            inner.dots.centerX, .5f)
        assertEquals(94.6f, pct(inner.dots.centerY, 608f), .05f)
        assertEquals(cover.dots.dot, inner.dots.dot, .2f)
        assertTrue(inner.gridBottom < inner.dots.centerY - inner.dots.dot)
        assertTrue(inner.status.bounds.bottom < inner.dock.box.top && inner.dock.box.bottom < inner.search.top)
    }

    @Test fun `both unfolded halves share one row system with the Today page`() {
        val geometry = inner.toHomeGeometry()
        val today = widgetPlacementFrame(-1, 2, 2, geometry.gridWidth / 4f, geometry.iconSize, 0f,
            2f * geometry.rowHeight, geometry.referenceCardSize)
        val home = widgetPlacementFrame(0, 2, 2, geometry.gridWidth / 4f, geometry.iconSize, 0f,
            2f * geometry.rowHeight, geometry.referenceCardSize)
        assertEquals("Today page cards align with the right half's rows", home, today)
        assertEquals(geometry.rowHeight, geometry.topRowHeight, 0f)
        assertEquals(2f * geometry.rowHeight, geometry.widgetHeight + 18f, .001f)
    }

    @Test fun `cover landscape keeps the vertical column and shows 24 cells as six by four`() {
        val g = coverLandscape.grid
        assertEquals(6, g.columns); assertEquals(4, g.rows)
        assertEquals(HOME_CELLS, g.columns * g.rows)
        assertTrue(coverLandscape.pose.sideColumn)
        assertFalse(coverLandscape.dock.horizontal)
        assertTrue(coverLandscape.dock.box.height > coverLandscape.dock.box.width)
        assertEquals(cover.grid.icon, g.icon, .01f)
        // Round 4: the 425 dp tall column cannot hold Apple's 242 dp Dock centred below the status block,
        // so the same Dock is fitted (uniformly scaled, ≥ 0.85×) and centred (DockFitTest).
        assertEquals(cover.dock.box.width / cover.dock.icon, coverLandscape.dock.box.width / coverLandscape.dock.icon, .001f)
        assertEquals(425f / 2f, coverLandscape.dock.box.centerY, .01f)
        assertEquals(622f - 44.2f, coverLandscape.status.timeCenterX, .5f)
        assertTrue(g.iconLeft + g.iconGridWidth < coverLandscape.dock.box.left - 16f)
        assertTrue(g.iconLeft >= 16f)
        assertTrue(coverLandscape.gridBottom < coverLandscape.dots.centerY - coverLandscape.dots.dot)
        assertTrue(coverLandscape.status.bounds.bottom < coverLandscape.dock.box.top)
        assertTrue(coverLandscape.dock.box.bottom < coverLandscape.search.top)
        assertTrue(coverLandscape.search.bottom <= 425f)
        assertTrue(desktopSideDock(cover = true, landscape = true))
    }

    @Test fun `inner portrait is Apple's centred layout from the rectified photo 6`() {
        val g = innerPortrait.grid
        val w = 608f; val h = 860f
        assertFalse(innerPortrait.pose.sideColumn)
        assertEquals(4, g.columns); assertEquals(6, g.rows)
        assertEquals(w, g.pageStride, 0f)
        // Same physical icon as every other pose (54 dp); wide iPad-like gaps, centred.
        assertEquals(inner.grid.icon, g.icon, .01f)
        assertEquals(96.5f, g.columnPitch, .1f)                       // 15.87 %W
        assertEquals("grid centred", w / 2f, g.iconLeft + g.iconGridWidth / 2f, .01f)
        assertEquals(159.5f, g.iconLeft + g.icon / 2f, .5f)            // first column centre (photo: 159.5 dp)
        assertEquals(449f, g.iconLeft + 3f * g.columnPitch + g.icon / 2f, .5f)
        assertEquals(73f, g.firstRowTop, .1f)                          // 8.49 %H
        assertEquals(106.75f, g.rowPitch, .1f)                         // 12.41 %H
        assertEquals("physical widget card of the inner panel", inner.grid.widgetSide, g.widgetSide, .01f)
        // Status: time over ring in the top-right corner, 43.5 dp from the edge like the landscape column.
        val st = innerPortrait.status
        assertFalse(st.horizontal)
        assertEquals(st.timeCenterX, st.ringCenterX, 0f)
        assertEquals(564.5f, st.timeCenterX, .2f)
        assertEquals(860f - inner.status.timeCenterX, w - st.timeCenterX, 1f)
        assertEquals(37f, st.timeCenterY, .1f); assertEquals(70f, st.ringCenterY, .1f)
        assertEquals(inner.status.ringDiameter, st.ringDiameter, .01f)
        assertTrue("status beside the grid", st.bounds.left > g.iconLeft + g.iconGridWidth)
        // Dock: compact floating glass hugging four 54 dp icons at the bottom centre, 70 dp pitch.
        val dock = innerPortrait.dock
        assertTrue(dock.horizontal)
        assertEquals(w / 2f, dock.box.centerX, .01f)
        assertEquals(g.icon, dock.icon, .01f)
        assertEquals(70f, dock.pitch, .1f)
        assertEquals(FLOATING_DOCK_INSET * dock.icon, dock.padding, .01f)
        assertEquals(.236f, FLOATING_DOCK_INSET, .002f)
        assertEquals(3f * dock.pitch + dock.icon + 2f * dock.padding, dock.box.width, .01f)
        assertEquals(dock.icon + 2f * dock.padding, dock.box.height, .01f)
        assertEquals("glass 159–448 dp in the photo", 288.5f, dock.box.width, 2f)
        assertEquals("8.5 dp above the panel edge", h - 8.5f, dock.box.bottom, .1f)
        assertEquals(dock.box.centerX, dock.contentCenterX, .001f)
        assertEquals("concentric", dock.icon * IOS_ICON_CORNER_FRACTION + dock.padding, dock.cornerRadius, .001f)
        assertTrue(dock.cornerRadius < dock.box.height / 2f - 10f)
        // Search: bottom right, on the status column, level with the Dock.
        assertEquals(st.timeCenterX, innerPortrait.search.centerX, .01f)
        assertEquals(dock.box.centerY, innerPortrait.search.centerY, .01f)
        assertEquals(inner.search.width, innerPortrait.search.width, .01f)
        assertTrue(innerPortrait.search.left > dock.box.right + 60f)
        // Page dots centred above the Dock, below the sixth row.
        assertEquals(w / 2f, innerPortrait.dots.centerX, .01f)
        assertEquals(716f, innerPortrait.dots.centerY, .2f)
        assertTrue(innerPortrait.gridBottom + 20f < innerPortrait.dots.centerY)
        assertTrue(innerPortrait.dots.centerY + 40f < dock.box.top)
        assertFalse(desktopSideDock(cover = false, landscape = false))
        assertTrue(desktopSideDock(cover = false, landscape = true))
        assertTrue(desktopSideDock(cover = true, landscape = false))
    }

    @Test fun `inner portrait stays inside a narrower or shorter window`() {
        for ((w, h) in listOf(500f to 860f, 608f to 700f, 700f to 1000f)) {
            val f = duoHomeFrame(DuoHomePose.INNER_PORTRAIT, w, h)
            assertTrue("$w×$h grid inside", f.grid.cellLeft >= 15.9f && f.grid.cellLeft + f.grid.cellGridWidth <= w - 15.9f)
            assertTrue("$w×$h rows above the dots", f.gridBottom < f.dots.centerY - f.dots.dot / 2f)
            assertTrue("$w×$h dock inside", f.dock.box.left >= 0f && f.dock.box.right <= w)
            assertEquals(w / 2f, f.grid.cellLeft + f.grid.cellGridWidth / 2f, .01f)
        }
    }

    @Test fun `recent apps extend the Dock below its four apps`() {
        val plain = cover.dock
        val withRecent = duoHomeFrame(DuoHomePose.COVER_PORTRAIT, 425f, 622f, dockExtraSlots = 2).dock
        assertEquals(6, withRecent.slots)
        assertTrue(withRecent.box.top >= cover.status.bounds.bottom + 16f - .01f)
        assertTrue(withRecent.box.bottom <= cover.search.top - 16f + .01f)
        // Round 4: the whole group stays centred; like the iPad Dock it shrinks a little to fit (≈0.89×)
        // instead of being pushed 20 dp below the centre.
        assertEquals(311f, withRecent.box.centerY, .01f)
        val s = withRecent.icon / plain.icon
        assertTrue("scaled $s", s >= DOCK_MIN_FIT_SCALE && s < 1f)
        assertEquals(s * (plain.box.height - 2f * plain.padding) + 2f * plain.pitch * s + 2f * plain.padding * s + 13f,
            withRecent.box.height, .01f)
        val innerRecent = duoHomeFrame(DuoHomePose.INNER_LANDSCAPE, 860f, 608f, dockExtraSlots = 2).dock
        assertEquals(304f, innerRecent.box.centerY, 25f)
        assertTrue(innerRecent.box.top >= inner.status.bounds.bottom + 16f - .01f)
        assertTrue(innerRecent.box.bottom <= inner.search.top - 16f + .01f)
        val portraitRecent = duoHomeFrame(DuoHomePose.INNER_PORTRAIT, 608f, 860f, dockExtraSlots = 2).dock
        assertEquals(5f * portraitRecent.pitch + portraitRecent.icon + 2f * portraitRecent.padding + 13f,
            portraitRecent.box.width, .01f)
        assertEquals(304f, portraitRecent.box.centerX, .01f)
    }

    @Test fun `legacy Dock preferences scale or move the Dock but defaults are Apple's`() {
        assertSame(cover, cover.withDockPreference(LayoutPreset()))
        val wide = cover.withDockPreference(LayoutPreset(dockWidth = 84f)).dock
        assertEquals(cover.dock.box.width * 84f / 68f, wide.box.width, .01f)
        assertEquals(cover.dock.icon * 84f / 68f, wide.icon, .01f)
        assertEquals(cover.dock.box.centerX, wide.box.centerX, .001f)
        assertEquals(wide.icon * IOS_ICON_CORNER_FRACTION + wide.padding, wide.cornerRadius, .001f)
        val manual = cover.withDockPreference(LayoutPreset(dockAlignToGrid = false, dockPosition = .5f)).dock
        assertEquals(311f, manual.box.centerY, .01f)
        val clamped = cover.withDockPreference(LayoutPreset(dockAlignToGrid = false, dockPosition = .25f)).dock
        assertTrue(clamped.box.top >= cover.status.bounds.bottom)
        assertSame(innerPortrait, innerPortrait.withDockPreference(LayoutPreset(dockWidth = 84f)))
    }

    @Test fun `home geometry adapter keeps Apple sizes and only scales artwork by preference`() {
        val g = cover.toHomeGeometry()
        assertEquals(cover.grid.icon, g.iconSize, .001f)
        assertEquals(cover.grid.cellGridWidth, g.gridWidth, .001f)
        assertEquals(cover.grid.rowPitch, g.rowHeight, .001f)
        assertEquals(cover.grid.firstRowTop, g.contentTop, .001f)
        assertEquals(cover.grid.widgetSide, g.referenceCardSize!!, .001f)
        assertEquals(0f, g.pageGutter, 0f)
        val smaller = cover.toHomeGeometry(LayoutPreset(iconSize = 51f))
        assertEquals(cover.grid.icon * .75f, smaller.iconSize, .01f)
        assertEquals(g.gridWidth, smaller.gridWidth, 0f)
        assertEquals(g.rowHeight, smaller.rowHeight, 0f)
        val roomy = cover.toHomeGeometry(LayoutPreset(rowGap = 28f))
        assertTrue(roomy.rowHeight > g.rowHeight)
        assertTrue("six rows still end above the dots",
            roomy.contentTop + 5f * roomy.rowHeight + roomy.iconSize + roomy.labelHeight < cover.dots.centerY - cover.dots.dot / 2f)
        val bigText = duoHomeFrame(DuoHomePose.COVER_PORTRAIT, 425f, 622f, duoLabelHeight(2f)).toHomeGeometry()
        assertTrue("large text tightens rows instead of clipping the last label",
            bigText.contentTop + 5f * bigText.rowHeight + bigText.iconSize + bigText.labelHeight < cover.dots.centerY)
        assertEquals("Apple's pitch is kept at default text size", inner.grid.rowPitch, inner.toHomeGeometry().rowHeight, .001f)
        assertEquals(0f, cover.toHomeGeometry(labels = false).labelHeight, 0f)
    }

    @Test fun `legacy homeGeometry entry point uses the same frame`() {
        assertEquals(cover.toHomeGeometry(), homeGeometry(425f, 622f, LayoutPreset(), true))
        assertEquals(inner.toHomeGeometry(), homeGeometry(860f, 608f, LayoutPreset(), true))
        assertTrue(homeGeometry(860f, 608f, LayoutPreset(), true).expanded)
        assertFalse(homeGeometry(608f, 860f, LayoutPreset(), true, expanded = false).expanded)
        assertEquals(homeGeometry(425f, 608f, LayoutPreset(), true),
            homeGeometry(Float.NaN, Float.POSITIVE_INFINITY, LayoutPreset(iconSize = Float.NaN), true))
    }

    @Test fun `fractions adapt to other panel sizes`() {
        val big = duoHomeFrame(DuoHomePose.COVER_PORTRAIT, 475f, 700f)
        assertTrue(big.grid.icon > cover.grid.icon)
        assertEquals(cover.grid.firstRowTop / 622f, big.grid.firstRowTop / 700f, .0001f)
        for (frame in listOf(cover, inner, coverLandscape, innerPortrait, big)) {
            assertTrue(frame.dock.box.left >= 0f && frame.dock.box.right <= frame.width + .01f)
            assertTrue(frame.search.right <= frame.width + .01f && frame.search.bottom <= frame.height + .01f)
            assertTrue(frame.homeBottomSpace() >= 0f)
            // The pane always holds every row: content top + rows + 8 dp padding fit above the bottom space.
            val g = frame.toHomeGeometry()
            assertTrue(g.contentTop + (frame.grid.rows - 1) * g.rowHeight + g.iconSize + g.labelHeight + 8f <=
                frame.height - frame.homeBottomSpace(g) + .01f)
        }
    }

    @Test fun `leading feed reaches bottom but normal home retains controls clearance`() {
        assertEquals(608f, homePaneViewportHeight(-1, 608f, 44f), .001f)
        assertEquals(564f, homePaneViewportHeight(0, 608f, 44f), .001f)
        assertEquals(0f, homePaneViewportHeight(0, 30f, 44f), .001f)
    }
    @Test fun `invalid icon preferences fall back to current APK defaults`() {
        assertEquals(68f, LayoutPreset(iconSize = Float.NaN).sanitized().iconSize, 0f)
        assertEquals(48f, LayoutPreset(iconSize = 40f).sanitized().iconSize, 0f)
    }
    @Test fun `reconciliation preserves custom order while handling installs removals duplicates`() {
        assertEquals(listOf("c", "a", "d"), reconcileOrder(listOf("c", "gone", "a", "c"), listOf("a", "c", "d")))
        assertEquals(emptyList<String>(), reconcileOrder(listOf("a"), emptyList()))
    }
    @Test fun `reordering across page boundary does not drop apps`() {
        val apps = (0..31).map { "app$it" }
        val moved = moveApp(apps, "app16", -1)
        assertEquals("app16", moved[15])
        assertEquals("app15", moved[16])
        assertEquals(apps.toSet(), moved.toSet())
        assertEquals("app31", moveApp(apps, "app31", -100).first())
        assertEquals(apps, moveApp(apps, "missing", 1))
    }
    @Test fun `out of range preferences are constrained before layout`() {
        val p = LayoutPreset(999f, -40f, 2f, 12f).sanitized()
        assertEquals(68f, p.iconSize); assertEquals(0f, p.rowGap)
        assertEquals(56f, p.dockWidth); assertEquals(.75f, p.dockPosition)
    }
    @Test fun `new installs and refresh do not pin apps and empty home stays empty`() {
        assertEquals(listOf("c", "a"), reconcilePins(listOf("c", "gone", "a", "c"), listOf("a", "c", "new")))
        assertEquals(emptyList<String>(), reconcilePins(emptyList(), listOf("new")))
    }
    @Test fun `migration uses suggestions for auto sorted legacy home but retains manual first page`() {
        val installed = (0..31).map { "app%02d".format(it) }
        assertEquals(listOf("app20", "app10"), migrateHomePins(installed, installed, listOf("app20", "app10", "gone")))
        val custom = listOf("app31") + installed.dropLast(1)
        assertEquals(custom.take(16), migrateHomePins(custom, installed, listOf("app20")))
    }
    @Test fun `home always has a page independently of the library`() {
        assertEquals(1, homePageCount(0)); assertEquals(1, homePageCount(HOME_CELLS)); assertEquals(2, homePageCount(HOME_CELLS + 1))
    }
}
