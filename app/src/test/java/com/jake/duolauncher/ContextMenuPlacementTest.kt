package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test

class ContextMenuPlacementTest {
    private val screen = MenuBounds(0f, 0f, 425f, 622f)

    @Test fun `menu drops below an icon near the top aligned with its leading edge`() {
        val anchor = MenuBounds(40f, 80f, 120f, 180f)
        val placement = contextMenuPlacement(anchor, screen, 250f, 220f)
        assertTrue(placement.below)
        assertFalse(placement.reversed)
        assertEquals(190f, placement.y, 0f)
        assertEquals(40f, placement.x, 0f)
        assertEquals(0f, placement.originY, 0f)
        assertEquals((80f - 40f) / 250f, placement.originX, 1e-4f)
        // A leading-half icon whose leading edge would push the menu off-screen is clamped.
        val centred = contextMenuPlacement(MenuBounds(170f, 80f, 250f, 180f), screen, 250f, 220f)
        assertEquals(425f - 16f - 250f, centred.x, 0f)
    }

    @Test fun `menu rises above an icon near the bottom aligned with its trailing edge and reverses`() {
        val anchor = MenuBounds(300f, 480f, 380f, 580f)
        val placement = contextMenuPlacement(anchor, screen, 250f, 220f)
        assertFalse(placement.below)
        assertTrue(placement.reversed)
        assertEquals(480f - 10f - 220f, placement.y, 0f)
        assertEquals(380f - 250f, placement.x, 0f)
        assertEquals(1f, placement.originY, 0f)
    }

    @Test fun `a menu taller than either side is clamped inside the screen`() {
        val anchor = MenuBounds(100f, 280f, 180f, 380f)
        val placement = contextMenuPlacement(anchor, screen, 250f, 560f)
        assertTrue(placement.y >= 16f)
        assertTrue(placement.y + 560f <= 622f - 16f + .001f)
        val wide = contextMenuPlacement(anchor, MenuBounds(0f, 0f, 200f, 300f), 250f, 100f)
        assertEquals(16f, wide.x, 0f)
    }

    @Test fun `screen origin offsets are respected for panes that do not start at zero`() {
        val pane = MenuBounds(430f, 0f, 860f, 608f)
        val placement = contextMenuPlacement(MenuBounds(440f, 40f, 500f, 100f), pane, 250f, 200f)
        assertEquals(446f, placement.x, 0f)
        assertEquals(110f, placement.y, 0f)
    }

    @Test fun `a camera cutout in the way moves the menu to the other side`() {
        val anchor = MenuBounds(180f, 300f, 240f, 360f)
        val camera = MenuBounds(200f, 372f, 230f, 400f)
        val placement = contextMenuPlacement(anchor, screen, 250f, 200f, obstacles = listOf(camera))
        assertFalse(placement.below)
        assertEquals(300f - 10f - 200f, placement.y, 0f)
        assertFalse(MenuBounds(placement.x, placement.y, placement.x + 250f, placement.y + 200f).intersects(camera))
    }

    @Test fun `a cutout beside the menu is passed sideways when no other side fits`() {
        // Inner panel, top-right camera; an icon at the top whose menu must open below.
        val inner = MenuBounds(0f, 0f, 860f, 608f)
        val camera = MenuBounds(809f, 0f, 860f, 60f)
        val anchor = MenuBounds(720f, 8f, 774f, 62f)
        val placement = contextMenuPlacement(anchor, inner, 250f, 300f, obstacles = listOf(camera))
        assertTrue(placement.below)
        assertFalse(MenuBounds(placement.x, placement.y, placement.x + 250f, placement.y + 300f).intersects(camera.inflate(10f)))
    }

    // ---- Fix wave: the menu never covers the lifted item (review P1-9) -------------------------

    @Test fun `rotated cover widget whose menu fits neither above nor below opens beside it`() {
        val landscape = MenuBounds(0f, 0f, 622f, 425f)
        val widget = MenuBounds(358f, 120f, 508f, 264f)
        val placement = contextMenuPlacement(widget, landscape, 250f, 198f, gap = 12f, margin = 12f)
        assertTrue(placement.beside)
        assertFalse(MenuBounds(placement.x, placement.y, placement.x + 250f, placement.y + 198f).intersects(widget))
        assertEquals(widget.left - 12f - 250f, placement.x, .01f)
        assertEquals(widget.top, placement.y, .01f)
        assertEquals(1f, placement.originX, 0f)
        assertEquals(1f, placement.previewScale, 0f)
        assertFalse(placement.reversed)
    }

    @Test fun `a tall portrait item glides up and the menu goes below it`() {
        val anchor = MenuBounds(25f, 150f, 320f, 445f)
        val placement = contextMenuPlacement(anchor, screen, 250f, 198f)
        assertFalse(placement.beside)
        assertTrue(placement.previewDy < 0f)
        assertEquals(1f, placement.previewScale, 0f)
        val moved = anchor.translate(placement.previewDx, placement.previewDy)
        assertTrue(moved.top >= 16f - .01f)
        val menu = MenuBounds(placement.x, placement.y, placement.x + 250f, placement.y + 198f)
        assertFalse(menu.intersects(moved))
        assertTrue(menu.bottom <= 622f - 16f + .01f)
    }

