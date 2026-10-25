package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class GlassProjectionMathTest {
    @Test fun `inner tilt is zero across the flat jitter band and saturates at perpendicular`() {
        for (angle in listOf(180f, 176f, 174f, 172f)) assertEquals(0f, GlassProjectionMath.innerTilt(angle), 0f)
        assertEquals(2f, GlassProjectionMath.innerTilt(170f), 1e-5f)
        assertEquals(52f, GlassProjectionMath.innerTilt(120f), 1e-5f)
        assertEquals(90f, GlassProjectionMath.innerTilt(82f), 1e-5f)
        assertEquals(90f, GlassProjectionMath.innerTilt(0f), 0f)
        assertEquals(0f, GlassProjectionMath.innerTilt(Float.NaN), 0f)
        val values = (0..180).map { GlassProjectionMath.innerTilt(it.toFloat()) }
        assertTrue(values.zipWithNext().all { (a, b) -> b <= a })
    }
    @Test fun `cover tilt starts past the closed jitter band and grows with the opening`() {
        for (angle in listOf(0f, 1f, 3f)) assertEquals(0f, GlassProjectionMath.coverTilt(angle), 0f)
        assertEquals(27f, GlassProjectionMath.coverTilt(30f), 1e-5f)
        assertEquals(90f, GlassProjectionMath.coverTilt(93f), 1e-5f)
        assertEquals(90f, GlassProjectionMath.coverTilt(180f), 0f)
        assertEquals(0f, GlassProjectionMath.coverTilt(Float.NaN), 0f)
    }
    @Test fun `reveal and cover progress map back to the same hinge degrees`() {
        assertEquals(0f, innerTiltFromReveal(1f), 0f)
        assertEquals(0f, innerTiltFromReveal(.999f), 0f)
        assertEquals(GlassProjectionMath.innerTilt(150f), innerTiltFromReveal(hingeReveal(150f)), 1e-4f)
        assertEquals(90f, innerTiltFromReveal(0f), 0f)
        assertEquals(0f, coverTiltFromFold(0f), 0f)
        assertEquals(90f, coverTiltFromFold(1f), 0f)
        assertEquals(GlassProjectionMath.coverTilt(60f), coverTiltFromFold(coverFoldProgress(hingeReveal(60f))), 1e-3f)
    }
    @Test fun `wipe uniforms follow the viewer's keyframes`() {
        // evidence/r19/web/wipe-log.json: (slider, inner wipeAmount, inner brightness, cover wipeAmount)
        val log = listOf(
            listOf(0f, 1f, .14986f, 0f), listOf(.11f, 1f, .18302f, .11011f), listOf(.2f, .95988f, .21003f, .2001f),
            listOf(.333f, .80025f, .24994f, .33313f), listOf(.45f, .65986f, .38139f, .45012f), listOf(.6f, .47983f, .55016f, .39985f),
            listOf(.75f, .29983f, .71891f, .24985f), listOf(.9f, .11983f, .88766f, .09985f), listOf(1f, 0f, 1f, 0f))
        for ((h, innerAmount, innerBrightness, coverAmount) in log) {
            assertEquals(innerAmount, GlassProjectionMath.wipeAmount(h, inner = true), 1e-3f)
            assertEquals(innerBrightness, GlassProjectionMath.brightness(h, inner = true), 1e-3f)
            assertEquals(coverAmount, GlassProjectionMath.wipeAmount(h, inner = false), 1e-3f)
            assertEquals(1f, GlassProjectionMath.brightness(h, inner = false), 0f)
        }
    }
    @Test fun `window lookup follows the viewer's camera and cover rest blend`() {
        // wipe-log.json transitionToCameraRest for the cover at slider .45 .6 .75 .9; 1 at both endpoints.
        for ((h, t) in listOf(.45f to 0f, .6f to .18288f, .75f to .56839f, .9f to .91308f, 0f to 1f, 1f to 1f))
            assertEquals(t, GlassProjectionMath.restMix(h, inner = false), 1e-3f)
        assertEquals(0f, GlassProjectionMath.restMix(.3f, inner = true), 0f)
        // apple-geom.json: the camera sits at x = 4(1 - h) in the phone's frame (2.001 at 90 degrees).
        assertEquals(0f, GlassProjectionMath.cameraX(1f), 0f); assertEquals(4f, GlassProjectionMath.cameraX(0f), 0f)
        assertEquals(2f, GlassProjectionMath.cameraX(.5f), 1e-6f)
        assertEquals(90f, GlassProjectionMath.hingeDegrees(.5f), 1e-4f); assertEquals(180f, GlassProjectionMath.hingeDegrees(Float.NaN), 0f)
        assertEquals(0f, GlassProjectionMath.contentOffset(.5f, inner = true), 0f)
        assertEquals(.23f * .5f, GlassProjectionMath.contentOffset(.5f, inner = false), 1e-6f)
        // Native frame at rest, Apple's scale once the fold is under way.
        assertEquals(1f, GlassProjectionMath.identityMix(1f, inner = true), 0f); assertEquals(0f, GlassProjectionMath.identityMix(.9f, inner = true), 0f)
        assertEquals(1f, GlassProjectionMath.identityMix(0f, inner = false), 0f); assertEquals(0f, GlassProjectionMath.identityMix(.1f, inner = false), 0f)
    }
    @Test fun `at rest the wipe leaves every pixel alone`() {
        assertFalse(GlassProjectionMath.active(1f, inner = true))
        assertFalse(GlassProjectionMath.active(0f, inner = false)); assertFalse(GlassProjectionMath.active(1f, inner = false))
        assertFalse(GlassProjectionMath.active(Float.NaN, inner = true))
        assertTrue(GlassProjectionMath.active(.99f, inner = true)); assertTrue(GlassProjectionMath.active(.02f, inner = false))
        for (d in listOf(0f, .3f, .5f, .8f, 1f)) {
            assertEquals(1f, GlassProjectionMath.pointLight(d, 1f, inner = true), 1e-6f)
            assertEquals(1f, GlassProjectionMath.pointLight(d, 0f, inner = false), 1e-6f)
            assertEquals(0f, GlassProjectionMath.blurArea(d, 0f, inner = true), 0f)
        }
        assertEquals(0f, GlassProjectionMath.blurSigmaPx(0f, 1.5f), 0f)
    }
    @Test fun `inner blur starts inside the held half and is widest at the free edge`() {
        val amount = GlassProjectionMath.wipeAmount(.5f, inner = true)
        // d = .45 lies just inside the held half (the hinge is d = .5); nothing nearer the far edge blurs.
        for (d in listOf(0f, .2f, .45f)) assertEquals(0f, GlassProjectionMath.blurArea(d, amount, inner = true), 0f)
        assertTrue(GlassProjectionMath.blurArea(.5f, amount, inner = true) > 0f)
        val areas = (45..100).map { GlassProjectionMath.blurArea(it / 100f, amount, inner = true) }
        assertTrue(areas.zipWithNext().all { (a, b) -> b >= a })
        // Folded past 30°: the free edge reaches LOD 9.24 (mips 9 and 10 mixed), ≈ .38 of the long side.
        val texel = 2364f / GlassProjectionMath.INNER_TEXELS
        val widest = GlassProjectionMath.blurSigmaPx(8f * GlassProjectionMath.blurArea(1f, 1f, inner = true), texel)
        assertEquals(.382f * 2364f, widest, 12f)
        // Light falls from the held half to the free edge, which goes dark once the wipe is under way.
        val lights = (0..100).map { GlassProjectionMath.pointLight(it / 100f, .5f, inner = true) }
        assertTrue(lights.zipWithNext().all { (a, b) -> b <= a + 1e-6f })
        assertEquals(0f, lights.last(), 1e-6f)
        assertEquals(GlassProjectionMath.light(.5f, inner = true), lights.first(), 1e-6f)
    }
    @Test fun `cover wipe is a tent that keeps the hinge edge crisp`() {
        val amounts = (0..100).map { GlassProjectionMath.wipeAmount(it / 100f, inner = false) }
        assertEquals(.5f, amounts.max(), 1e-6f); assertEquals(50, amounts.indexOf(amounts.max()))
        val amount = GlassProjectionMath.wipeAmount(.5f, inner = false)
        assertEquals(0f, GlassProjectionMath.blurArea(0f, amount, inner = false), 0f)
        assertEquals(1f, GlassProjectionMath.pointLight(0f, .5f, inner = false), 1e-6f)
        assertTrue(GlassProjectionMath.pointLight(.9f, .5f, inner = false) < .1f)
        assertEquals(0f, GlassProjectionMath.edgeAmount(.5f, inner = false), 0f)
    }
    @Test fun `mip variance matches a B-spline fetch from box filtered levels`() {
        assertEquals(1f / 3f, GlassProjectionMath.mipVariance(0f), 1e-6f)
        for (l in 1..9) assertEquals((5f * 4f.pow(l) - 1f) / 12f, GlassProjectionMath.mipVariance(l.toFloat()), 1e-2f)
        val sigmas = (0..92).map { GlassProjectionMath.blurSigmaPx(it / 10f, 1f) }
        assertTrue(sigmas.zipWithNext().all { (a, b) -> b >= a })
    }
    @Test fun `hinge position comes back from reveal and cover progress`() {
        assertEquals(1f, innerHingeFromReveal(1f), 0f); assertEquals(0f, innerHingeFromReveal(0f), 0f)
        assertEquals(DuneFoldMath.hingePosition(90f), innerHingeFromReveal(hingeReveal(90f)), 1e-4f)
        assertEquals(0f, coverHingeFromFold(0f), 0f)
        assertEquals(DuneFoldMath.hingePosition(60f), coverHingeFromFold(coverFoldProgress(hingeReveal(60f))), 1e-3f)
        assertEquals(DuneFoldMath.hingePosition(1f + 174f * .68f), coverHingeFromFold(1f), 1e-5f)
    }
    @Test fun `photo wallpaper zoom is one at rest`() {
        assertEquals(1f, GlassProjectionMath.wallpaperZoom(0f), 0f)
        assertEquals(1f + GlassProjectionMath.WALLPAPER_ZOOM, GlassProjectionMath.wallpaperZoom(90f), 1e-6f)
    }
    @Test fun `leaf geometry follows the window shape`() {
        val landscape = FoldLeafGeometry.forWindow(2364f, 1672f, inner = true)
        assertEquals(1182f, landscape.hingeX, 0f); assertEquals(-1f, landscape.dirX, 0f); assertEquals(1182f, landscape.lengthPx, 0f)
        assertEquals(2364f, landscape.foldLengthPx(inner = true), 0f)
        val portrait = FoldLeafGeometry.forWindow(1672f, 2364f, inner = true)
        assertEquals(1182f, portrait.hingeY, 0f); assertEquals(-1f, portrait.dirY, 0f); assertEquals(0f, portrait.dirX, 0f)
        val cover = FoldLeafGeometry.forWindow(1168f, 1712f, inner = false)
        assertEquals(0f, cover.hingeX, 0f); assertEquals(1f, cover.dirX, 0f); assertEquals(1168f, cover.lengthPx, 0f)
    }
    @Test fun `cover hinge edge follows the physical hinge under every window rotation`() {
        // ROTATION_90 = device turned counter-clockwise: the natural left edge (hinge) is now at the bottom.
        val r0 = android.view.Surface.ROTATION_0; val r90 = android.view.Surface.ROTATION_90
        val r180 = android.view.Surface.ROTATION_180; val r270 = android.view.Surface.ROTATION_270
        assertEquals(HingeEdge.LEFT, coverHingeEdge(r0)); assertEquals(HingeEdge.BOTTOM, coverHingeEdge(r90))
        assertEquals(HingeEdge.RIGHT, coverHingeEdge(r180)); assertEquals(HingeEdge.TOP, coverHingeEdge(r270))
        // Portrait window at 0/180, the inherited landscape window (1712 × 1168) at 90/270.
        val left = FoldLeafGeometry.forWindow(1168f, 1712f, inner = false, rotation = r0)
        assertEquals(HingeEdge.LEFT, left.hingeEdge)
        assertEquals(0f, left.hingeX, 0f); assertEquals(1f, left.dirX, 0f); assertEquals(0f, left.dirY, 0f); assertEquals(1168f, left.lengthPx, 0f)
        val bottom = FoldLeafGeometry.forWindow(1712f, 1168f, inner = false, rotation = r90)
        assertEquals(HingeEdge.BOTTOM, bottom.hingeEdge)
        assertEquals(1168f, bottom.hingeY, 0f); assertEquals(-1f, bottom.dirY, 0f); assertEquals(0f, bottom.dirX, 0f); assertEquals(1168f, bottom.lengthPx, 0f)
        val right = FoldLeafGeometry.forWindow(1168f, 1712f, inner = false, rotation = r180)
        assertEquals(HingeEdge.RIGHT, right.hingeEdge)
        assertEquals(1168f, right.hingeX, 0f); assertEquals(-1f, right.dirX, 0f); assertEquals(1168f, right.lengthPx, 0f)
        val top = FoldLeafGeometry.forWindow(1712f, 1168f, inner = false, rotation = r270)
        assertEquals(HingeEdge.TOP, top.hingeEdge)
        assertEquals(0f, top.hingeY, 0f); assertEquals(1f, top.dirY, 0f); assertEquals(1168f, top.lengthPx, 0f)
        // Every variant spans the whole window from its hinge edge: along = dot(p - hinge, dir) covers [0, length].
        for ((leaf, w, h) in listOf(Triple(left, 1168f, 1712f), Triple(bottom, 1712f, 1168f), Triple(right, 1168f, 1712f), Triple(top, 1712f, 1168f))) {
            val corners = listOf(0f to 0f, w to 0f, 0f to h, w to h)
            val along = corners.map { (x, y) -> (x - leaf.hingeX) * leaf.dirX + (y - leaf.hingeY) * leaf.dirY }
            assertEquals(0f, along.min(), 1e-3f); assertEquals(leaf.lengthPx, along.max(), 1e-3f)
            assertEquals(leaf.lengthPx, leaf.foldLengthPx(inner = false), 0f)
        }
    }
    @Test fun `inner hinge bisects the long side and the rotation names the same axis on lhasa`() {
        // lhasa's inner panel is natively 1672 × 2364 (portrait, hinge horizontal); the owner's everyday
        // ROTATION_270 window is 2364 × 1672 with a vertical hinge. The aspect rule and the rotation rule agree.
        val r0 = android.view.Surface.ROTATION_0; val r90 = android.view.Surface.ROTATION_90
        val r180 = android.view.Surface.ROTATION_180; val r270 = android.view.Surface.ROTATION_270
        for (rotation in listOf(r90, r270)) {
            assertTrue(innerHingeVertical(rotation))
            val leaf = FoldLeafGeometry.forWindow(2364f, 1672f, inner = true, rotation = rotation)
            assertEquals(1182f, leaf.hingeX, 0f); assertEquals(-1f, leaf.dirX, 0f); assertEquals(0f, leaf.dirY, 0f)
            assertEquals(HingeEdge.RIGHT, leaf.hingeEdge) // the left half tilts, in the user's frame
        }
        for (rotation in listOf(r0, r180)) {
            assertFalse(innerHingeVertical(rotation))
            val leaf = FoldLeafGeometry.forWindow(1672f, 2364f, inner = true, rotation = rotation)
            assertEquals(1182f, leaf.hingeY, 0f); assertEquals(-1f, leaf.dirY, 0f); assertEquals(0f, leaf.dirX, 0f)
            assertEquals(HingeEdge.BOTTOM, leaf.hingeEdge) // the top half tilts, the bottom half is held
        }
    }
    @Test fun `angle follower is frame rate independent bounded and reverses immediately`() {
        for(inner in listOf(false,true)) {
            val sixty=(1..6).fold(40f) { current,_->GlassProjectionMath.followAngle(current,60f,16,inner,0f) }
            val oneTwenty=(1..12).fold(40f) { current,_->GlassProjectionMath.followAngle(current,60f,8,inner,0f) }
            assertEquals(sixty,oneTwenty,.0001f)
            val follower=GlassAngleFollow(); follower.reset(0,40f)
            val opening=follower.update(16,40f,60f,inner)
            assertTrue(opening in 40f..60f)
            val closing=follower.update(32,opening,20f,inner)
            assertTrue(closing>=20f && closing<opening)
            assertEquals(closing,follower.update(48,closing,Float.NaN,inner),0f)
        }
    }
    @Test fun `pyramid blends variance rather than radius or display gamma`() {
        for(level in 0 until GlassProjectionMath.LEVELS-1) {
            val lo=GlassProjectionMath.levelVariance(level); val hi=GlassProjectionMath.levelVariance(level+1)
            val midpoint=GlassProjectionMath.levelMix(sqrt((lo+hi)/2))
            assertEquals(level,midpoint.low); assertEquals(level+1,midpoint.high)
            assertEquals(.5f,midpoint.weight,1e-5f)
        }
        assertEquals(0,GlassProjectionMath.levelMix(0f).low)
        assertEquals(1f,GlassProjectionMath.levelMix(0f).sharp,0f)
        assertEquals(0f,GlassProjectionMath.levelMix(2f).sharp,0f)
        assertEquals(GlassProjectionMath.LEVELS-1,GlassProjectionMath.levelMix(1e6f).high)
        assertEquals(0,GlassProjectionMath.levelMix(Float.NaN).low)
    }

    @Test fun `round 20 pyramid matches its measured impulse variances and reaches Apple's widest blur`() {
        // evidence/r20/tools/blur_sim2.py ('newh'): impulse variance of each level as fetched.
        val measured=floatArrayOf(1.33f,3.19f,12.75f,63.41f,266.08f,1076.72f,4319.31f,17289.64f,69170.96f,276696.25f,1106797.4f)
        assertEquals(measured.size+1,GlassProjectionMath.LEVELS)
        for(i in measured.indices) assertEquals(measured[i],GlassProjectionMath.levelVariance(i),measured[i]*.01f)
        // No level sits more than ~5x above the one below it (Core.35.1 jumped 38x from 1.3 to 51 px²).
        for(i in 1 until GlassProjectionMath.LEVELS)
            assertTrue(GlassProjectionMath.levelVariance(i)/GlassProjectionMath.levelVariance(i-1) < 5.2f)
        for(inner in listOf(true,false)) {
            val texel=GlassProjectionMath.texelPx(if(inner)2364f else 1168f,inner)
            val widest=GlassProjectionMath.blurSigmaPx(GlassProjectionMath.MAX_LOD,texel)
            assertTrue(widest*widest <= GlassProjectionMath.levelVariance(GlassProjectionMath.LEVELS-1))
        }
    }
}
