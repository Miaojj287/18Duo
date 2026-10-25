package com.jake.duolauncher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSetupAlertTest {
    @Test fun `a widget setup only counts as interrupted once Home has been back in front for a moment`() {
        // Binding in progress behind Android's dialog: Home is paused, no prompt.
        assertFalse(widgetSetupInterrupted(pending = true, resumed = false, resumedForMillis = 5_000))
        // Just resumed with the result still on its way: no prompt (its window used to eat the first tap).
        assertFalse(widgetSetupInterrupted(pending = true, resumed = true, resumedForMillis = 0))
        assertFalse(widgetSetupInterrupted(pending = true, resumed = true, resumedForMillis = WIDGET_SETUP_INTERRUPT_GRACE_MS - 1))
        // Still pending after the grace period: the user walked away from the setup.
        assertTrue(widgetSetupInterrupted(pending = true, resumed = true, resumedForMillis = WIDGET_SETUP_INTERRUPT_GRACE_MS))
        assertFalse(widgetSetupInterrupted(pending = false, resumed = true, resumedForMillis = 10_000))
    }
}
