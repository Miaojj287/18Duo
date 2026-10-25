package com.jake.duolauncher

import android.os.SystemClock
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production follower and panel state in an isolated composition.
 * No debug launcher mode, synthetic sensor stream or screenshot fixture is shipped. */
@RunWith(AndroidJUnit4::class)
class FoldMotionStateTest {
    @get:Rule val compose = createComposeRule()

    private fun innerSignal(epoch: Long = 0L, angle: Float = 90f) = FoldSignal(
        angleDegrees = angle,
        activePanel = FoldPanel.INNER,
        previousPanel = if (epoch > 0L) FoldPanel.COVER else FoldPanel.UNKNOWN,
        panelEpoch = epoch,
        receivedElapsedNs = SystemClock.elapsedRealtimeNanos(),
        panelChangedElapsedNs = if (epoch > 0L) SystemClock.elapsedRealtimeNanos() else 0L,
        phase = if (epoch > 0L) FoldPhase.HANDOFF_TO_INNER else FoldPhase.OPENING_INNER,
    )

    @Test fun disablingMotionClearsProjectionAndArrivalImmediately() {
        compose.mainClock.autoAdvance = false
        val signal = mutableStateOf(innerSignal(epoch = 1L))
        val enabled = mutableStateOf(true)
        lateinit var transition: FoldTransitionState
        compose.setContent {
            val current = rememberFoldTransitionFromSignal(true, signal, enabled.value)
            SideEffect { transition = current }
        }
        compose.mainClock.advanceTimeBy(96)
        compose.runOnIdle { assertTrue(transition.reveal.value < 1f); enabled.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertEquals(1f, transition.reveal.value, 0f)
            assertEquals(0f, transition.coverFold.value, 0f)
            assertEquals(1f, transition.arrival.value, 0f)
            assertEquals(FoldPhase.INNER_STABLE, transition.phase.value)
        }
    }

    @Test fun confirmedFlatClearsAnInFlightArrivalWithoutWaitingForFramesToSettle() {
        compose.mainClock.autoAdvance = false
        val signal = mutableStateOf(innerSignal(epoch = 1L))
        lateinit var transition: FoldTransitionState
        compose.setContent {
            val current = rememberFoldTransitionFromSignal(true, signal)
            SideEffect { transition = current }
        }
        compose.mainClock.advanceTimeBy(96)
        compose.runOnIdle {
            assertTrue(transition.arrival.value < 1f)
            signal.value = signal.value.copy(innerConfirmedFlat = true, phase = FoldPhase.INNER_STABLE)
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertEquals(1f, transition.reveal.value, 0f)
            assertEquals(FoldPhase.INNER_STABLE, transition.phase.value)
        }
        compose.runOnIdle { signal.value = signal.value.copy(innerConfirmedFlat = false, phase = FoldPhase.CLOSING_INNER) }
        compose.mainClock.advanceTimeBy(800)
        compose.runOnIdle {
            assertEquals(hingeReveal(90f), transition.reveal.value, .002f)
            assertEquals(FoldPhase.CLOSING_INNER, transition.phase.value)
        }
    }

    @Test fun interruptedArrivalContinuesFromTheCurrentPresentationValue() {
        compose.mainClock.autoAdvance = false
        val signal = mutableStateOf(innerSignal(epoch = 1L))
        lateinit var transition: FoldTransitionState
        var presented = 0f
        compose.setContent {
            val current = rememberFoldTransitionFromSignal(true, signal)
            SideEffect { transition = current }
        }
        compose.mainClock.advanceTimeBy(128)
        compose.runOnIdle {
            presented = transition.arrival.value
            assertTrue(presented > 0f && presented < 1f)
            signal.value = innerSignal(epoch = 2L)
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertTrue(transition.arrival.value >= presented - .001f) }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertEquals(1f, transition.arrival.value, .001f) }
    }

    @Test fun confirmedCoverContactFadesTheFollowerAndHandoffResidual() {
        compose.mainClock.autoAdvance = false
        val signal = mutableStateOf(innerSignal(epoch = 1L).copy(
            activePanel = FoldPanel.COVER, previousPanel = FoldPanel.INNER))
        lateinit var transition: FoldTransitionState
        compose.setContent {
            val current = rememberFoldTransitionFromSignal(false, signal)
            SideEffect { transition = current }
        }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle {
            assertTrue(transition.coverFold.value > 0f)
            signal.value = signal.value.copy(coverProjectionBlocked = true)
        }
        // Round 20: no one-frame step (the closed-in-hand twitch); clear once the gate has faded.
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertTrue(transition.coverFold.value > 0f) }
        compose.mainClock.advanceTimeBy(COVER_GATE_MS + 64L)
        compose.runOnIdle { assertEquals(0f, transition.coverFold.value, 0f) }
    }

    @Test fun disposingAndResettingTheSessionCannotReplayAnOldHandoffOnReentry() {
        compose.mainClock.autoAdvance = false
        val signal = mutableStateOf(innerSignal(epoch = 1L))
        val visible = mutableStateOf(true)
        lateinit var transition: FoldTransitionState
        compose.setContent {
            if (visible.value) {
                val current = rememberFoldTransitionFromSignal(true, signal)
                SideEffect { transition = current }
            }
        }
        compose.mainClock.advanceTimeBy(96)
        compose.runOnIdle { assertTrue(transition.arrival.value < 1f); visible.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { signal.value = resetFoldTracking(signal.value); visible.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertEquals(1f, transition.arrival.value, 0f)
            assertEquals(1f, transition.reveal.value, 0f)
            assertEquals(FoldPhase.RECOVERY, transition.phase.value)
        }
    }
}