    @Test fun `an item taller than the room shrinks to at most 80 percent before the menu would overlap`() {
        val anchor = MenuBounds(25f, 100f, 320f, 520f)
        val placement = contextMenuPlacement(anchor, screen, 250f, 198f)
        assertTrue(placement.previewScale in CONTEXT_PREVIEW_MIN_SCALE..1f)
        assertTrue(placement.previewScale < 1f)
        val height = anchor.height * placement.previewScale
        val centerY = anchor.centerY + placement.previewDy
        assertTrue(placement.y >= centerY + height / 2f + 10f - .01f)
        assertTrue(placement.y + 198f <= 622f - 16f + .01f)
    }

    // ---- Round 14: iOS never moves the item; the menu shrinks to fit (owner, phone cover) --------

    // The phone's cover, in dp: 1168x1712 px at 440 dpi, camera cutout at 557-611 x 0-126 px.
    private val cover = MenuBounds(0f, 0f, 1168f / 2.75f, 1712f / 2.75f)
    private val coverCamera = MenuBounds(557f / 2.75f, 0f, 611f / 2.75f, 126f / 2.75f)

    private fun placeScaled(anchor: MenuBounds, scalable: Float, hairlines: Float, width: Float = 250f): Pair<Float, ContextMenuPlacement> {
        val obstacles = listOf(coverCamera)
        val scale = contextMenuScale(anchor, cover, scalable, hairlines, gap = 12f, margin = 12f,
            maxScale = contextMenuMaxScale(cover.height), menuWidth = width, obstacles = obstacles)
        return scale to contextMenuPlacement(anchor, cover, width * scale, scalable * scale + hairlines, gap = 12f, margin = 12f,
            obstacles = obstacles)
    }

    private fun assertOpensInPlace(anchor: MenuBounds, scale: Float, placement: ContextMenuPlacement, height: Float) {
        assertTrue(scale in CONTEXT_MENU_MIN_SCALE..1f)
        assertFalse(placement.beside)
        assertEquals(0f, placement.previewDx, 0f)
        assertEquals(0f, placement.previewDy, 0f)
        assertEquals(1f, placement.previewScale, 0f)
        val menu = MenuBounds(placement.x, placement.y, placement.x + 250f * scale, placement.y + height)
        assertFalse(menu.intersects(anchor))
        assertFalse(menu.intersects(coverCamera.inflate(12f)))
        assertTrue(menu.top >= 12f - .01f && menu.bottom <= cover.bottom - 12f + .01f)
    }

    @Test fun `an icon in the third row from the bottom keeps its place and its menu opens above it`() {
        // Phone screenshot r14/home0: Files row, icons 902-1048 px; pinned app menu has 6 rows in 4 sections.
        val icon = MenuBounds(70f / 2.75f, 902f / 2.75f, 218f / 2.75f, 1048f / 2.75f)
        val scalable = 6 * 48f + 3 * 8f
        // At full size the menu fits neither side, which used to lift the icon.
        assertNotEquals(0f, contextMenuPlacement(icon, cover, 250f, scalable + 1f, gap = 12f, margin = 12f).previewDy)
        val (scale, placement) = placeScaled(icon, scalable, 1f)
        assertOpensInPlace(icon, scale, placement, scalable * scale + 1f)
        assertFalse(placement.below)
        // The camera would clip the cover-size menu above it: the menu is a little smaller instead (phone, Core.31.2).
        assertTrue(scale < contextMenuMaxScale(cover.height))
    }

    @Test fun `the Today weather widget keeps its place and its menu opens below it`() {
        // Phone screenshot r14/today0: small weather widget 500-890 x 648-1040 px; size row + 4 rows.
        val widget = MenuBounds(500f / 2.75f, 648f / 2.75f, 890f / 2.75f, 1040f / 2.75f)
        val scalable = 52f + 8f + 4 * 48f + 8f
        assertNotEquals(0f, contextMenuPlacement(widget, cover, 250f, scalable + 1f, gap = 12f, margin = 12f).previewDy)
        val (scale, placement) = placeScaled(widget, scalable, 1f)
        assertOpensInPlace(widget, scale, placement, scalable * scale + 1f)
        assertTrue(placement.below)
    }

    @Test fun `menus on one screen share one size wherever the item is`() {
        val scalable = 6 * 48f + 3 * 8f
        val top = placeScaled(MenuBounds(25f, 236f, 79f, 290f), scalable, 1f).first
        val bottom = placeScaled(MenuBounds(25f, 513f, 79f, 567f), scalable, 1f).first
        assertEquals(contextMenuMaxScale(cover.height), top, 0f)
        assertEquals(top, bottom, 0f)
        // An iPhone-tall screen keeps the full iOS size.
        assertEquals(1f, contextMenuMaxScale(852f), 0f)
    }
}
