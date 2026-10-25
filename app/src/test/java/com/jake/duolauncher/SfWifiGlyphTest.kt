package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The status ring and its SF "wifi" glyph against Apple's status-bar video (status-bar.mp4 at 3.17 s,
 * 1920×1080; ring outer diameter D = 353.7 px, centre y = 239.2 px in the 480 px crop). Element
 * bounding boxes are the anti-aliased 50 % coverage boxes of each connected component; all values
 * are fractions of D relative to the ring centre (y down). evidence/r3-chrome/wifi/ has the frames.
 */
class SfWifiGlyphTest {
    private val tolerance = .006f // ≈ 2 px at D = 353.7, a fifth of a pixel on the 100 px phone ring

    private fun assertExtent(name: String, extent: GlyphExtent, width: Float, top: Float, bottom: Float) {
        assertEquals("$name width", width, extent.width, tolerance)
        assertEquals("$name top", top, extent.top, tolerance)
        assertEquals("$name bottom", bottom, extent.bottom, tolerance)
        assertEquals("$name is centred", 0f, extent.left + extent.right, 1e-5f)
    }

    @Test fun `wifi elements match Apple's frame`() {
        assertExtent("wedge", sfWifiElementExtent(0), 44f / 353.7f, (267f - 239.2f) / 353.7f, (302.3f - 239.2f) / 353.7f)
        assertExtent("inner arc", sfWifiElementExtent(1), 100f / 353.7f, (226f - 239.2f) / 353.7f, (262f - 239.2f) / 353.7f)
        assertExtent("outer arc", sfWifiElementExtent(2), 158f / 353.7f, (185f - 239.2f) / 353.7f, (233f - 239.2f) / 353.7f)
    }

    @Test fun `wifi glyph keeps SF proportions`() {
        // One stroke weight for both arcs, slightly lighter than the ring's.
        assertEquals(.88f, SfWifiGlyph.STROKE / DuoStatusRing.STROKE, .02f)
        // Equal clear gaps wedge → inner arc → outer arc.
        val wedgeOuter = SfWifiGlyph.WEDGE_CORE + SfWifiGlyph.WEDGE_CORNER
        val gap1 = SfWifiGlyph.INNER_ARC - SfWifiGlyph.STROKE / 2f - wedgeOuter
        val gap2 = (SfWifiGlyph.OUTER_ARC - SfWifiGlyph.STROKE / 2f) - (SfWifiGlyph.INNER_ARC + SfWifiGlyph.STROKE / 2f)
        assertEquals(gap1, gap2, .004f)
        // The visual fan (round caps included) opens ≈ 98°, a little wider than the wedge's 92°.
        val capAngle = Math.toDegrees((SfWifiGlyph.STROKE / 2f / SfWifiGlyph.OUTER_ARC).toDouble())
        assertEquals(49.0, SfWifiGlyph.ARC_HALF_ANGLE + capAngle, 1.0)
        assertTrue(SfWifiGlyph.WEDGE_HALF_ANGLE in 44f..48f)
        // The whole glyph sits inside the ring's inner edge with clear space above.
        val ringInner = .5f - DuoStatusRing.STROKE
        assertTrue(-sfWifiElementExtent(2).top < ringInner - .15f)
        assertTrue(sfWifiElementExtent(0).bottom < ringInner)
    }

    @Test fun `ring and cellular dots match Apple's frame`() {
        assertEquals(25.35f / 353.7f, DuoStatusRing.STROKE, .002f)
        assertEquals(241.4f, DuoStatusRing.ARC_SWEEP, .8f)
        assertEquals("gap centred at the bottom", 90f, DuoStatusRing.ARC_START + DuoStatusRing.ARC_SWEEP / 2f - 180f, .01f)
        // Video dot centres (x, y) in the crop, measured as coverage centroids.
        val measured = listOf(154.4f to 384.2f, 210.3f to 404.4f, 268.5f to 404.5f, 324.3f to 384.3f)
        val cx = 239.4f; val cy = 239.2f
        DuoStatusRing.DOT_ANGLES.forEachIndexed { index, degrees ->
            val angle = Math.toRadians(degrees.toDouble())
            val x = cx + 353.7f * DuoStatusRing.DOT_RADIUS * cos(angle).toFloat()
            val y = cy + 353.7f * DuoStatusRing.DOT_RADIUS * sin(angle).toFloat()
            assertEquals("dot $index x", measured[index].first, x, 2.5f)
            assertEquals("dot $index y", measured[index].second, y, 2.5f)
        }
        assertEquals(34.9f / 353.7f, DuoStatusRing.DOT_DIAMETER, .002f)
    }
}
