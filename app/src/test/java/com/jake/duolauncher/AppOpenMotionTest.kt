package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppOpenMotionTest {
    private val screenW = 1168f
    private val screenH = 1712f
    private val icon = ZoomBounds(100f, 300f, 260f, 460f)

    @Before fun reset() { AppOpenZoomController.cancel() }

    @Test fun `progress zero leaves the icon and home untouched`() {
        val t = AppOpenMotion.transform(0f, icon, screenW, screenH)
        assertEquals(100f, t.iconLeft, .0001f)
        assertEquals(300f, t.iconTop, .0001f)
        assertEquals(160f, t.iconWidth, .0001f)
        assertEquals(160f, t.iconHeight, .0001f)
        assertEquals(1f, t.iconAlpha, 0f)
        assertEquals(1f, t.contentScale, 0f)
        assertEquals(1f, t.contentAlpha, 0f)
        assertEquals(1f, t.wallpaperScale, 0f)
        assertEquals(160f * AppOpenMotion.ICON_CORNER_FRACTION, t.iconCornerRadius, .0001f)
    }

    @Test fun `progress one covers the whole screen centred while home has zoomed away`() {
        val t = AppOpenMotion.transform(1f, icon, screenW, screenH)
        assertTrue(t.iconWidth >= screenW - .01f)
        assertTrue(t.iconHeight >= screenH - .01f)
        assertEquals(screenW / 2f, t.iconLeft + t.iconWidth / 2f, .01f)
        assertEquals(screenH / 2f, t.iconTop + t.iconHeight / 2f, .01f)
        assertEquals(0f, t.iconAlpha, 0f)
        assertEquals(AppOpenMotion.CONTENT_ZOOM, t.contentScale, .0001f)
        assertEquals(0f, t.contentAlpha, 0f)
        assertEquals(AppOpenMotion.WALLPAPER_ZOOM, t.wallpaperScale, .0001f)
        assertEquals(AppOpenMotion.SCREEN_CORNER_RADIUS, t.iconCornerRadius, .0001f)
    }

    @Test fun `icon stays opaque while still small then dissolves into the window`() {
        assertEquals(1f, AppOpenMotion.transform(.5f, icon, screenW, screenH).iconAlpha, 0f)
        val mid = AppOpenMotion.transform(.725f, icon, screenW, screenH).iconAlpha
        assertEquals(.5f, mid, .0001f)
        assertEquals(0f, AppOpenMotion.transform(.9f, icon, screenW, screenH).iconAlpha, .0001f)
    }

    @Test fun `progress is clamped and degenerate bounds do not divide by zero`() {
        val over = AppOpenMotion.transform(2f, icon, screenW, screenH)
        assertEquals(AppOpenMotion.CONTENT_ZOOM, over.contentScale, .0001f)
        val empty = AppOpenMotion.transform(.5f, ZoomBounds(10f, 10f, 10f, 10f), screenW, screenH)
        assertTrue(empty.iconWidth.isFinite() && empty.iconWidth > 0f)
    }

    @Test fun `opening zoom is abandoned only after the timeout`() {
        val zoom = AppOpenZoom(icon, bitmap(), AppOpenDirection.OPENING, 1_000L)
        assertFalse(AppOpenMotion.abandoned(zoom, 1_000L + AppOpenMotion.ABANDON_MILLIS - 1))
        assertTrue(AppOpenMotion.abandoned(zoom, 1_000L + AppOpenMotion.ABANDON_MILLIS))
        assertFalse(AppOpenMotion.abandoned(zoom.copy(direction = AppOpenDirection.RETURNING), 99_999L))
    }

    @Test fun `controller plays the reverse zoom only when another window covered the launcher`() {
        AppOpenZoomController.begin(icon, bitmap(), 0L)
        assertEquals(AppOpenDirection.OPENING, AppOpenZoomController.state.value?.direction)
        AppOpenZoomController.launcherPaused()
        AppOpenZoomController.launcherResumed(400L)
        val returning = AppOpenZoomController.state.value
        assertNotNull(returning)
        assertEquals(AppOpenDirection.RETURNING, returning!!.direction)
        assertEquals(400L, returning.startedAt)
        AppOpenZoomController.finished(returning)
        assertNull(AppOpenZoomController.state.value)
    }

    @Test fun `resuming without ever being covered drops the zoom`() {
        AppOpenZoomController.begin(icon, bitmap(), 0L)
        AppOpenZoomController.launcherResumed(50L)
        assertNull(AppOpenZoomController.state.value)
    }

    @Test fun `a failed launch cancels and a stale opening zoom is abandoned into a return`() {
        AppOpenZoomController.begin(icon, bitmap(), 0L)
        AppOpenZoomController.cancel()
        assertNull(AppOpenZoomController.state.value)

        AppOpenZoomController.begin(icon, bitmap(), 0L)
        val zoom = AppOpenZoomController.state.value!!
        AppOpenZoomController.abandon(zoom, 100L)
        assertEquals(AppOpenDirection.OPENING, AppOpenZoomController.state.value?.direction)
        AppOpenZoomController.abandon(zoom, AppOpenMotion.ABANDON_MILLIS)
        assertEquals(AppOpenDirection.RETURNING, AppOpenZoomController.state.value?.direction)
    }

    @Test fun `finished ignores a zoom that is no longer current`() {
        AppOpenZoomController.begin(icon, bitmap(), 0L)
        val first = AppOpenZoomController.state.value!!
        AppOpenZoomController.begin(icon, bitmap(), 10L)
        AppOpenZoomController.finished(first)
        assertNotNull(AppOpenZoomController.state.value)
    }

    @Test fun `a gesture return is driven by the overlay and survives the resume`() {
        AppOpenZoomController.begin(icon, bitmap(), 0L, "pkg/.Main")
        AppOpenZoomController.launcherPaused()
        AppOpenZoomController.beginGesture(1.1f, 500L)
        assertEquals(AppOpenDirection.GESTURE, AppOpenZoomController.state.value?.direction)
        assertEquals(1.1f, AppOpenZoomController.gestureProgress.floatValue, 0f)
        // Home resuming under the overlay must not start the in-launcher return on top of it.
        AppOpenZoomController.launcherResumed(520L)
        assertEquals(AppOpenDirection.GESTURE, AppOpenZoomController.state.value?.direction)
        AppOpenZoomController.gestureZoom(1.04f)
        assertEquals(1.04f, AppOpenZoomController.gestureProgress.floatValue, 0f)
        AppOpenZoomController.endGesture()
        assertNull(AppOpenZoomController.state.value)
        assertEquals(1f, AppOpenZoomController.gestureProgress.floatValue, 0f)
    }

    @Test fun `ending a gesture leaves an unrelated zoom alone`() {
        AppOpenZoomController.begin(icon, bitmap(), 0L)
        AppOpenZoomController.endGesture()
        assertEquals(AppOpenDirection.OPENING, AppOpenZoomController.state.value?.direction)
    }

    @Test fun `gesture transform zooms wallpaper and icons together, fully opaque`() {
        val t = AppOpenMotion.gestureTransform(1.08f)
        assertEquals(1.08f, t.contentScale, 0f)
        assertEquals(1.08f, t.wallpaperScale, 0f)
        assertEquals(1f, t.contentAlpha, 0f)
        assertEquals(0f, t.iconAlpha, 0f)
    }

    @Test fun `zoom bounds helpers`() {
        val b = ZoomBounds.centered(50f, 40f, 20f, 10f)
        assertEquals(ZoomBounds(40f, 35f, 60f, 45f), b)
        assertEquals(ZoomBounds(20f, 17.5f, 30f, 22.5f), b.scaled(.5f))
        assertEquals(ZoomBounds(41f, 33f, 61f, 43f), b.offset(1f, -2f))
    }

    @Test fun `fallback zoom shows the icon only near icon size and the card only away from full screen`() {
        assertEquals(1f, AppOpenMotion.fallbackIconMix(0f), 0f)
        assertEquals(0f, AppOpenMotion.fallbackIconMix(.5f), 0f)
        assertEquals(1f, AppOpenMotion.fallbackCardAlpha(.5f), 0f)
        assertEquals(0f, AppOpenMotion.fallbackCardAlpha(1f), 0f)
        assertEquals(.7f, AppOpenMotion.transform(.7f, icon, screenW, screenH).progress, 0f)
    }

    private fun bitmap(): android.graphics.Bitmap? = null

    @Test fun `without the overlay the launcher plays the whole zoom and starts the app once`() {
        var started = 0
        AppOpenZoomController.beginHandover(icon, bitmap(), 0L, "a/.A") { started++ }
        val zoom = AppOpenZoomController.state.value!!
        assertTrue(zoom.handover)
        assertEquals("a/.A", AppOpenZoomController.hiddenIcon.value)
        assertEquals(0, started)
        AppOpenZoomController.handover(); AppOpenZoomController.handover()
        assertEquals(1, started)
        // The card stays opaque to the end: the app's window replaces it in place, no system slide.
        assertEquals(1f, AppOpenMotion.fallbackCardAlpha(1f, handover = true), 1e-4f)
        assertEquals(0f, AppOpenMotion.fallbackCardAlpha(1f), 1e-4f)
    }

    @Test fun `a cancelled handover never starts the app`() {
        var started = 0
        AppOpenZoomController.beginHandover(icon, bitmap(), 0L, null) { started++ }
        AppOpenZoomController.cancel()
        AppOpenZoomController.handover()
        assertEquals(0, started)
        assertNull(AppOpenZoomController.state.value)
    }

    @Test fun `a full-screen launch card's icon matches Android's splash icon size`() {
        val density = 2.75f
        val coverShort = 1168f
        // Full screen on the cover: 34 % of 425 dp ≈ 144 dp, like the system splash that replaces it.
        val full = AppOpenMotion.launchCardIconSide(1168f, 1712f, coverShort, density, fullness = 1f) / density
        assertEquals(144.5f, full, 1f)
        // A switcher-size card keeps a modest icon; huge panels cap at 160 dp.
        val deck = AppOpenMotion.launchCardIconSide(748f, 1096f, coverShort, density, fullness = .64f) / density
        assertEquals(70.7f, deck, 1f)
        assertEquals(160f * density, AppOpenMotion.launchCardIconSide(9000f, 9000f, 9000f, density, 1f), 1e-3f)
    }

    @Test fun `the overlay can re-aim a gesture return at another icon`() {
        AppOpenZoomController.beginGesture(1.07f, 0L, "a/.A")
        AppOpenZoomController.gestureIcon("work:a/.A")
        assertEquals("work:a/.A", AppOpenZoomController.hiddenIcon.value)
        AppOpenZoomController.gestureIcon(null)
        assertNull(AppOpenZoomController.hiddenIcon.value)
        assertEquals(AppOpenDirection.GESTURE, AppOpenZoomController.state.value?.direction)
        AppOpenZoomController.endGesture()
    }

    @Test fun `Reduce Motion launches cross-fade a full-screen card over an unmoved Home`() {
        val t = AppOpenMotion.dissolveTransform(.4f, screenW, screenH)
        assertEquals(0f, t.iconLeft, 0f); assertEquals(screenW, t.iconWidth, 0f); assertEquals(screenH, t.iconHeight, 0f)
        assertEquals(1f, t.contentScale, 0f); assertEquals(1f, t.wallpaperScale, 0f); assertEquals(1f, t.contentAlpha, 0f)
        assertEquals(.4f, t.progress, 1e-6f)
        var started = 0
        AppOpenZoomController.beginHandover(icon, bitmap(), 0L, "a/.A", dissolve = true) { started++ }
        assertTrue(AppOpenZoomController.state.value!!.dissolve)
        AppOpenZoomController.handover()
        assertEquals(1, started)
    }

    @Test fun `the overlay's landed card waits until Home has drawn the icon again (N1, N4)`() {
        AppOpenZoomController.beginGesture(1.05f, 0L, "a/.A")
        AppOpenZoomController.endGesture()
        assertFalse(AppOpenZoomController.restoredIconDrawn)
        AppOpenZoomController.iconDrawn(listOf("b/.B"))
        assertFalse(AppOpenZoomController.restoredIconDrawn)
        AppOpenZoomController.iconDrawn(listOf("a/.A"))
        assertTrue(AppOpenZoomController.restoredIconDrawn)
        // A fallback return hid nothing, so there is nothing to wait for.
        AppOpenZoomController.beginGesture(1.05f, 0L, null)
        AppOpenZoomController.endGesture()
        assertTrue(AppOpenZoomController.restoredIconDrawn)
    }

    /** Round 12: a swipe up on Home shrinks the page but never the wallpaper, and carries the live blur. */
    @Test fun `a Home-origin gesture shrinks the content only and carries the blur`() {
        val t = AppOpenMotion.homeGestureTransform(.86f, .4f)
        assertEquals(.86f, t.contentScale, 1e-6f)
        assertEquals(1f, t.wallpaperScale, 1e-6f)
        assertEquals(1f, t.contentAlpha, 1e-6f)
        assertEquals(.4f, t.homeBlur, 1e-6f)
        assertEquals(0f, AppOpenMotion.homeGestureTransform(1f, -1f).homeBlur, 1e-6f)
        assertEquals(1f, AppOpenMotion.homeGestureTransform(1.02f, 7f).homeBlur, 1e-6f)
        // The app-return gesture is unchanged: one uniform scale, no blur of its own.
        val g = AppOpenMotion.gestureTransform(1.07f)
        assertEquals(1.07f, g.wallpaperScale, 1e-6f); assertEquals(0f, g.homeBlur, 1e-6f)
    }
}
