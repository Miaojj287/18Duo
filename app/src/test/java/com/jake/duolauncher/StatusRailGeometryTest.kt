package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

/** Status rail sizing and camera avoidance against the real Xiaomi 18 Fold cutouts (BRIEF). */
class StatusRailGeometryTest {
    private val density = 2.75f
    private fun px(left: Int, top: Int, right: Int, bottom: Int) =
        DuoBox(left / density, top / density, right / density, bottom / density)

    /** Cover: top-centre hole [557,0][611,126] px in a 1168×1712 panel. */
    private val coverCamera = px(557, 0, 611, 126)
    /** Unfolded: top-right hole [2224,0][2364,164] px in a 2364×1672 panel. */
    private val innerCamera = px(2224, 0, 2364, 164)
    /** The cover hole after rotating the cover to landscape (ROTATION_90 and ROTATION_270). */
    private val coverCameraLeft = px(0, 557, 126, 611)
    private val coverCameraRight = px(1712 - 126, 557, 1712, 611)
    /** The unfolded hole when the open phone is held in portrait (both rotations). */
    private val innerPortraitTopLeft = px(0, 0, 164, 140)
    private val innerPortraitBottomRight = px(1672 - 164, 2224, 1672, 2364)

    private val cover = duoHomeFrame(DuoHomePose.COVER_PORTRAIT, 1168 / density, 1712 / density)
    private val inner = duoHomeFrame(DuoHomePose.INNER_LANDSCAPE, 2364 / density, 1672 / density)
    private val coverLandscape = duoHomeFrame(DuoHomePose.COVER_LANDSCAPE, 1712 / density, 1168 / density)
    private val innerPortrait = duoHomeFrame(DuoHomePose.INNER_PORTRAIT, 1672 / density, 2364 / density)

    @Test fun `the centred cover camera moves nothing`() {
        assertEquals("No element intersects the cover camera; nothing is over-shifted",
            cover, cover.avoidingCutouts(listOf(coverCamera)))
        assertTrue(cover.grid.firstRowTop > coverCamera.bottom)
    }

    @Test fun `the unfolded corner camera moves only the status block by the minimum`() {
        assertTrue(inner.status.bounds.intersects(innerCamera))
        val moved = inner.avoidingCutouts(listOf(innerCamera))
        assertEquals(innerCamera.bottom + CUTOUT_CLEARANCE_DP, moved.status.bounds.top, .01f)
        val dy = moved.status.timeCenterY - inner.status.timeCenterY
        assertEquals("time and ring move together", dy, moved.status.ringCenterY - inner.status.ringCenterY, .001f)
        assertEquals(inner.status.timeCenterX, moved.status.timeCenterX, 0f)
        assertEquals(inner.grid, moved.grid)
        assertEquals(inner.dock, moved.dock)
        assertEquals(inner.search, moved.search)
        assertEquals(inner.dots, moved.dots)
        assertTrue(moved.status.bounds.bottom + 8f < moved.dock.box.top)
    }

    @Test fun `a cutout outside the status column leaves the inner status at Apple's height`() {
        val elsewhere = DuoBox(400f, 0f, 440f, 30f)
        assertEquals(inner.status, inner.avoidingCutouts(listOf(elsewhere)).status)
        assertEquals(inner.status, inner.avoidingCutouts(emptyList()).status)
    }

    @Test fun `rotated cover nudges the grid off a left camera by a few dp`() {
        val moved = coverLandscape.avoidingCutouts(listOf(coverCameraLeft))
        val dx = moved.grid.cellLeft - coverLandscape.grid.cellLeft
        assertTrue(dx > 0f && dx < 6f)
        assertEquals(coverCameraLeft.right + 2f, moved.grid.iconLeft, .01f)
        assertEquals(coverLandscape.dock, moved.dock)
        assertEquals(coverLandscape.status, moved.status)
        assertEquals(coverLandscape.grid.firstRowTop, moved.grid.firstRowTop, 0f)
    }

    @Test fun `rotated cover moves the whole control column off a right camera`() {
        assertTrue(coverLandscape.dock.box.intersects(coverCameraRight))
        val moved = coverLandscape.avoidingCutouts(listOf(coverCameraRight))
        assertFalse(moved.dock.box.intersects(coverCameraRight, CUTOUT_CLEARANCE_DP - .01f))
        assertEquals(coverCameraRight.left - CUTOUT_CLEARANCE_DP, moved.dock.box.right, .01f)
        assertEquals(moved.dock.box.centerX, moved.status.timeCenterX, .01f)
        assertEquals(moved.dock.box.centerX, moved.search.centerX, .01f)
        val g = moved.grid
        assertTrue(g.iconLeft >= 16f)
        assertTrue(g.iconLeft + g.iconGridWidth < moved.dock.box.left)
        assertEquals(g.iconLeft + g.iconGridWidth / 2f, moved.dots.centerX, .01f)
    }

