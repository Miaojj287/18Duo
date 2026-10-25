package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test

class NavigationSetupTest {
    private var mode = false
    private var writes = 0
    private var enabled = false
    private var hidden = false
    private fun enable(defaultHome: Boolean = true,
        write: () -> Boolean = { mode = true; true }, hide: () -> Unit = { hidden = true }) =
        enableNavigation(defaultHome, { mode }, { writes++; write() }, { enabled = true }, hide)

    @Test fun existingGestureModeNeedsNoPrivilegedModeWrite() {
        mode = true
        assertEquals(NavigationEnableResult.ENABLED, enable(write = { throw SecurityException() }))
        assertEquals(0, writes)
        assertTrue(enabled)
    }
    @Test fun cosmeticPillFailureDoesNotPreventNavigation() {
        mode = true
        assertEquals(NavigationEnableResult.ENABLED, enable(hide = { throw SecurityException() }))
        assertTrue(enabled)
    }
    @Test fun nonDefaultLauncherNeverChangesAnything() {
        assertEquals(NavigationEnableResult.DEFAULT_HOME_REQUIRED, enable(defaultHome = false))
        assertEquals(0, writes); assertFalse(enabled); assertFalse(hidden)
    }
    @Test fun deniedSystemPermissionLeavesOverlayPreferenceUntouched() {
        assertEquals(NavigationEnableResult.SYSTEM_MODE_REQUIRED, enable(write = { throw SecurityException() }))
        assertFalse(enabled); assertFalse(hidden)
    }
    @Test fun failedWriteLeavesOverlayPreferenceUntouched() {
        assertEquals(NavigationEnableResult.SYSTEM_MODE_REQUIRED, enable(write = { false }))
        assertFalse(enabled); assertFalse(hidden)
    }
    @Test fun successfulWriteMustBeConfirmedByReadback() {
        assertEquals(NavigationEnableResult.SYSTEM_MODE_REQUIRED, enable(write = { true }))
        assertFalse(enabled); assertFalse(hidden)
    }
    @Test fun confirmedModeEnablesOverlay() {
        assertEquals(NavigationEnableResult.ENABLED, enable())
        assertEquals(1, writes); assertTrue(enabled); assertTrue(hidden)
    }
}
