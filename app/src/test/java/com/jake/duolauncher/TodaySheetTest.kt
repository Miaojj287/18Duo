package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The scrolled Today View's glass sheet (r21, owner's 7.webp). */
class TodaySheetTest {
    @Test fun `the sheet runs outside the widget column and is concentric with the cards`() {
        // A 4-wide widget column at x 10..370 dp, first widget at 50 dp, a 608 dp pane, 24 dp card corners.
        val frame = todaySheetFrame(10f, 360f, 50f, 608f, 24f)
        assertEquals(10f - TODAY_SHEET_SIDE_DP, frame.left, 1e-4f)
        assertEquals(370f + TODAY_SHEET_SIDE_DP, frame.right, 1e-4f)
        assertEquals(50f + TODAY_SHEET_TOP_DP, frame.top, 1e-4f)
        assertEquals(608f - TODAY_SHEET_BOTTOM_DP, frame.bottom, 1e-4f)
        assertEquals(24f + TODAY_SHEET_SIDE_DP, frame.radius, 1e-4f)
    }

    @Test fun `what lies outside the sheet is hidden at rest too, without cutting an unscrolled first widget`() {
        val frame = todaySheetFrame(10f, 360f, 50f, 608f, 24f)
        // At rest the top edge sits just above the first widget's resting top; while the sheet shows, on its rim.
        assertEquals(49f, todaySheetMaskTop(frame, 0f), 1e-4f)
        assertEquals(frame.top, todaySheetMaskTop(frame, 1f), 1e-4f)
        assertEquals(49f + (frame.top - 49f) / 2f, todaySheetMaskTop(frame, .5f), 1e-4f)
        // The bottom rim never moves: a widget resting below it is out of sight.
        assertEquals(608f - TODAY_SHEET_BOTTOM_DP, frame.bottom, 1e-4f)
    }

    @Test fun `scroll bar thumb follows the scroll and keeps a minimum length`() {
        // Viewport 600 of 1200 px content on a 500 px track: half the track, at the end when fully scrolled.
        assertEquals(0f to 250f, todayScrollThumb(0, 600, 600, 500f, 40f))
        assertEquals(250f to 250f, todayScrollThumb(600, 600, 600, 500f, 40f))
        val long = todayScrollThumb(100, 100_000, 600, 500f, 40f)!!
        assertEquals(40f, long.second, 0f)
        assertNull(todayScrollThumb(0, 0, 600, 500f, 40f))
    }
}
