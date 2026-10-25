package com.jake.duolauncher

import android.view.Surface
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

/** Round 24: inner-screen book pose StandBy (owner photo 1) and the cover's full-screen Analog dial (photo 4). */
class InnerStandbyPolicyTest {
    @Test fun `a book standing on a table faces the book rotation`() {
        assertEquals(INNER_BOOK_ROTATION, innerStandbyRotation(95f, .3f, 9.7f, .4f, hingeAlongX = false))
        assertEquals(Surface.ROTATION_90, innerStandbyRotation(95f, -.2f, -9.6f, .9f, hingeAlongX = false))
        assertEquals(INNER_BOOK_ROTATION, innerStandbyRotation(140f, 1f, 9.5f, -2f, hingeAlongX = false))
    }

    @Test fun `Xiaomi 18 Fold reads the book pose on X (phone log 2026-10-26)`() {
        assertEquals(INNER_BOOK_ROTATION, innerStandbyRotation(82f, -9.8f, .04f, .11f, hingeAlongX = true))
        assertEquals(Surface.ROTATION_90, innerStandbyRotation(72f, 9.72f, .005f, -.19f, hingeAlongX = true))
        assertNull(innerStandbyRotation(178f, 2.05f, .56f, 9.5f, hingeAlongX = true)) // open flat on the table
        assertNull(innerStandbyRotation(94f, .3f, 9.7f, .4f, hingeAlongX = true))
    }

    @Test fun `flat, laptop, closed or sideways poses are not a book`() {
        assertNull(innerStandbyRotation(95f, .1f, .2f, 9.8f, hingeAlongX = false)) // a half lying on the table, face up
        assertNull(innerStandbyRotation(95f, .1f, 6.9f, 6.9f, hingeAlongX = false)) // leaning back
        assertNull(innerStandbyRotation(170f, .1f, 9.8f, .1f, hingeAlongX = false)) // opened flat, held upright
        assertNull(innerStandbyRotation(30f, .1f, 9.8f, .1f, hingeAlongX = false)) // nearly closed
        assertNull(innerStandbyRotation(95f, 9.8f, .2f, .1f, hingeAlongX = false)) // hinge horizontal (tent / laptop on its side)
        assertNull(innerStandbyRotation(null, .1f, 9.8f, .1f, hingeAlongX = false))
        assertNull(innerStandbyRotation(95f, Float.NaN, 9.8f, .1f, hingeAlongX = false))
    }

    @Test fun `the book pose waits five seconds, stays until it is dismissed, and returns only after leaving the pose`() {
        val gate = StandbyEntryGate()
        assertFalse(gate.update(FoldPanel.INNER, true, true, 0L))
        assertFalse(gate.update(FoldPanel.INNER, true, true, 4_999L))
        assertTrue(gate.update(FoldPanel.INNER, true, true, 5_000L))
        gate.dismiss()
        assertFalse(gate.update(FoldPanel.INNER, true, true, 20_000L))
        assertFalse(gate.update(FoldPanel.INNER, false, false, 21_000L, upright = true))
        assertFalse(gate.update(FoldPanel.INNER, true, true, 22_000L))
        assertTrue(gate.update(FoldPanel.INNER, true, true, 27_000L))
    }

    @Test fun `tent dial figures sit on a rounded rectangle inside the screen`() {
        val a = 700f; val b = 400f
        assertEquals(0f, tentDialRing(0f, a, b).x, .01f)
        assertEquals(-b, tentDialRing(0f, a, b).y, .01f)
        assertEquals(a, tentDialRing(90f, a, b).x, .01f)
        for (hour in 1..12) {
            val p = tentDialRing(hour * 30f, a, b)
            assertTrue(kotlin.math.abs(p.x) <= a + .01f && kotlin.math.abs(p.y) <= b + .01f)
        }
        // Rounder than an ellipse: 2 o'clock sits further out than on the ellipse through the same axes.
        val two = tentDialRing(60f, a, b)
        assertTrue((two.x / a) * (two.x / a) + (two.y / b) * (two.y / b) > 1f)
    }

    @Test fun `book page dial fills the lower page and never overlaps the date block`() {
        val (center, side) = bookPageDial(1182f, 2000f)
        assertEquals(1182f * BookPage.DIAL_SIDE, side, .5f)
        // The Xiaomi inner's half page (1182 × 1672) is squatter than the Duo's: the dial takes the room below the date.
        val (_, inner) = bookPageDial(1182f, 1672f)
        assertEquals(1672f - 1182f * (BookPage.DIAL_TOP + BookPage.DIAL_BOTTOM), inner, .5f)
        assertTrue(center.y - side / 2f >= 1182f * BookPage.DIAL_TOP - .5f)
        assertTrue(center.y + side / 2f <= 2000f)
        val (_, short) = bookPageDial(1182f, 1300f)
        assertTrue(short < 1182f * BookPage.DIAL_SIDE)
    }

    @Test fun `lunar month and day without the year`() {
        // ICU is not available on the JVM; the helper only strips the cycle year from LunarDate's text.
        assertEquals("九月十七", LunarDate.text(43, 9, false, 17)!!.substringAfter("年"))
        assertNull(standbyLunarMonthDay(LocalDate.of(2026, 10, 26), Locale.US))
    }
}
