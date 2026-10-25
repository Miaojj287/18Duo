package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test

class EditGeometryTest {
    // Xiaomi 18 Fold cover 425×622 dp with Duo fractions (BRIEF): grid 5.9–75.2 %W, first row 8.2 %H.
    private val cover = MenuBounds(0f, 0f, 425f, 622f)
    private val coverGrid = MenuBounds(25f, 51f, 320f, 600f)
    private val coverCamera = MenuBounds(202f, 0f, 222f, 46f)
    // Right column: time centre 12.8 %H, ring centre 17.9 %H, Ø 36.5 dp, centred on 89.6 %W.
    private val coverStatus = MenuBounds(349f, 70f, 412f, 130f)
    private val coverDock = MenuBounds(349f, 222f, 414f, 462f)

    @Test fun `cover edit and done capsules avoid the status column the camera the dock and the first row`() {
        val layout = jiggleChromeLayout(cover, coverGrid, 62f, 66f, listOf(coverCamera))
        listOf(layout.edit, layout.done).forEach { pill ->
            assertFalse(pill.intersects(coverStatus))
            assertFalse(pill.intersects(coverCamera))
            assertFalse(pill.intersects(coverDock))
            assertTrue("above the first row's badges", pill.bottom <= coverGrid.top - 7f + .01f)
            assertTrue(pill.top >= 8f)
        }
        assertEquals(coverGrid.left, layout.edit.left, 0f)
        assertEquals(coverGrid.right, layout.done.right, 0f)
        assertEquals(34f, layout.edit.height, .01f)
    }

    @Test fun `inner capsules stay inside the two grids, left of the top-right camera and status`() {
        val inner = MenuBounds(0f, 0f, 860f, 608f)
        val grid = MenuBounds(67f, 49f, 717f, 590f)
        val camera = MenuBounds(809f, 0f, 860f, 60f)
        val layout = jiggleChromeLayout(inner, grid, 62f, 66f, listOf(camera))
        assertEquals(67f, layout.edit.left, 0f)
        assertEquals(717f, layout.done.right, 0f)
        assertFalse(layout.done.intersects(camera))
        assertTrue(layout.done.bottom <= 49f - 7f + .01f)
    }

    @Test fun `a cutout that intersects a capsule pushes it sideways by the minimum`() {
        val camera = MenuBounds(20f, 0f, 60f, 40f)
        val layout = jiggleChromeLayout(cover, coverGrid, 62f, 66f, listOf(camera))
        assertEquals(60f + 6f, layout.edit.left, 0f)
        assertFalse(layout.edit.intersects(camera))
        assertEquals(coverGrid.right, layout.done.right, 0f)
    }

    @Test fun `a short band shrinks the capsules to their minimum and pins them to the top margin`() {
        val layout = jiggleChromeLayout(cover, coverGrid.copy(top = 30f), 62f, 66f)
        assertEquals(28f, layout.edit.height, .01f)
        assertEquals(8f, layout.edit.top, 0f)
    }

    @Test fun `folder icon shows a symmetric three by three mini grid of the first nine apps`() {
        val slots = folderPreviewSlots(54f, 12)
        assertEquals(9, slots.size)
        assertEquals(54f * FOLDER_PREVIEW_MARGIN, slots.first().x, 1e-4f)
        assertEquals(54f - 54f * FOLDER_PREVIEW_MARGIN, slots.last().x + slots.last().size, 1e-3f)
        assertEquals(slots[1].x - slots[0].x, slots[2].x - slots[1].x, 1e-4f)
        assertEquals(slots[3].y - slots[0].y, slots[1].x - slots[0].x, 1e-4f)
        assertEquals(2, folderPreviewSlots(54f, 2).size)
        assertTrue(folderPreviewSlots(54f, 0).isEmpty())
    }

