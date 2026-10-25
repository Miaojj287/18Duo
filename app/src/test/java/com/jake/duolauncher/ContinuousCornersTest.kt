package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

class ContinuousCornersTest {
    private sealed interface Cmd
    private data class Move(val x: Float, val y: Float) : Cmd
    private data class Line(val x: Float, val y: Float) : Cmd
    private data class Cubic(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x3: Float, val y3: Float) : Cmd
    private object Close : Cmd

    private class Recorder : CurveSink {
        val commands = mutableListOf<Cmd>()
        override fun moveTo(x: Float, y: Float) { commands += Move(x, y) }
        override fun lineTo(x: Float, y: Float) { commands += Line(x, y) }
        override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            commands += Cubic(x1, y1, x2, y2, x3, y3)
        }
        override fun close() { commands += Close }

        /** Dense polyline of the outline. */
        fun samples(perCubic: Int = 24): List<Pair<Float, Float>> {
            val out = mutableListOf<Pair<Float, Float>>()
            var cx = 0f; var cy = 0f
            for (c in commands) when (c) {
                is Move -> { cx = c.x; cy = c.y; out += cx to cy }
                is Line -> { cx = c.x; cy = c.y; out += cx to cy }
                is Cubic -> {
                    for (i in 1..perCubic) {
                        val t = i / perCubic.toFloat(); val u = 1 - t
                        val x = u * u * u * cx + 3 * u * u * t * c.x1 + 3 * u * t * t * c.x2 + t * t * t * c.x3
                        val y = u * u * u * cy + 3 * u * u * t * c.y1 + 3 * u * t * t * c.y2 + t * t * t * c.y3
                        out += x to y
                    }
                    cx = c.x3; cy = c.y3
                }
                Close -> Unit
            }
            return out
        }
    }

    private fun record(w: Float, h: Float, r: Float, smoothing: Float = IOS_CORNER_SMOOTHING) = Recorder().also {
        continuousRoundedRect(0f, 0f, w, h, floatArrayOf(r, r, r, r), smoothing, it)
    }

    private fun area(points: List<Pair<Float, Float>>): Float {
        var sum = 0.0
        for (i in points.indices) {
            val (x1, y1) = points[i]; val (x2, y2) = points[(i + 1) % points.size]
            sum += x1.toDouble() * y2 - x2.toDouble() * y1
        }
        return abs(sum / 2).toFloat()
    }

    @Test fun ios_icon_corner_uses_figma_sixty_percent_extent() {
        val size = 144f
        val corner = smoothCorner(size * IOS_ICON_CORNER_FRACTION, IOS_CORNER_SMOOTHING, size / 2f)
        assertEquals(.6f, corner.smoothing, 1e-6f)
        assertEquals(1.6f * size * IOS_ICON_CORNER_FRACTION, corner.extent, 1e-3f)
        assertEquals(36f, corner.arcDegrees, 1e-4f)
        assertTrue(corner.a > corner.b && corner.b > 0f)
    }

    @Test fun outline_stays_inside_bounds_and_touches_every_edge() {
        val w = 160f; val h = 90f
        val points = record(w, h, 30f).samples()
        assertTrue(points.all { (x, y) -> x in -1e-3f..w + 1e-3f && y in -1e-3f..h + 1e-3f })
        assertEquals(0f, points.minOf { it.first }, 1e-3f)
        assertEquals(w, points.maxOf { it.first }, 1e-3f)
        assertEquals(0f, points.minOf { it.second }, 1e-3f)
        assertEquals(h, points.maxOf { it.second }, 1e-3f)
    }

    @Test fun equal_radii_give_a_shape_symmetric_about_both_axes() {
        val w = 120f; val h = 200f
        val points = record(w, h, 26f).samples(48)
        fun nearest(x: Float, y: Float) = points.minOf { (px, py) -> hypot(px - x, py - y) }
        points.forEach { (x, y) ->
            assertTrue(nearest(w - x, y) < .35f)
            assertTrue(nearest(x, h - y) < .35f)
        }
    }

    @Test fun arc_section_lies_on_the_corner_circle_with_matching_tangents() {
        val w = 100f; val r = 22.4f
        val commands = record(w, w, r).commands
        // Top-right corner: entry cubic, arc cubic, exit cubic follow the first line.
        val entry = commands[2] as Cubic
        val arc = commands[3] as Cubic
        val exit = commands[4] as Cubic
        val cx = w - r; val cy = r
        assertEquals(r, hypot(entry.x3 - cx, entry.y3 - cy), 1e-3f)
        assertEquals(r, hypot(arc.x3 - cx, arc.y3 - cy), 1e-3f)
        assertEquals(w, exit.x3, 1e-4f)
        assertEquals(1.6f * r, exit.y3, 1e-3f)
        // G1: the entry cubic's last tangent equals the arc's first tangent.
        val entryAngle = atan2(entry.y3 - entry.y2, entry.x3 - entry.x2)
        val arcAngle = atan2(arc.y1 - entry.y3, arc.x1 - entry.x3)
        assertEquals(entryAngle, arcAngle, 1e-3f)
        assertEquals(.6 * PI / 4, entryAngle.toDouble(), 1e-3)
    }

    @Test fun continuous_corner_removes_more_area_than_a_circular_corner_of_the_same_radius() {
        val s = 144f; val r = s * IOS_ICON_CORNER_FRACTION
        val squircle = area(record(s, s, r).samples(64))
        val circular = s * s - (4 - PI.toFloat()) * r * r
        val looser = s * s - (4 - PI.toFloat()) * (1.6f * r) * (1.6f * r)
        assertTrue(squircle < circular)
        assertTrue(squircle > looser)
    }

    @Test fun degenerate_sizes_emit_nothing_and_zero_radius_is_a_rectangle() {
        assertTrue(record(0f, 50f, 10f).commands.isEmpty())
        assertTrue(record(50f, -1f, 10f).commands.isEmpty())
        assertTrue(record(Float.NaN, 50f, 10f).commands.isEmpty())
        val rect = record(40f, 30f, 0f).commands
        assertTrue(rect.none { it is Cubic })
        assertEquals(listOf(Move(0f, 0f), Line(40f, 0f), Line(40f, 30f), Line(0f, 30f), Line(0f, 0f), Close), rect)
    }

    @Test fun radius_at_half_the_side_degrades_to_a_true_circle() {
        val corner = smoothCorner(radius = 30f, smoothing = IOS_CORNER_SMOOTHING, budget = 30f)
        assertEquals(0f, corner.smoothing, 0f)
        assertEquals(90f, corner.arcDegrees, 0f)
        val points = record(60f, 60f, 30f).samples(64)
        points.forEach { (x, y) -> assertEquals(30f, hypot(x - 30f, y - 30f), .05f) }
        // Too-large radii are clamped instead of overlapping.
        assertEquals(30f, smoothCorner(80f, .6f, 30f).radius, 0f)
    }

    @Test fun tight_shapes_trade_smoothing_for_room() {
        val corner = smoothCorner(radius = 24f, smoothing = .6f, budget = 30f)
        assertEquals(.25f, corner.smoothing, 1e-4f)
        assertEquals(30f, corner.extent, 1e-3f)
        assertTrue(record(61f, 250f, 24f).samples().all { (x, _) -> x in -1e-3f..61.001f })
    }
}
