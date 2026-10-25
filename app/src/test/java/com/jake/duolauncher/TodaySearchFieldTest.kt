package com.jake.duolauncher

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fix round 2: Today View search field (visual P2-8), Spotlight warm-up (P2-8e), drag regions (P2-8c). */
class TodaySearchFieldTest {
    private fun smallCard(geometry: HomeGeometry, column: Int): Pair<Float, Float> {
        val cell = geometry.gridWidth / geometry.columns
        val frame = widgetPlacementFrame(-1, 2, 2, cell, geometry.iconSize, geometry.iconSize, 2f * geometry.rowHeight,
            geometry.referenceCardSize)
        val left = cell * ((geometry.columns - GRID_COLUMNS) / 2f + column) + frame.insetX
        return left to left + frame.width
    }

    private fun assertSpansWidgetColumn(geometry: HomeGeometry) {
        val field = todaySearchFieldFrame(geometry.gridWidth, geometry.columns, geometry.iconSize, geometry.referenceCardSize)
        val (leftCardLeft, _) = smallCard(geometry, 0)
        val (_, rightCardRight) = smallCard(geometry, 2)
        assertEquals("starts at the first widget's left edge", leftCardLeft, field.left, .01f)
        assertEquals("ends at the last widget's right edge", rightCardRight, field.left + field.width, .01f)
    }

    @Test fun `search field spans exactly the Today widget column on every panel`() {
        assertSpansWidgetColumn(duoHomeFrame(DuoHomePose.COVER_PORTRAIT, 425f, 622f).toHomeGeometry())
        assertSpansWidgetColumn(duoHomeFrame(DuoHomePose.INNER_LANDSCAPE, 860f, 608f).toHomeGeometry())
        assertSpansWidgetColumn(duoHomeFrame(DuoHomePose.INNER_PORTRAIT, 608f, 860f).toHomeGeometry())
        // The rotated cover keeps Today's four columns centred on its six-column canvas.
        val landscape = duoHomeFrame(DuoHomePose.COVER_LANDSCAPE, 622f, 425f).toHomeGeometry()
        assertSpansWidgetColumn(landscape)
        val field = todaySearchFieldFrame(landscape.gridWidth, landscape.columns, landscape.iconSize, landscape.referenceCardSize)
        assertEquals("centred", landscape.gridWidth / 2f, field.left + field.width / 2f, .01f)
    }

    @Test fun `search field is an iOS sized capsule above the widgets`() {
        val cover = duoHomeFrame(DuoHomePose.COVER_PORTRAIT, 425f, 622f).toHomeGeometry()
        val field = todaySearchFieldFrame(cover.gridWidth, cover.columns, cover.iconSize, cover.referenceCardSize)
        assertTrue("wide enough for the placeholder", field.width > 4f * TODAY_SEARCH_HEIGHT)
        // Off on iPhone Duo: Today's first widget stays on Home's row 1.
        assertEquals(if (TODAY_SEARCH_IN_TODAY_VIEW) TODAY_SEARCH_HEIGHT + TODAY_SEARCH_GAP else 0f, TODAY_SEARCH_BLOCK, 0f)
    }

    @Test fun `Spotlight warms every app name and Settings entry before it opens`() {
        val labels = spotlightWarmLabels(emptyList())
        SETTINGS_LINKS.forEach { link ->
            assertTrue(link.zh in labels && link.en in labels && labels.containsAll(link.keywords))
        }
        assertTrue("never blocks Home's first frames", SEARCH_KEY_WARM_DELAY_MS in 1..1_000)
    }

    @Test fun `an unchanged drag region is not rebuilt or rewritten`() {
        val drag = HomeDragState()
        val owner = Any()
        val target = DropTarget.Home(3)
        drag.register(owner, target, Rect(0f, 0f, 10f, 10f), "app", 0, null, null, null)
        val first = drag.regions[target]
        drag.register(owner, target, Rect(0f, 0f, 10f, 10f), "app", 0, null, null, null)
        assertSame("same position: nothing allocated or written", first, drag.regions[target])
        drag.register(owner, target, Rect(5f, 0f, 15f, 10f), "app", 0, null, null, null)
        assertNotSame(first, drag.regions[target])
        assertEquals(Rect(5f, 0f, 15f, 10f), drag.regions[target]!!.bounds)
        // A different owner taking over the target always registers (and owns its removal).
        val other = Any()
        drag.register(other, target, Rect(5f, 0f, 15f, 10f), "app", 0, null, null, null)
        drag.unregister(owner, target)
        assertEquals("the old owner no longer removes it", Rect(5f, 0f, 15f, 10f), drag.regions[target]!!.bounds)
        drag.unregister(other, target)
        assertEquals(null, drag.regions[target])
    }
}
