package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupWizardTest {
    @Test fun freshInstallStartsAtDefaultHome() {
        val state = SetupWizardState(defaultHome = false, navigationService = false, gestureMode = false, wallpaper = false)
        assertEquals(SetupStep.DEFAULT_HOME, state.current)
        assertEquals(SetupStepStatus.CURRENT, setupStepStatus(state, SetupStep.DEFAULT_HOME))
        assertEquals(SetupStepStatus.UPCOMING, setupStepStatus(state, SetupStep.NAVIGATION_SERVICE))
        assertEquals(SetupStepStatus.UPCOMING, setupStepStatus(state, SetupStep.FULLSCREEN_GESTURES))
        assertEquals(0, state.completedCount)
        assertFalse(state.complete)
    }

    @Test fun returningFromSettingsAdvancesToNextIncompleteStep() {
        val state = SetupWizardState(defaultHome = true, navigationService = false, gestureMode = false, wallpaper = false)
        assertEquals(SetupStep.NAVIGATION_SERVICE, state.current)
        assertEquals(SetupStepStatus.DONE, state.status(SetupStep.DEFAULT_HOME))
        assertEquals(SetupStepStatus.CURRENT, state.status(SetupStep.NAVIGATION_SERVICE))
        assertEquals(1, state.completedCount)
    }

    @Test fun laterStepAlreadySatisfiedIsDoneButDoesNotBecomeCurrent() {
        // Gesture mode can already be on system-wide before the service exists.
        val state = SetupWizardState(defaultHome = true, navigationService = false, gestureMode = true, wallpaper = false)
        assertEquals(SetupStep.NAVIGATION_SERVICE, state.current)
        assertEquals(SetupStepStatus.DONE, state.status(SetupStep.FULLSCREEN_GESTURES))
        assertEquals(2, state.completedCount)
        assertFalse(state.complete)
    }

    @Test fun allDoneCompletesWizard() {
        val state = SetupWizardState(defaultHome = true, navigationService = true, gestureMode = true, wallpaper = true)
        assertNull(state.current)
        assertTrue(state.complete)
        assertEquals(4, state.completedCount)
        SetupStep.entries.forEach { assertEquals(SetupStepStatus.DONE, state.status(it)) }
    }

    @Test fun losingDefaultHomeReopensFirstStep() {
        val state = SetupWizardState(defaultHome = false, navigationService = true, gestureMode = true, wallpaper = true)
        assertEquals(SetupStep.DEFAULT_HOME, state.current)
        assertEquals(SetupStepStatus.CURRENT, state.status(SetupStep.DEFAULT_HOME))
    }

    @Test fun wallpaperIsTheLastStep() {
        assertEquals(SetupStep.WALLPAPER, SetupStep.entries.last())
        val state = SetupWizardState(defaultHome = true, navigationService = true, gestureMode = true, wallpaper = false)
        assertEquals(SetupStep.WALLPAPER, state.current)
        assertFalse(state.complete)
        assertEquals(3, state.completedCount)
    }

    @Test fun wallpaperChosenEarlyWaitsAsDone() {
        // A photo picked in Settings before the wizard ran counts, but never jumps the queue.
        val state = SetupWizardState(defaultHome = false, navigationService = false, gestureMode = false, wallpaper = true)
        assertEquals(SetupStep.DEFAULT_HOME, state.current)
        assertEquals(SetupStepStatus.DONE, state.status(SetupStep.WALLPAPER))
        assertEquals(SetupStepStatus.UPCOMING, state.status(SetupStep.NAVIGATION_SERVICE))
    }
}
