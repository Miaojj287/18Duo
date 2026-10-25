package com.jake.duolauncher

import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandbySweepTest {
    private val second = 1_700_000_000_000L // a whole wall-clock second

    @Test fun `the hand sweeps in twelve even steps per second and wraps at the minute`() {
        assertEquals(0f, standbySweepSecond(second - second % 60_000L, 0), 0f)
        val base = second - second % 60_000L + 41_000L
        var previous = standbySweepSecond(base, 0)
        assertEquals(41f, previous, 0f)
        for (ms in 1L until 1_000L) {
            val now = standbySweepSecond(base + ms, 0)
            assertTrue(now >= previous)
            assertEquals(41f + (ms * 12 / 1_000) / 12f, now, 1e-4f)
            previous = now
        }
        assertEquals(59f + 11f / 12f, standbySweepSecond(base + 18_999L, 0), 1e-4f)
        assertEquals(0f, standbySweepSecond(base + 19_000L, 0), 0f)
        // 0.5° per step on the dial.
        assertEquals(.5f, (standbySweepSecond(base + 84L, 0) - standbySweepSecond(base, 0)) * 6f, 1e-4f)
    }

    @Test fun `the zone offset only shifts whole minutes`() {
        assertEquals(standbySweepSecond(second + 250L, 0), standbySweepSecond(second + 250L, 8 * 3_600), 0f)
    }

    @Test fun `redraws never come closer than 50 ms so the clock never holds 120 Hz`() {
        // Simulate the loop: each wake-up lands on a step; the face's own frame is the whole second.
        var t = second + 3L
        val wakes = mutableListOf<Long>()
        repeat(240) { t += standbySweepDelayMillis(t); wakes += t }
        val frames = (wakes + (0..21).map { second + it * 1_000L + 8L }).sorted() // + face frames ~8 ms late
        frames.zipWithNext().forEach { (a, b) -> assertTrue("frames ${a - second} and ${b - second}", b - a > 50L) }
        // Eleven sweep steps per second plus the face's own frame: twelve hand positions every second.
        assertEquals(11, wakes.count { it in second + 5_000L until second + 6_000L })
        // Every wake-up is on or just after its step boundary, never before it.
        wakes.forEach { wake ->
            val within = wake % 1_000L
            assertTrue(within != 0L)
            assertTrue((within * 12) % 1_000L < 12L)
        }
    }

    @Test fun `a wake-up shows the step it was scheduled for`() {
        for (offset in 0L until 1_000L step 7L) {
            val wake = second + offset + standbySweepDelayMillis(second + offset)
            val before = standbySweepSecond(wake - 1, 0)
            assertTrue(standbySweepSecond(wake, 0) > before)
        }
    }

    @Test fun `only a time tracking the wall clock sweeps`() {
        val now = ZonedDateTime.parse("2026-09-09T14:46:26+08:00[Asia/Shanghai]")
        val wall = now.toInstant().toEpochMilli()
        assertTrue(standbyNowIsLive(now, wall + 900L))
        assertTrue(standbyNowIsLive(now, wall - 200L))
        assertFalse(standbyNowIsLive(now, wall + 86_400_000L)) // fixed preview / gallery time
    }
}
