package com.jake.duolauncher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay

/**
 * StandBy's time source (code review P2-8d). Faces read it only inside their draw (and semantics) scopes, so a tick
 * redraws a canvas instead of recomposing the pagers, chrome and every face. [minute] invalidates its readers once a
 * minute, so faces without seconds (digital, calendar, photos, the burn-in orbit) redraw once a minute.
 */
@Stable
internal class StandbyClock(initial: ZonedDateTime) {
    private val tick = mutableStateOf(initial)
    /** The latest tick. Never read it in composition. */
    var now: ZonedDateTime
        get() = tick.value
        set(value) { tick.value = value }
    private val minuteState: State<ZonedDateTime> =
        derivedStateOf(structuralEqualityPolicy()) { tick.value.truncatedTo(ChronoUnit.MINUTES) }
    /** [now] truncated to the minute; readers are invalidated only when the minute changes. */
    val minute: ZonedDateTime get() = minuteState.value
}

/** Wall-clock aligned seconds while the screen is started (same 1 Hz cadence as before; the analog hands are unchanged). */
@Composable
internal fun rememberStandbyClock(): StandbyClock {
    val clock = remember { StandbyClock(ZonedDateTime.now()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(clock, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val time = ZonedDateTime.now()
                clock.now = time
                delay(1000L - time.nano / 1_000_000L)
            }
        }
    }
    return clock
}
