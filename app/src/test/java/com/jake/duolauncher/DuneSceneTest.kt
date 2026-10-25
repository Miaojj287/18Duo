package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DuneSceneTest {
    private val assets = File("src/main/assets/dunes")

    @Test fun `spring replays the site's steps`() {
        // Apple's Spring (x3GhYO92U1trXLEtspQS) from fold 1 to 0, replayed in evidence/r19 (Python).
        val spring = DuneFoldSpring()
        var x = 1f
        val expected = listOf(.76118f, .64402f, .521918f, .430314f, .352335f, .289291f)
        for (e in expected) { val (next, more) = spring.update(x, 0f); assertTrue(more); assertEquals(e, next, 2e-5f); x = next }
        var steps = expected.size
        while (true) { val (next, more) = spring.update(x, 0f); steps++; x = next; if (!more) break }
        assertEquals(45, steps); assertEquals(0f, x, 0f)
        // Non-finite input resets instead of propagating.
        assertEquals(.3f to false, DuneFoldSpring().update(Float.NaN, .3f))
    }

    @Test fun `motion steps once per sixtieth of a second whatever the display rate`() {
        val at60 = DuneFoldMotion(1f).apply { retarget(0f) }
        val at120 = DuneFoldMotion(1f).apply { retarget(0f) }
        repeat(30) { at60.advance(1.0 / 60) }
        repeat(60) { at120.advance(1.0 / 120) }
        assertEquals(at60.value, at120.value, 1e-6f)
        repeat(60) { at60.advance(1.0 / 60) }
        assertFalse(at60.moving); assertEquals(0f, at60.value, 0f)
        // A long stall catches up a quarter second at most.
        val stalled = DuneFoldMotion(0f).apply { retarget(1f) }
        stalled.advance(5.0)
        val reference = DuneFoldMotion(0f).apply { retarget(1f) }
        repeat(15) { reference.advance(1.0 / 60) }
        assertEquals(reference.value, stalled.value, 1e-6f)
    }

    @Test fun `fold target has Apple's dead zone`() {
        assertEquals(0f, DuneFoldMath.foldTarget(0f), 0f)
        assertEquals(0f, DuneFoldMath.foldTarget(.11f), 0f)
        assertEquals(.5f, DuneFoldMath.foldTarget(.555f), 1e-6f)
        assertEquals(1f, DuneFoldMath.foldTarget(1f), 0f)
        assertEquals(0f, DuneFoldMath.hingePosition(2f), 0f)
        assertEquals(1f, DuneFoldMath.hingePosition(176f), 0f)
        assertEquals(.5f, DuneFoldMath.hingePosition(87.5f), 1e-6f)
    }

    @Test fun `camera track reproduces the dumped samples`() {
        val track = DuneCameraTrack.parse(File(assets, "camera.bin").readBytes())
        val out = FloatArray(DuneCameraTrack.SAMPLE_FLOATS)
        track.sample(0f, out)
        // dump.json cams[0].proj[0] and close anchor translation
        assertEquals(3.664458f, out[DuneCameraTrack.PROJECTION], 1e-5f)
        assertEquals(4.1735f, out[DuneCameraTrack.CLOSE + 12], 1e-5f)
        track.sample(.5f, out)
        assertEquals(3.072992f, out[DuneCameraTrack.PROJECTION], 1e-5f)
        assertEquals(-.015f, out[DuneCameraTrack.PROJECTION + 9], 1e-6f)
        assertEquals(2.08675f, out[DuneCameraTrack.CLOSE + 12], 1e-5f)
        val a = FloatArray(64); val b = FloatArray(64); val mid = FloatArray(64)
        track.sample(.5f, a); track.sample(.51f, b); track.sample(.505f, mid)
        for (k in 0 until 64) assertEquals((a[k] + b[k]) / 2f, mid[k], 1e-5f)
    }

    @Test fun `view matrix inverts the camera`() {
        val track = DuneCameraTrack.parse(File(assets, "camera.bin").readBytes())
        val cam = FloatArray(64); val view = FloatArray(16); val product = FloatArray(16)
        for (f in listOf(0f, .37f, 1f)) {
            track.sample(f, cam)
            DuneMatrix.invert(cam, DuneCameraTrack.WORLD, view)
            DuneMatrix.multiply(view, 0, cam, DuneCameraTrack.WORLD, product)
            for (c in 0..3) for (r in 0..3) assertEquals(if (c == r) 1f else 0f, product[c * 4 + r], 1e-4f)
        }
        val n = FloatArray(9)
        DuneMatrix.normalMatrix(floatArrayOf(2f, 0f, 0f, 0f, 0f, 4f, 0f, 0f, 0f, 0f, 1f, 0f, 5f, 6f, 7f, 1f), n)
        assertArrayEquals(floatArrayOf(.5f, 0f, 0f, 0f, .25f, 0f, 0f, 0f, 1f), n, 1e-6f)
    }

    @Test fun `windows show a centred crop of Apple's target`() {
        val p = FloatArray(16) { 0f }.apply { this[0] = 2f; this[5] = 3f; this[8] = .1f; this[9] = .2f; this[11] = -1f }
        val cover = p.copyOf(); DuneFoldMath.cropProjection(cover, 1168f / 1712f)
        val s = DuneFoldMath.SCENE_ASPECT / (1168f / 1712f)
        assertEquals(2f * s, cover[0], 1e-5f); assertEquals(.1f * s, cover[8], 1e-5f); assertEquals(3f, cover[5], 0f)
        val wide = p.copyOf(); DuneFoldMath.cropProjection(wide, 2f)
        assertEquals(2f, wide[0], 0f); assertEquals(3f * 2f / DuneFoldMath.SCENE_ASPECT, wide[5], 1e-5f)
        val same = p.copyOf(); DuneFoldMath.cropProjection(same, DuneFoldMath.SCENE_ASPECT)
        assertArrayEquals(p, same, 1e-6f)
    }

    @Test fun `assets match the manifest`() {
        // android.jar's org.json is a stub in local tests: read the draw keys with a pattern.
        val manifest = File(assets, "scene.json").readText()
        val keys = Regex("\"key\": \"(\\w+)\"").findAll(manifest).map { it.groupValues[1] }.toList()
        assertEquals(listOf("sky", "stars", "hills", "duneBack", "duneFront"), keys)
        for (key in keys) {
            assertTrue(File(assets, "$key.vert").readText().startsWith("#version 300 es"))
            assertTrue(File(assets, "$key.frag").readText().startsWith("#version 300 es"))
            val file = File(assets, "$key.mesh")
            val mesh = java.nio.ByteBuffer.wrap(file.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val v = mesh.int; val n = mesh.int
            assertEquals(8L + v * 32L + n * 2L, file.length())
        }
        for (file in Regex("\"file\": \"([^\"]+)\"").findAll(manifest).map { it.groupValues[1] }) assertTrue(file, File(assets, file).isFile)
        assertTrue(File(assets, "fold0.webp").isFile)
    }
}