    @Test fun `open folder zooms out of its icon and back`() {
        val icon = MenuBounds(260f, 300f, 314f, 354f)
        val panel = MenuBounds(42f, 190f, 382f, 530f)
        val start = folderZoomTransform(icon, panel, 0f)
        assertEquals(54f / 340f, start.scaleX, 1e-4f)
        assertEquals(icon.centerX - panel.centerX, start.translationX, 1e-3f)
        assertEquals(icon.centerY - panel.centerY, start.translationY, 1e-3f)
        val end = folderZoomTransform(icon, panel, 1f)
        assertEquals(ZoomTransform(1f, 1f, 0f, 0f), end)
        assertEquals(folderPanelSide(425f, 622f), 340f, 0f)
        assertEquals(folderPanelSide(860f, 608f), 340f, 0f)
        assertTrue(folderPanelSide(622f, 425f) <= 425f - 132f)
    }

    @Test fun `resting on the middle of another app offers a folder, elsewhere reflows`() {
        val icon = MenuBounds(100f, 100f, 154f, 154f)
        assertEquals(DragHoverKind.FOLDER, dragHoverKind(127f, 127f, icon, true))
        assertEquals(DragHoverKind.FOLDER, dragHoverKind(140f, 135f, icon, true))
        assertEquals(DragHoverKind.INSERT, dragHoverKind(102f, 102f, icon, true))
        assertEquals(DragHoverKind.INSERT, dragHoverKind(127f, 170f, icon, true))
        assertEquals(DragHoverKind.INSERT, dragHoverKind(127f, 127f, icon, false))
        assertEquals(DragHoverKind.INSERT, dragHoverKind(127f, 127f, null, true))
        assertTrue(DRAG_FOLDER_DWELL_MS > DRAG_REFLOW_DWELL_MS)
    }

    @Test fun `the folder partner is the icon the dragged icon covers, nearest first`() {
        val calendar = MenuBounds(100f, 100f, 154f, 154f)
        val contacts = MenuBounds(180f, 100f, 234f, 154f)
        val icons = mapOf("calendar" to calendar, "contacts" to contacts)
        assertEquals("calendar", folderPartnerAt(127f, 127f, icons))
        // Off-centre but over the icon (round 16: an icon grabbed by its corner still makes a folder).
        assertEquals("calendar", folderPartnerAt(145f, 140f, icons))
        assertEquals("contacts", folderPartnerAt(200f, 120f, icons))
        // The gutter between two icons and the label below are no folder.
        assertNull(folderPartnerAt(167f, 127f, icons))
        assertNull(folderPartnerAt(127f, 175f, icons))
        assertNull(folderPartnerAt(127f, 127f, emptyMap()))
    }

    @Test fun `renaming lifts the folder only when the keyboard would cover the name field`() {
        // Cover: field ends at 400 px, keyboard top at 950 px: the folder stays where it is.
        assertEquals(0f, folderRenameLift(fieldBottom = 400f, keyboardTop = 950f, clearance = 33f), 0f)
        // No keyboard yet.
        assertEquals(0f, folderRenameLift(fieldBottom = 400f, keyboardTop = 0f, clearance = 33f), 0f)
        // A field the keyboard would hide rises just enough to clear it.
        assertEquals(83f, folderRenameLift(fieldBottom = 1000f, keyboardTop = 950f, clearance = 33f), 1e-3f)
    }

    @Test fun `the grid reflows only once the dragged icon rests`() {
        assertTrue(dragRested(100f, 100f, 106f, 104f, slopPx = 12f))
        assertFalse(dragRested(100f, 100f, 130f, 100f, slopPx = 12f))
    }

    @Test fun `page turns after a half second at the edge, then more slowly`() {
        assertEquals(EDGE_TURN_FIRST_MS, edgeTurnDelayMs(0))
        assertEquals(EDGE_TURN_REPEAT_MS, edgeTurnDelayMs(1))
        assertEquals(EDGE_TURN_REPEAT_MS, edgeTurnDelayMs(5))
        assertTrue(EDGE_TURN_FIRST_MS in 400L..700L)
    }

