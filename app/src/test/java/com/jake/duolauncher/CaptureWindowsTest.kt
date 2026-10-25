package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round 8 QA: a system dialog left on screen ended up in the next app's switcher card. */
class CaptureWindowsTest {
    private val w = 1168; private val h = 1712
    private val self = "com.jake.duolauncher.core"
    private val settings = "com.android.settings"

    private fun app(pkg: String?, layer: Int, l: Int = 0, t: Int = 0, r: Int = w, b: Int = h) =
        WindowLine(WindowLine.APPLICATION, layer, pkg, pkg, l, t, r, b)
    private fun sys(title: String?, layer: Int, l: Int, t: Int, r: Int, b: Int, pkg: String? = "com.android.systemui") =
        WindowLine(WindowLine.SYSTEM, layer, pkg, title, l, t, r, b)
    private val statusBar = sys("StatusBar", 30, 0, 0, w, 126)
    private val navBar = sys(null, 29, 0, h - 60, w, h)
    private val ourStage = WindowLine(WindowLine.ACCESSIBILITY_OVERLAY, 40, self, "DuoNavigation-Stage", 0, 0, w, h)

    private fun verdict(vararg windows: WindowLine) = captureWindows(windows.toList(), settings, self, w, h)

    @Test fun `the app alone under the bars and our overlay can be photographed`() {
        assertEquals(CaptureWindows.CLEAR, verdict(ourStage, statusBar, navBar, app(settings, 10), app("launcher", 1)))
        // The keyboard is part of the app's state.
        val ime = WindowLine(WindowLine.INPUT_METHOD, 20, "com.android.inputmethod.latin", null, 0, 1000, w, h)
        assertEquals(CaptureWindows.CLEAR, verdict(ime, app(settings, 10)))
        // The app's own dialog is its own content.
        assertEquals(CaptureWindows.CLEAR, verdict(app(settings, 11, 100, 600, 1000, 1100), app(settings, 10)))
    }

    @Test fun `a system dialog, toast, floating window or the shade refuses the capture`() {
        // "Built for an older version of Android" / permission dialogs: a window of another package on top.
        assertEquals(CaptureWindows.NOT_IN_FRONT, verdict(app("android", 12, 100, 600, 1000, 1100), app(settings, 10)))
        assertEquals(CaptureWindows.NOT_IN_FRONT, verdict(app("com.android.permissioncontroller", 12), app(settings, 10)))
        // The same dialog as a system window.
        assertEquals(CaptureWindows.COVERED, verdict(sys("Messaging", 25, 100, 600, 1000, 1100, "android"), app(settings, 10)))
        // A toast: small, bottom-centre, not a bar.
        assertEquals(CaptureWindows.COVERED, verdict(sys("Toast", 26, 200, 1400, 968, 1520), app(settings, 10)))
        // A HyperOS floating window of another app.
        assertEquals(CaptureWindows.NOT_IN_FRONT, verdict(app("com.tencent.mm", 15, 300, 300, 900, 1200), app(settings, 10)))
        // The notification shade pulled down.
        assertEquals(CaptureWindows.COVERED, verdict(sys("NotificationShade", 31, 0, 0, w, h), app(settings, 10)))
        // Another accessibility service's overlay.
        val other = WindowLine(WindowLine.ACCESSIBILITY_OVERLAY, 41, "com.other", "Bubble", 0, 0, 200, 200)
        assertEquals(CaptureWindows.COVERED, verdict(other, app(settings, 10)))
        // Split screen: the divider lies over it.
        assertEquals(CaptureWindows.COVERED, verdict(WindowLine(WindowLine.SPLIT_SCREEN_DIVIDER, 13, null, null, 580, 0, 590, h), app(settings, 10, r = 585)))
    }

    @Test fun `windows that do not touch the app or lie under it do not matter`() {
        val beside = sys("Pip", 25, 0, 0, 100, 100)
        assertEquals(CaptureWindows.CLEAR, verdict(beside, app(settings, 10, l = 200)))
        assertEquals(CaptureWindows.CLEAR, verdict(app(settings, 10), sys("Wallpaper", 0, 0, 0, w, h)))
    }

    @Test fun `another app or an unknown package in front is not the target`() {
        assertEquals(CaptureWindows.NOT_IN_FRONT, verdict(app("com.android.contacts", 10)))
        assertEquals(CaptureWindows.NOT_IN_FRONT, verdict(app(null, 10)))
        assertEquals(CaptureWindows.NOT_IN_FRONT, verdict(statusBar))
        // Without the window list the timed policy decides alone.
        assertEquals(CaptureWindows.UNKNOWN, captureWindows(emptyList(), settings, self, w, h))
    }

    @Test fun `system bars are recognised by title or by their edge-strip shape`() {
        assertTrue(isSystemBar(sys("StatusBar", 1, 0, 0, w, 126), w, h))
        assertTrue(isSystemBar(sys("Taskbar", 1, 0, h - 150, w, h), w, h))
        assertTrue(isSystemBar(sys(null, 1, 0, h - 60, w, h), w, h))
        assertTrue(isSystemBar(sys(null, 1, w - 50, 0, w, h), w, h))
        assertFalse(isSystemBar(sys("Toast", 1, 200, 1400, 968, 1520), w, h))
        assertFalse(isSystemBar(sys(null, 1, 0, 600, w, 700), w, h))
        assertFalse(isSystemBar(sys(null, 1, 0, 0, w, h / 2), w, h))
    }
}
