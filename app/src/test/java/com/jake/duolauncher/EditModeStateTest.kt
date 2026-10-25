package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class EditModeStateTest {
    @Test fun `long pressing an item opens the context menu and the edit row enters jiggle`() {
        val menu = editModeTransition(HomeEditMode.NONE, HomeEditEvent.LongPressItem)
        assertEquals(HomeEditMode.CONTEXT_MENU, menu)
        assertEquals(HomeEditMode.JIGGLE, editModeTransition(menu, HomeEditEvent.EditHome))
        assertEquals(HomeEditMode.NONE, editModeTransition(menu, HomeEditEvent.DismissMenu))
        assertEquals(HomeEditMode.NONE, editModeTransition(menu, HomeEditEvent.TapEmpty))
        assertEquals(HomeEditMode.NONE, editModeTransition(menu, HomeEditEvent.Back))
    }

    @Test fun `dragging out of a long press or holding empty space enters jiggle`() {
        assertEquals(HomeEditMode.JIGGLE, editModeTransition(HomeEditMode.CONTEXT_MENU, HomeEditEvent.DragStart))
        assertEquals(HomeEditMode.JIGGLE, editModeTransition(HomeEditMode.NONE, HomeEditEvent.DragStart))
        assertEquals(HomeEditMode.JIGGLE, editModeTransition(HomeEditMode.NONE, HomeEditEvent.LongPressEmpty))
        assertEquals(HomeEditMode.JIGGLE, editModeTransition(HomeEditMode.JIGGLE, HomeEditEvent.LongPressEmpty))
    }

    @Test fun `jiggle survives item presses but ends on done back home or a background tap`() {
        assertEquals(HomeEditMode.JIGGLE, editModeTransition(HomeEditMode.JIGGLE, HomeEditEvent.LongPressItem))
        assertEquals(HomeEditMode.JIGGLE, editModeTransition(HomeEditMode.JIGGLE, HomeEditEvent.DismissMenu))
        // Apple support: "tap the Home Screen background" when finished.
        assertEquals(HomeEditMode.NONE, editModeTransition(HomeEditMode.JIGGLE, HomeEditEvent.TapEmpty))
        assertEquals(HomeEditMode.NONE, editModeTransition(HomeEditMode.JIGGLE, HomeEditEvent.Done))
        assertEquals(HomeEditMode.NONE, editModeTransition(HomeEditMode.JIGGLE, HomeEditEvent.Back))
        assertEquals(HomeEditMode.NONE, editModeTransition(HomeEditMode.JIGGLE, HomeEditEvent.Home))
        assertEquals(HomeEditMode.NONE, editModeTransition(HomeEditMode.NONE, HomeEditEvent.TapEmpty))
    }

    @Test fun `jiggle phases are stable per item and spread over a full turn`() {
        assertEquals(jigglePhase("com.app/a"), jigglePhase("com.app/a"), 0f)
        val phases = (0 until 40).map { jigglePhase("app-$it") }
        phases.forEach { assertTrue(it in 0f..(2f * Math.PI.toFloat() + .001f)) }
        assertTrue(phases.distinct().size >= 36)
        assertTrue(phases.count { it < Math.PI } in 8..32)
    }

    @Test fun `legacy jiggle rotation stays within the amplitude and repeats every period`() {
        val phase = jigglePhase(7)
        for (t in 0L until JIGGLE_PERIOD_MS * 2 step 7) {
            val rotation = jiggleRotation(t, phase)
            assertTrue(abs(rotation) <= JIGGLE_AMPLITUDE_DEG + .0001f)
            assertEquals(rotation, jiggleRotation(t + JIGGLE_PERIOD_MS, phase), .0001f)
        }
        assertNotEquals(jiggleRotation(0L, 0f), jiggleRotation(JIGGLE_PERIOD_MS / 4, 0f), .5f)
        assertEquals(0f, jiggleRotation(50L, 1f, periodMs = 0L), 0f)
    }

    @Test fun `every item jiggles with its own amplitude period and phase around the iOS values`() {
        val motions = (0 until 30).map { jiggleMotion("com.app/$it") }
        motions.forEach {
            assertTrue(it.amplitudeDeg in 1.6f..2.0f)
            assertTrue(it.periodMs in 235f..285f)
            assertTrue(it.bobDp in .4f..0.7f)
        }
        assertTrue(motions.map { it.periodMs }.distinct().size >= 25)
        assertTrue(motions.map { it.amplitudeDeg }.distinct().size >= 25)
        assertEquals(jiggleMotion("same"), jiggleMotion("same"))
        // Neighbours are out of step at the same instant.
        val sample = motions.map { jiggleRotation(1234L, it) }
        assertTrue(sample.count { it > 0f } in 6..24)
    }

    @Test fun `rotation and bob are bounded, scale with intensity and stop at zero intensity`() {
        val motion = jiggleMotion("icon")
        for (t in 0L..2000L step 11) {
            assertTrue(abs(jiggleRotation(t, motion)) <= motion.amplitudeDeg + 1e-4f)
            assertTrue(abs(jiggleBob(t, motion)) <= motion.bobDp + 1e-4f)
            assertEquals(0f, jiggleRotation(t, motion, intensity = 0f), 0f)
            assertEquals(jiggleRotation(t, motion) / 2f, jiggleRotation(t, motion, intensity = .5f), 1e-4f)
        }
    }

    @Test fun `widgets rotate less so their corners travel about as far as an icon's`() {
        val icon = jiggleMotion("w", sizeRatio = 1f)
        val widget = jiggleMotion("w", sizeRatio = 2.65f)
        assertTrue(widget.amplitudeDeg < icon.amplitudeDeg / 2f)
        assertTrue(widget.amplitudeDeg >= .45f)
        assertEquals(icon.amplitudeDeg * 54f / 2f, widget.amplitudeDeg * 54f * 2.65f / 2f, .5f)
        assertEquals(.45f, jiggleMotion("huge", sizeRatio = 20f).amplitudeDeg, 0f)
    }

    @Test fun `remove badge is an iOS sized disc overhanging the icon corner`() {
        val badge = removeBadgeGeometry(54f)
        assertEquals(20.5f, badge.diameter, .1f)
        // Centre sits ~0.07 icon inside the icon's top-left corner.
        assertEquals(54f * .07f, badge.offsetX + badge.diameter / 2f, .01f)
        assertEquals(badge.offsetX, badge.offsetY, 0f)
        assertTrue(badge.offsetX < 0f)
        assertEquals(17f, removeBadgeGeometry(30f).diameter, 0f)
        assertEquals(25f, removeBadgeGeometry(90f).diameter, 0f)
        val capped = removeBadgeGeometry(54f, maxOverhangX = 2f, maxOverhangY = 0f)
        assertEquals(-2f, capped.offsetX, 0f)
        assertEquals(0f, capped.offsetY, 0f)
    }

    @Test fun `dock badges never leave the dock glass`() {
        // Duo Dock: 44dp icons, ~10.5dp side padding, ~13.5dp above the first icon.
        val (ox, oy) = dockBadgeOverhang(44f, 10.5f, 13.5f)
        val badge = removeBadgeGeometry(44f)
        assertTrue(ox > 0f && ox <= 10.5f - 1.5f)
        assertTrue(oy > 0f && oy <= 13.5f - 1.5f)
        assertTrue(ox <= -badge.offsetX && oy <= -badge.offsetY)
        // Inside the concentric corner: distance to the corner centre + radius <= corner radius.
        val iconRadius = 44f * .225f
        val corner = iconRadius + 10.5f
        val cx = -ox + badge.diameter / 2f; val cy = -oy + badge.diameter / 2f
        val dx = (iconRadius - cx).coerceAtLeast(0f); val dy = (iconRadius - (13.5f - 10.5f) - cy).coerceAtLeast(0f)
        assertTrue(kotlin.math.sqrt(dx * dx + dy * dy) + badge.diameter / 2f <= corner)
        // Core.16's cramped Dock (6dp top padding + 2dp) keeps the disc inside too.
        val (tx, ty) = dockBadgeOverhang(44f, 4f, 3f)
        assertTrue(tx <= 2.5f && ty <= 1.5f)
    }
}
