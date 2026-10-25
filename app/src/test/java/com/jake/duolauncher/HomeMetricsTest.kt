package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test

class HomeMetricsTest {
    @Test fun `cover metrics are the measured iPhone Duo fractions`() {
        val m = homeMetrics(425f)
        assertEquals(1f, m.scale, .0001f)
        assertEquals(54f, m.iconSize, .1f)                  // 12.7 %W
        assertEquals(80.3f, m.columnPitch, .1f)              // 18.9 %W
        assertEquals(90.5f, m.rowPitch, .5f)                 // 14.55 %H of the 1.455 cover
        assertEquals(11f, m.labelSp, 0f)
        assertEquals(143.2f, m.smallWidget, .1f)             // 33.7 %W
        assertEquals(m.iconSize * IOS_ICON_CORNER_FRACTION, m.iconCornerRadius, .001f)
        assertEquals(.31f, m.dockRadius / 65f, .03f)         // concentric, not a capsule
        assertEquals(4, m.columns)
    }

    @Test fun `metrics agree with the pose frame on the cover`() {
        val frame = duoHomeFrame(DuoHomePose.COVER_PORTRAIT, 425f, 622f)
        val m = homeMetrics(425f)
        assertEquals(frame.grid.icon, m.iconSize, .001f)
        assertEquals(frame.grid.columnPitch, m.columnPitch, .001f)
        assertEquals(frame.grid.widgetSide, m.smallWidget, .001f)
        assertEquals(frame.dock.cornerRadius, m.dockRadius, .001f)
        assertEquals(425f, homeMetricsPanelWidth(425f, 622f, expanded = false), 0f)
        assertEquals("A rotated cover keeps its portrait metrics", 425f, homeMetricsPanelWidth(622f, 425f, expanded = false), 0f)
        assertEquals(430f, homeMetricsPanelWidth(860f, 608f, expanded = true), 0f)
    }

    @Test fun `a narrow panel narrows column gaps before artwork and never below the floor`() {
        val ideal = homeMetrics(425f)
        val fitted = ideal.fitted(300f)
        assertEquals(ideal.iconSize, fitted.iconSize, 0f)
        assertEquals(300f, fitted.gridWidth, .001f)
        assertTrue(fitted.columnGap in MIN_COLUMN_GAP_DP..ideal.columnGap)
        val cramped = ideal.fitted(270f)
        assertEquals(270f, cramped.gridWidth, .001f)
        assertEquals(MIN_COLUMN_GAP_DP, cramped.columnGap, .001f)
        val tiny = ideal.fitted(200f)
        assertEquals("Artwork stops at the floor and the gap absorbs the rest", MIN_ICON_DP, tiny.iconSize, .001f)
        assertSame(ideal, ideal.fitted(Float.POSITIVE_INFINITY))
        assertSame(ideal, ideal.fitted(1000f))
        assertEquals(homeMetrics(425f, 300f), fitted)
    }

    @Test fun `invalid widths fall back to the shared cover canvas`() {
        assertEquals(homeMetrics(SHARED_HOME_WIDTH_DP), homeMetrics(Float.NaN))
        assertEquals(homeMetrics(SHARED_HOME_WIDTH_DP), homeMetrics(0f))
    }
}
