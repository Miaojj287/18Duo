package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Core.17 review P1-7: a screenshot must never be filed under the wrong app or profile. */
class NavigationForegroundTest {
    private val home = "com.jake.duolauncher.core"

    private fun ev(type: Int, pkg: String, time: Long, cls: String = ".Main") = UsageSample(type, pkg, cls, time)

    @Test fun `the newest resumed activity is in front`() {
        val events = listOf(ev(USAGE_RESUMED, "a", 1), ev(USAGE_PAUSED, "a", 2), ev(USAGE_RESUMED, "b", 3))
        assertEquals(UsageForeground("b", false, 3), usageForeground(events, home))
    }

    @Test fun `a trailing pause means our user has nothing in front (work profile, Dual Apps)`() {
        // Personal WeChat paused; the dual copy in user 999 resumed, which our usage events never see.
        val events = listOf(ev(USAGE_RESUMED, "a", 1), ev(USAGE_PAUSED, "a", 2), ev(USAGE_STOPPED, "a", 3))
        assertNull(usageForeground(events, home).packageName)
    }

    @Test fun `two resumed packages are ambiguous (floating window)`() {
        val events = listOf(ev(USAGE_RESUMED, "a", 1), ev(USAGE_RESUMED, "b", 2))
        val result = usageForeground(events, home)
        assertEquals("b", result.packageName)
        assertTrue(result.ambiguous)
    }

    @Test fun `Home resuming ends apps that died without a pause event`() {
        val events = listOf(ev(USAGE_RESUMED, "crashed", 1), ev(USAGE_RESUMED, home, 2, ".MainActivity"),
            ev(USAGE_PAUSED, home, 3, ".MainActivity"), ev(USAGE_RESUMED, "b", 4))
        assertEquals(UsageForeground("b", false, 4), usageForeground(events, home))
    }

    @Test fun `destroyed and per-activity pauses are tracked separately`() {
        val events = listOf(ev(USAGE_RESUMED, "a", 1, ".One"), ev(USAGE_RESUMED, "a", 2, ".Two"),
            ev(USAGE_PAUSED, "a", 3, ".One"), ev(USAGE_DESTROYED, "a", 4, ".One"))
        assertEquals(UsageForeground("a", false, 2), usageForeground(events, home))
        assertNull(usageForeground(events + ev(USAGE_PAUSED, "a", 5, ".Two"), home).packageName)
    }

    @Test fun `windows are classified by owner, our own dialogs and system chrome do not count`() {
        val ime = setOf("com.android.inputmethod.latin")
        val main = "com.jake.duolauncher.MainActivity"
        assertEquals(WindowOwner.HOME, classifyWindow(home, main, home, main, ime))
        assertEquals(WindowOwner.IGNORE, classifyWindow(home, "android.app.AlertDialog", home, main, ime))
        assertEquals(WindowOwner.IGNORE, classifyWindow("com.android.systemui", "x", home, main, ime))
        // HyperOS: the status bar, shade and notification capsule live in the SystemUI plugin (round 12.4).
        assertEquals(WindowOwner.IGNORE, classifyWindow("miui.systemui.plugin", "x", home, main, ime))
        assertEquals(WindowOwner.IGNORE, classifyWindow("com.miui.systemui.plugin.extra", "x", home, main, ime))
        assertEquals(WindowOwner.APP, classifyWindow("com.miui.home", "x", home, main, ime))
        assertEquals(WindowOwner.IGNORE, classifyWindow("com.android.inputmethod.latin", "x", home, main, ime))
        assertEquals(WindowOwner.IGNORE, classifyWindow(null, null, home, main, ime))
        assertEquals(WindowOwner.APP, classifyWindow("com.tencent.mm", "x", home, main, ime))
    }

    @Test fun `window and usage agreeing is the only fileable case with usage access`() {
        val window = WindowSample("a", home = false, at = 1_000)
        val agree = resolveForeground(UsageForeground("a", false, 900), window, home) { false }
        assertEquals(ForegroundGuess("a", home = false, fileable = true), agree)
        // The window is a's, but our user's a is not the resumed one: another profile's copy.
        val otherProfile = resolveForeground(UsageForeground(null, false), window, home) { true }
        assertEquals(ForegroundGuess("a", home = false, fileable = false), otherProfile)
        val floating = resolveForeground(UsageForeground("a", true, 900), window, home) { false }
        assertFalse(floating.fileable)
    }

    @Test fun `an app launched after the last Home window event is the one in front (round 9)`() {
        val homeWindow = WindowSample(null, home = true, at = 1_000)
        assertEquals(ForegroundGuess("a", home = false, fileable = false),
            resolveForeground(null, homeWindow, home, launchedAfterWindow = "a") { false })
        assertTrue(resolveForeground(null, homeWindow, home) { false }.home)
        assertTrue(resolveForeground(null, homeWindow, home, launchedAfterWindow = home) { false }.home)
    }

    @Test fun `without usage access an app installed in two profiles is never filed`() {
        val window = WindowSample("a", home = false, at = 1_000)
        assertTrue(resolveForeground(null, window, home) { false }.fileable)
        assertFalse(resolveForeground(null, window, home) { true }.fileable)
        assertEquals("a", resolveForeground(null, window, home) { true }.packageName)
    }

    @Test fun `Home in front, unknown, and usage-only answers`() {
        assertTrue(resolveForeground(UsageForeground("a", false, 1), WindowSample(null, home = true, at = 5_000), home) { false }.home)
        assertEquals(ForegroundGuess.UNKNOWN, resolveForeground(null, null, home) { false })
        assertEquals(ForegroundGuess("a", home = false, fileable = true),
            resolveForeground(UsageForeground("a", false, 1), null, home) { false })
        assertTrue(resolveForeground(UsageForeground(home, false, 1), null, home) { false }.home)
    }

    @Test fun `a window sample much older than the newest resume is stale`() {
        // Window events were missed (e.g. the service rebound): trust the resume.
        val stale = WindowSample("old", home = false, at = 1_000)
        assertEquals(ForegroundGuess("new", home = false, fileable = true),
            resolveForeground(UsageForeground("new", false, 10_000), stale, home) { false })
    }

    @Test fun `snapshots of the same app in two profiles never share a key`() {
        assertNotEquals(snapshotKey("com.tencent.mm", 0), snapshotKey("com.tencent.mm", 999))
        assertEquals(snapshotKey("a", 10), snapshotKey("a", 10))
    }

    @Test fun `the stage draw cache stays bounded and empties when the stage goes idle`() {
        var created = 0
        val cache = TransientDrawCache<Int, String>(maxEntries = 24) { created++; "shader $it" }
        // Thirty gestures, each drawing a fresh screenshot plus the same few icons.
        repeat(30) { gesture ->
            cache.get(1000 + gesture)
            repeat(5) { icon -> cache.get(icon) }
            assertTrue(cache.size <= 24)
        }
        assertEquals("shader 3", cache.get(3))
        cache.clear()
        assertEquals(0, cache.size)
        assertTrue(created >= 30)
    }
}
