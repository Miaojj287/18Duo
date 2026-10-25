package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuoTypographyTest {
    @Test fun tracking_opens_captions_and_tightens_body_and_titles_like_sf_pro() {
        // Icon labels (11 sp) stay neutral-to-open; body and titles tighten progressively.
        assertTrue(duoTrackingEm(11f) in 0f..0.01f)
        assertTrue(duoTrackingEm(15f) < 0f)
        assertTrue(duoTrackingEm(17f) < duoTrackingEm(15f))
        assertTrue(duoTrackingEm(32f) < duoTrackingEm(17f))
        // The large-title value matches the −0.7 sp the launcher used before Inter at 32 sp.
        assertEquals(-.7f, duoTrackingEm(32f) * 32f, .05f)
        // The curve flattens out instead of collapsing letters at clock sizes.
        assertTrue(duoTrackingEm(200f) > -.0225f)
    }

    @Test fun degenerate_sizes_get_no_tracking() {
        assertEquals(0f, duoTrackingEm(0f), 0f)
        assertEquals(0f, duoTrackingEm(-3f), 0f)
        assertEquals(0f, duoTrackingEm(Float.NaN), 0f)
    }

    @Test fun display_cut_matches_apples_twenty_point_switch() {
        assertEquals(20f, DISPLAY_SWITCH_SP, 0f)
    }
}
