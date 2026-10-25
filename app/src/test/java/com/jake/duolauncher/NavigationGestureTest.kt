package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationGestureTest {
    @Test fun `quick bottom swipe goes home`() {
        val gesture = NavigationGesture(NavigationEdge.BOTTOM)
        gesture.down(100f, 600f, 0L)
        gesture.move(100f, 540f, 120L)

        assertEquals(NavigationAction.HOME, gesture.up(180L))
    }

    @Test fun `a pause engages the switcher while the finger is down and release keeps it`() {
        val gesture = NavigationGesture(NavigationEdge.BOTTOM)
        gesture.down(100f, 600f, 0L)
        gesture.move(100f, 520f, 100L)

        assertNull(gesture.tick(100L + SwitcherMotion.HOLD_MILLIS - 1))
        assertFalse(gesture.progress(100L + SwitcherMotion.HOLD_MILLIS - 1).engaged)
        assertNull(gesture.tick(100L + SwitcherMotion.HOLD_MILLIS))
        assertTrue(gesture.progress(100L + SwitcherMotion.HOLD_MILLIS).engaged)
        assertEquals(NavigationAction.RECENTS, gesture.up(500L))
    }

    @Test fun `a pause too low on the screen does not engage`() {
        val gesture = NavigationGesture(NavigationEdge.BOTTOM)
        gesture.down(100f, 600f, 0L)
        gesture.move(100f, 570f, 50L)
        gesture.tick(1_000L)
        assertFalse(gesture.progress(1_000L).engaged)
    }

    /** Round 12 (iOS): the pause commits the gesture to the switcher; a hard flick afterwards opens it too. */
    @Test fun `engaged and then flicked hard upward opens the switcher`() {
        val gesture = NavigationGesture(NavigationEdge.BOTTOM)
        gesture.down(100f, 600f, 0L)
        gesture.move(100f, 520f, 100L)
        gesture.tick(400L)
        assertTrue(gesture.progress(400L).engaged)
        gesture.move(100f, 460f, 440L)
        gesture.move(100f, 360f, 480L)
        assertEquals(NavigationAction.RECENTS, gesture.up(490L))
    }

    @Test fun `engaged and then pulled back down cancels`() {
        val gesture = NavigationGesture(NavigationEdge.BOTTOM)
        gesture.down(100f, 600f, 0L)
        gesture.move(100f, 520f, 100L)
        gesture.tick(400L)
        assertTrue(gesture.progress(400L).engaged)
        gesture.move(100f, 540f, 440L)
        gesture.move(100f, 580f, 480L)
        assertNull(gesture.up(490L))
    }

    @Test fun `stationary bottom hold opens recovery`() {
        val gesture = NavigationGesture(NavigationEdge.BOTTOM)
        gesture.down(100f, 600f, 0L)

        assertNull(gesture.tick(1_999L))
        assertEquals(NavigationAction.RECOVERY, gesture.tick(2_000L))
        assertNull(gesture.up(2_100L))
    }

    @Test fun `either side inward swipe goes back`() {
        val left = NavigationGesture(NavigationEdge.LEFT)
        left.down(0f, 100f, 0L)
        left.move(40f, 104f, 100L)
        assertEquals(NavigationAction.BACK, left.up(120L))

        val right = NavigationGesture(NavigationEdge.RIGHT)
        right.down(200f, 100f, 0L)
        right.move(160f, 96f, 100L)
        assertEquals(NavigationAction.BACK, right.up(120L))
    }

    @Test fun `wrong direction and steep diagonal do nothing`() {
        val outward = NavigationGesture(NavigationEdge.LEFT)
        outward.down(20f, 100f, 0L)
        outward.move(0f, 100f, 100L)
        assertNull(outward.up(120L))

        val diagonal = NavigationGesture(NavigationEdge.RIGHT)
        diagonal.down(200f, 100f, 0L)
        diagonal.move(160f, 150f, 100L)
        assertNull(diagonal.up(120L))
    }

    @Test fun `cancellation prevents every action`() {
        val gesture = NavigationGesture(NavigationEdge.BOTTOM)
        gesture.down(100f, 600f, 0L)
        gesture.move(100f, 530f, 100L)
        gesture.cancel()

        assertNull(gesture.tick(1_000L))
        assertNull(gesture.up(1_000L))
    }

    @Test fun `side feedback follows inward distance and keeps pointer position`() {
        val gesture = NavigationGesture(NavigationEdge.LEFT)
        gesture.down(2f, 100f, 0L)
        gesture.move(16f, 108f, 40L)

        val halfway = gesture.progress(40L)
        assertTrue(halfway.active)
        assertEquals(.5f, halfway.distance, .0001f)
        assertEquals(16f, halfway.x, 0f)
        assertEquals(108f, halfway.y, 0f)

        gesture.move(80f, 110f, 80L)
        assertEquals(1f, gesture.progress(80L).distance, 0f)
    }

    @Test fun `the bottom gesture locks to the first clear axis`() {
        val vertical = NavigationGesture(NavigationEdge.BOTTOM)
        vertical.down(100f, 600f, 0L)
        assertEquals(BottomAxis.UNDECIDED, vertical.progress(0L).axis)
        vertical.move(102f, 588f, 20L)
        assertEquals(BottomAxis.VERTICAL, vertical.progress(20L).axis)
        // Later sideways motion moves the card, it does not become a quick switch.
        vertical.move(220f, 560f, 60L)
        assertEquals(BottomAxis.VERTICAL, vertical.progress(60L).axis)

        val horizontal = NavigationGesture(NavigationEdge.BOTTOM)
        horizontal.down(100f, 600f, 0L)
        horizontal.move(115f, 597f, 20L)
        assertEquals(BottomAxis.HORIZONTAL, horizontal.progress(20L).axis)

        val down = NavigationGesture(NavigationEdge.BOTTOM)
        down.down(100f, 600f, 0L)
        down.move(101f, 612f, 20L)
        assertEquals(BottomAxis.UNDECIDED, down.progress(20L).axis)
        assertNull(down.up(40L))
    }

    @Test fun `sideways swipes along the indicator report quick switch directions`() {
        val right = NavigationGesture(NavigationEdge.BOTTOM)
        right.down(100f, 610f, 0L)
        right.move(140f, 606f, 40L)
        right.move(260f, 600f, 160L)
        assertEquals(NavigationAction.QUICK_PREVIOUS, right.up(170L))

        val left = NavigationGesture(NavigationEdge.BOTTOM)
        left.down(300f, 610f, 0L)
        left.move(260f, 608f, 40L)
        left.move(240f, 606f, 60L)
        // A short fast flick is enough.
        assertEquals(NavigationAction.QUICK_NEXT, left.up(70L))

        val timid = NavigationGesture(NavigationEdge.BOTTOM)
        timid.down(100f, 610f, 0L)
        timid.move(125f, 609f, 200L)
        timid.move(130f, 609f, 400L)
        assertNull(timid.up(420L))
    }

    @Test fun `finished gesture stops reporting active feedback`() {
        val gesture = NavigationGesture(NavigationEdge.RIGHT)
        gesture.down(200f, 100f, 0L)
        gesture.move(160f, 100f, 50L)
        assertEquals(NavigationAction.BACK, gesture.up(60L))

        assertFalse(gesture.progress(70L).active)
    }

    @Test fun `a finger stopped high up opens the switcher even without the hold timer`() {
        val gesture = NavigationGesture(NavigationEdge.BOTTOM)
        gesture.down(100f, 600f, 0L)
        gesture.move(100f, 400f, 100L)
        gesture.move(100f, 452f, 150L)
        gesture.move(100f, 452f, 210L)
        gesture.move(100f, 452f, 260L)
        assertEquals(NavigationAction.RECENTS, gesture.up(270L))
    }

    @Test fun `pulling back down cancels the gesture`() {
        val gesture = NavigationGesture(NavigationEdge.BOTTOM)
        gesture.down(100f, 600f, 0L)
        gesture.move(100f, 460f, 100L)
        gesture.move(100f, 500f, 160L)

        assertNull(gesture.up(170L))
        assertFalse(gesture.progress(180L).active)
    }

    @Test fun `bottom progress exposes travel, offsets and velocities`() {
        val gesture = NavigationGesture(NavigationEdge.BOTTOM)
        gesture.down(100f, 600f, 0L)
        gesture.move(130f, 540f, 100L)

        val progress = gesture.progress(100L)
        assertEquals(60f, progress.travel, 0f)
        assertEquals(600f, progress.velocity, .01f)
        assertEquals(30f, progress.dx, 0f)
        assertEquals(300f, progress.velocityX, .01f)
        assertEquals(1f, progress.distance, 0f)

        val side = NavigationGesture(NavigationEdge.LEFT)
        side.down(0f, 100f, 0L)
        side.move(20f, 100f, 50L)
        assertEquals(0f, side.progress(50L).travel, 0f)
        assertEquals(0f, side.progress(50L).dx, 0f)
    }

    @Test fun `recovery progress only accrues while the finger stays put`() {
        val gesture = NavigationGesture(NavigationEdge.BOTTOM)
        gesture.down(100f, 600f, 0L)
        assertEquals(.5f, gesture.progress(1_000L).recovery, .0001f)
        gesture.move(100f, 570f, 1_050L)
        assertEquals(0f, gesture.progress(1_100L).recovery, 0f)
        assertNull(gesture.tick(5_000L))
    }

    @Test fun `travel never goes negative when the finger drifts below the start`() {
        val gesture = NavigationGesture(NavigationEdge.BOTTOM)
        gesture.down(100f, 600f, 0L)
        gesture.move(100f, 620f, 50L)
        assertEquals(0f, gesture.progress(50L).travel, 0f)
        assertNull(gesture.up(60L))
    }

    @Test fun `thresholds scale with the panel height`() {
        // 70 dp is above the engage height on the 622 dp cover but below it on a tall 900 dp panel.
        val tall = NavigationGesture(NavigationEdge.BOTTOM)
        tall.down(100f, 880f, 0L, 425f, 900f)
        tall.move(100f, 810f, 100L)
        tall.tick(1_000L)
        assertFalse(tall.progress(1_000L).engaged)

        val cover = NavigationGesture(NavigationEdge.BOTTOM)
        cover.down(100f, 600f, 0L, 425f, 622f)
        cover.move(100f, 530f, 100L)
        cover.tick(1_000L)
        assertTrue(cover.progress(1_000L).engaged)
    }
}