    @Test fun `lifted icon keeps the grabbed point under the finger`() {
        val cell = MenuBounds(25f, 237f, 99f, 327f)
        val icon = iconRectInCell(cell, 54f, topAligned = true)
        assertEquals(MenuBounds(35f, 237f, 89f, 291f), icon)
        assertEquals(MenuBounds(35f, 255f, 89f, 309f), iconRectInCell(cell, 54f, topAligned = false))
        val grabX = 60f - icon.left; val grabY = 250f - icon.top
        assertEquals(icon, dragGhostBounds(60f, 250f, grabX, grabY, 54f, 54f))
        val moved = dragGhostBounds(160f, 300f, grabX, grabY, 54f, 54f)
        assertEquals(160f - grabX, moved.left, 0f)
        assertEquals(300f - grabY, moved.top, 0f)
        assertEquals(MenuBounds(5f, 5f, 15f, 15f), lerpBounds(MenuBounds(0f, 0f, 10f, 10f), MenuBounds(10f, 10f, 20f, 20f), .5f))
    }

    @Test fun `context menu height follows rows, hairlines and section gaps`() {
        val row = IosMenuAction("a", null, "t") {}
        val height = contextMenuHeightPx(listOf(listOf(row, row), listOf(row)), density = 1f)
        assertEquals(3 * CONTEXT_MENU_ROW_HEIGHT.value + CONTEXT_MENU_HAIRLINE.value + CONTEXT_MENU_SECTION_GAP.value, height, 1e-3f)
        assertEquals(0f, contextMenuHeightPx(emptyList(), 2f), 0f)
    }

    // ---- Fix wave: jiggle band in every pose (review P0-1 / P1-1) -------------------------------

    private fun DuoBox.bounds() = MenuBounds(left, top, right, bottom)

    /** The pills of [frame] for a 62/66 dp Edit/Done pair over its real grid extent. */
    private fun pills(frame: DuoHomeFrame, band: JiggleBand = frame.jiggleBand()): JiggleChromeLayout {
        val g = frame.grid
        val right = g.cellLeft + (if (frame.pose.spread) g.pageStride else 0f) + g.cellGridWidth
        val grid = MenuBounds(g.cellLeft, g.firstRowTop + band.gridShift, right, frame.gridBottom + band.gridShift)
        return jiggleChromeLayout(MenuBounds(0f, 0f, frame.width, frame.height), grid, 62f, 66f, band = band)
    }

    @Test fun `inner portrait jiggle keeps the corner status and Edit Done stay over the centred grid`() {
        // Round 8 (photo 6): the open-portrait status is the Duo's time+ring block in the top-right corner,
        // beside the grid, so like every other pose it stays visible while jiggling.
        val frame = duoHomeFrame(DuoHomePose.INNER_PORTRAIT, 608f, 860f)
        assertFalse(jiggleHidesStatus(frame.pose, frame.status.horizontal))
        val band = frame.jiggleBand()
        assertEquals(0f, band.gridShift, 0f)
        assertEquals(34f, band.height, .01f)
        val layout = pills(frame, band)
        listOf(layout.edit, layout.done).forEach { pill ->
            assertTrue("clear of the first row's badges", pill.bottom <= frame.grid.firstRowTop - JIGGLE_BADGE_REACH_DP + .01f)
            assertTrue(pill.top >= 8f)
            assertFalse(pill.intersects(frame.status.timeBox.bounds()))
            assertFalse(pill.intersects(frame.status.ringBox.bounds()))
        }
        // A horizontal status bar (iPhone) would still be hidden.
        assertTrue(jiggleHidesStatus(frame.pose, horizontalStatus = true))
    }

    @Test fun `a status bar that stays visible pushes the band below it and the grid down, never under it`() {
        val frame = duoHomeFrame(DuoHomePose.INNER_PORTRAIT, 608f, 860f)
        // An iPhone-style bar across the top of the grid.
        val status = listOf(MenuBounds(frame.grid.cellLeft + 20f, 18f, frame.grid.cellLeft + 80f, 40f),
            MenuBounds(frame.grid.cellLeft + frame.grid.cellGridWidth - 70f, 14f, frame.grid.cellLeft + frame.grid.cellGridWidth - 20f, 52f))
        val band = jiggleBand(0f, frame.grid.firstRowTop, frame.grid.cellLeft, frame.grid.cellLeft + frame.grid.cellGridWidth,
            ceilings = status, maxGridShift = 60f)
        assertTrue(band.top >= status.maxOf { it.bottom } + 6f - .01f)
        assertTrue(band.gridShift > 0f)
        val grid = MenuBounds(frame.grid.cellLeft, frame.grid.firstRowTop + band.gridShift,
            frame.grid.cellLeft + frame.grid.cellGridWidth, 800f)
        val layout = jiggleChromeLayout(MenuBounds(0f, 0f, 608f, 860f), grid, 62f, 66f, band = band)
        listOf(layout.edit, layout.done).forEach { pill ->
            status.forEach { assertFalse("pill covers the status bar", pill.intersects(it)) }
            assertTrue(pill.bottom <= grid.top - JIGGLE_BADGE_REACH_DP + .01f)
        }
    }

