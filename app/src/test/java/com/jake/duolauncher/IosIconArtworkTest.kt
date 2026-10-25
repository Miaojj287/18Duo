package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.hypot

class IosIconArtworkTest {
    private val size = 144
    private val opaqueBlue = 0xFF2060E0.toInt()

    private fun mask(inside: (x: Float, y: Float) -> Boolean): IntArray = IntArray(size * size) { i ->
        val x = i % size + .5f; val y = i / size + .5f
        if (inside(x, y)) opaqueBlue else 0
    }

    private fun roundedSquare(left: Float, top: Float, side: Float, radius: Float) = mask { x, y ->
        val cx = x.coerceIn(left + radius, left + side - radius)
        val cy = y.coerceIn(top + radius, top + side - radius)
        x in left..left + side && y in top..top + side && hypot(x - cx, y - cy) <= radius
    }

    @Test fun opaque_square_artwork_is_full_bleed() {
        val art = classifyIconArtwork(IntArray(size * size) { opaqueBlue }, size, size)
        assertEquals(IconArtworkKind.FullBleed, art.kind)
        assertEquals(opaqueBlue, art.rimColor)
    }

    @Test fun pre_masked_rounded_tiles_are_cropped_to_fill_the_squircle() {
        // An OEM-themed icon: 128 px rounded square, 30 % corners, 8 px transparent margin.
        val art = classifyIconArtwork(roundedSquare(8f, 8f, 128f, 38f), size, size)
        assertEquals(IconArtworkKind.Tile, art.kind)
        assertEquals(8, art.left); assertEquals(8, art.top)
        assertEquals(136, art.right); assertEquals(136, art.bottom)
        assertEquals(opaqueBlue, art.rimColor)
    }

    @Test fun round_legacy_icons_go_on_a_white_tile() {
        val art = classifyIconArtwork(mask { x, y -> hypot(x - 72f, y - 72f) <= 66f }, size, size)
        assertEquals(IconArtworkKind.Glyph, art.kind)
    }

    @Test fun small_or_irregular_glyphs_go_on_a_white_tile() {
        // A small square glyph is "solid" but too small to be a tile.
        assertEquals(IconArtworkKind.Glyph, classifyIconArtwork(roundedSquare(52f, 52f, 40f, 4f), size, size).kind)
        // A tall robot-like shape is not square enough.
        val tall = mask { x, y -> x in 40f..104f && y in 8f..136f }
        assertEquals(IconArtworkKind.Glyph, classifyIconArtwork(tall, size, size).kind)
    }

    @Test fun empty_artwork_does_not_crash() {
        val art = classifyIconArtwork(IntArray(size * size), size, size)
        assertEquals(IconArtworkKind.Glyph, art.kind)
        assertEquals(size, art.right)
    }
}
