package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavSpringTest {
    private fun run(spring: NavSpring, seconds: Float, frame: Float = 1f / 120f): List<Float> {
        val values = ArrayList<Float>()
        var t = 0f
        while (t < seconds) { spring.step(frame); values += spring.value; t += frame }
        return values
    }

    @Test fun `critically damped spring settles on target without overshoot`() {
        val spring = NavSpring(0f, .5f, 1f).thresholds(.001f)
        spring.animateTo(1f)
        val values = run(spring, 1.5f)
        assertTrue(values.all { it <= 1.0005f })
        assertTrue(spring.settled)
        assertEquals(1f, spring.value, 0f)
        // SwiftUI-style response: well on its way after one response period.
        assertTrue(values[(.5f * 120).toInt()] > .9f)
    }

    @Test fun `under-damped spring overshoots a little like iOS bouncy springs`() {
        val spring = NavSpring(0f, .4f, .7f).thresholds(.001f)
        spring.animateTo(1f)
        val values = run(spring, 2f)
        assertTrue(values.max() > 1.02f)
        assertEquals(1f, spring.value, .001f)
    }

    @Test fun `velocity carries over so a flicked card keeps its momentum`() {
        val still = NavSpring(0f, .45f, 1f)
        still.animateTo(100f)
        still.step(1f / 60f)
        val flicked = NavSpring(0f, .45f, 1f)
        flicked.animateTo(100f, initialVelocity = 2_000f)
        flicked.step(1f / 60f)
        assertTrue(flicked.value > still.value + 10f)
    }

    @Test fun `snap jumps and stops`() {
        val spring = NavSpring(0f)
        spring.animateTo(5f, 30f)
        spring.snap(2f)
        assertTrue(spring.settled)
        assertFalse(spring.step(1f / 60f))
        assertEquals(2f, spring.value, 0f)
    }

    @Test fun `long frames are sub-stepped and stay stable`() {
        val spring = NavSpring(0f, .1f, .5f).thresholds(.001f)
        spring.animateTo(1f)
        repeat(60) { spring.step(.2f) }
        assertEquals(1f, spring.value, .01f)
    }

    @Test fun `rect spring moves centre and size together`() {
        val from = ZoomBounds(0f, 0f, 400f, 600f)
        val to = ZoomBounds(100f, 100f, 160f, 160f)
        val rect = RectSpring(from, .4f, 1f)
        rect.animateTo(to)
        repeat(240) { rect.step(1f / 120f) }
        assertTrue(rect.settled)
        assertEquals(to.left, rect.bounds.left, .5f)
        assertEquals(to.bottom, rect.bounds.bottom, .5f)
    }
}