    @Test fun `rotated cover moves the pages down just enough that Edit never covers a badge`() {
        val frame = duoHomeFrame(DuoHomePose.COVER_LANDSCAPE, 622f, 425f)
        val geometry = frame.toHomeGeometry()
        val band = frame.jiggleBand(geometry)
        // First row at 7.5 %H (32 dp): 8 + 28 + 8.5 does not fit above it without a shift.
        assertTrue(band.gridShift > 0f)
        assertTrue(band.height >= 28f)
        val layout = pills(frame, band)
        listOf(layout.edit, layout.done).forEach { pill ->
            assertTrue(pill.bottom <= frame.grid.firstRowTop + band.gridShift - JIGGLE_BADGE_REACH_DP + .01f)
            assertFalse(pill.intersects(frame.status.timeBox.bounds()))
            assertFalse(pill.intersects(frame.dock.box.bounds()))
        }
        // The moved last row still ends above the jiggle-mode page-dot pill.
        val lastLabel = geometry.contentTop + (frame.grid.rows - 1) * geometry.rowHeight + geometry.iconSize + geometry.labelHeight
        assertTrue(lastLabel + band.gridShift <= frame.dots.centerY - frame.dots.dot / 2f - 8f)
    }

    @Test fun `cover portrait and unfolded jiggle keep the grid still and the pills off the status column`() {
        listOf(duoHomeFrame(DuoHomePose.COVER_PORTRAIT, 425f, 622f), duoHomeFrame(DuoHomePose.INNER_LANDSCAPE, 860f, 608f))
            .forEach { frame ->
                val band = frame.jiggleBand()
                assertEquals(frame.pose.name, 0f, band.gridShift, 0f)
                assertTrue(band.height >= 30f)
                assertFalse(jiggleHidesStatus(frame.pose))
                val layout = pills(frame, band)
                listOf(layout.edit, layout.done).forEach { pill ->
                    assertFalse(pill.intersects(frame.status.timeBox.bounds()))
                    assertFalse(pill.intersects(frame.status.ringBox.bounds()))
                    assertTrue(pill.bottom <= frame.grid.firstRowTop - JIGGLE_BADGE_REACH_DP + .01f)
                }
            }
    }

    @Test fun `a band with no room left and no shift allowed shrinks to the minimum at the top margin`() {
        val band = jiggleBand(0f, 30f, 0f, 400f)
        assertEquals(0f, band.gridShift, 0f)
        assertEquals(28f, band.height, 0f)
        assertEquals(8f, band.top, 0f)
        // A ceiling outside the grid's columns (the Duo status column) does not lower the band.
        val beside = jiggleBand(0f, 60f, 0f, 320f, ceilings = listOf(MenuBounds(350f, 20f, 410f, 120f)))
        assertEquals(jiggleBand(0f, 60f, 0f, 320f), beside)
    }
    @Test fun `only the icon and its label grab an app, not the gutter of its cell`() {
        // A 90 dp cell with a 60 dp icon centred at its top and a 16 dp label beneath.
        val cell = MenuBounds(0f, 0f, 90f, 90f)
        val icon = iconRectInCell(cell, 60f, topAligned = true)
        val grab = iconGrabArea(icon, labelHeight = 16f, slop = 4f)
        assertTrue(grab.contains(45f, 30f))
        assertTrue(grab.contains(45f, 72f)) // on the label
        assertTrue(grab.contains(12f, 30f)) // within the slop beside the artwork
        assertFalse(grab.contains(5f, 30f)) // the gutter between two icons
        assertFalse(grab.contains(45f, 85f)) // below the label
    }
}