    @Test fun `open portrait moves only what the real camera hits, by the shortest way`() {
        // Turned counter-clockwise the hole is top-left, far from the centred grid and the corner status.
        assertEquals(innerPortrait, innerPortrait.avoidingCutouts(listOf(innerPortraitTopLeft)))
        // Turned clockwise it is bottom-right, over Search: Search rises just clear (31 dp) instead of
        // moving 44 dp sideways; the compact Dock, grid, status and dots stay at Apple's place.
        assertTrue(innerPortrait.search.intersects(innerPortraitBottomRight))
        val moved = innerPortrait.avoidingCutouts(listOf(innerPortraitBottomRight))
        assertEquals(innerPortraitBottomRight.top - CUTOUT_CLEARANCE_DP, moved.search.bottom, .01f)
        assertEquals(innerPortrait.search.centerX, moved.search.centerX, 0f)
        assertEquals(innerPortrait.dock, moved.dock)
        assertEquals(innerPortrait.grid, moved.grid)
        assertEquals(innerPortrait.status, moved.status)
        assertEquals(innerPortrait.dots, moved.dots)
        // A camera that did reach into the Dock lifts it clear; its icons stay centred.
        val dock = innerPortrait.dock.box
        val camera = DuoBox(dock.right - 30f, dock.bottom - 20f, dock.right + 20f, 860f)
        val lifted = innerPortrait.avoidingCutouts(listOf(camera))
        assertEquals(camera.top - CUTOUT_CLEARANCE_DP, lifted.dock.box.bottom, .01f)
        assertEquals(innerPortrait.dock.contentCenterX, lifted.dock.contentCenterX, 0f)
        assertEquals(innerPortrait.grid, lifted.grid)
    }

    @Test fun `a camera over the open portrait status pushes the corner block down as one unit`() {
        val camera = DuoBox(innerPortrait.status.timeCenterX - 15f, 0f, innerPortrait.status.timeCenterX + 15f, 40f)
        val moved = innerPortrait.avoidingCutouts(listOf(camera))
        assertEquals(camera.bottom + CUTOUT_CLEARANCE_DP, moved.status.bounds.top, .01f)
        assertEquals(innerPortrait.status.timeCenterX, moved.status.timeCenterX, 0f)
        assertEquals(moved.status.timeCenterY - innerPortrait.status.timeCenterY,
            moved.status.ringCenterY - innerPortrait.status.ringCenterY, .001f)
    }

    @Test fun `rail geometry lands the ring centre on the measured height`() {
        for (frame in listOf(cover, inner, innerPortrait)) {
            val status = frame.status
            val rail = status.railGeometry(density = density)
            val ringCenter = status.railTop + rail.clockHeight + rail.clockGap + rail.visualSize / 2f
            assertEquals(status.ringCenterY, ringCenter, .5f)
            assertEquals(status.ringDiameter, rail.visualSize, 0f)
            assertEquals(15f, rail.timeFontSize, .1f)
            assertEquals("time is centred on its measured height", status.timeCenterY,
                status.railTop + rail.clockHeight / 2f, .001f)
        }
    }

    @Test fun `status text ignores the system font scale like iOS`() {
        val rail = cover.status.railGeometry(fontScale = 1.5f)
        assertEquals(15f / 1.5f, rail.timeFontSize, .01f)
        assertEquals(cover.status.ringDiameter, rail.visualSize, 0f)
    }

    @Test fun `fallback rail sizing snaps to pixels without inventing offsets`() {
        for (d in listOf(1f, 2f, 2.625f, 2.75f, 3.5f)) {
            val rail = statusRailGeometry(68f, density = d)
            val enlargedText = statusRailGeometry(68f, density = d, fontScale = 1.5f)
            assertEquals(rail.visualSize, enlargedText.visualSize, 0f)
            assertEquals(rail.timeFontSize / 1.5f, enlargedText.timeFontSize, .0001f)
            assertEquals((rail.visualSize * .55f * d).roundToInt().toFloat(), rail.clockHeight * d, 1f)
            assertEquals(0f, rail.speedHeight, 0f)
        }
    }

    @Test fun `zero sized cutout reports are ignored`() {
        assertEquals(inner, inner.avoidingCutouts(listOf(DuoBox(800f, 0f, 800f, 0f))))
    }
}
