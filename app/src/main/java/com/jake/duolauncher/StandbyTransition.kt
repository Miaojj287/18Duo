package com.jake.duolauncher

import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Home is parked (kept composed, not laid out or drawn) on every surface but its own and the veils rising over
 * it. Under the fold veil Home keeps laying out, so the new panel's configuration is drawn before the veil lifts. */
internal fun standbyParksHome(surface: LauncherSurface): Boolean =
    surface != LauncherSurface.HOME && surface != LauncherSurface.ENTERING && surface != LauncherSurface.FOLDING

/** Round 12: the handoff veil rises in ~0.1 s (the leaf under it is already dark) and lifts in ~0.2 s so the
 * panel's arrival motion is what the user sees. */
private val foldVeilSpring = IosSpring(.12f, 1f)

/** The black veil is fully up on these surfaces; on Home it lifts, while entering it rises. */
internal fun standbyVeilTarget(surface: LauncherSurface): Float = if (surface == LauncherSurface.HOME) 0f else 1f

/**
 * Hosts Home and StandBy around Android's rotation so that both directions read as a plain fade, like iOS
 * (interaction review S1):
 * - enter: a black veil rises over Home (which recedes slightly); once that frame is on screen [onVeiled] lets the
 *   controller request StandBy's orientation, so the rotation snapshot is black; the faces then fade in from black;
 * - exit: the face stays up while the orientation is released and the window settles (StandBy turns its canvas in a
 *   portrait window, so it never shows sideways), then fades off Home as Home settles from 1.04×.
 *
 * Home never leaves composition: under StandBy it is parked (no measure, placement, drawing, touch or
 * accessibility), so its page, open sheet/folder/Spotlight, edit mode and every `remember` survive, and its
 * request-keyed effects never replay when StandBy closes (code review P1-2).
 */
@Composable
internal fun StandbySurfaceHost(
    surface: LauncherSurface,
    onVeiled: () -> Unit,
    /** [active] is false while the face only veils Home's reveal. */
    standby: @Composable (active: Boolean) -> Unit,
    home: @Composable () -> Unit,
) {
    val view = LocalView.current
    val veil = remember { Animatable(standbyVeilTarget(surface)) }
    // Once shown, the face stays composed (turning its canvas while Android rotates to the other side or back to
    // Home's orientation) until it has faded off Home; before the first STANDBY frame those surfaces are black.
    var faceUp by remember { mutableStateOf(surface == LauncherSurface.STANDBY) }
    val showFace = surface == LauncherSurface.STANDBY ||
        faceUp && surface != LauncherSurface.ENTERING && surface != LauncherSurface.FOLDING
    SideEffect { if (surface == LauncherSurface.STANDBY) faceUp = true }
    val currentOnVeiled by rememberUpdatedState(onVeiled)
    val lastSurface = remember { mutableStateOf(surface) }
    LaunchedEffect(surface) {
        val from = lastSurface.value
        lastSurface.value = surface
        when (surface) {
            LauncherSurface.HOME -> {
                // SwiftUI .smooth (response 0.5 s, no bounce); Reduce Motion keeps it as a dissolve.
                if (LauncherMotion.animationsOff) veil.snapTo(0f)
                // A lifted fold veil is quick: the panel's arrival motion underneath is the reveal (FoldArrivalGate).
                else if (from == LauncherSurface.FOLDING) veil.animateTo(0f, LauncherMotion.dissolveOut)
                else veil.animateTo(0f, LauncherMotion.springSpec(LauncherMotion.smoothSpring, .001f))
                faceUp = false
            }
            LauncherSurface.FOLDING -> {
                faceUp = false
                if (LauncherMotion.animationsOff) veil.snapTo(1f)
                else veil.animateTo(1f, LauncherMotion.springSpec(foldVeilSpring, .002f))
                view.awaitFrameCommit()
                currentOnVeiled()
            }
            LauncherSurface.ENTERING -> {
                faceUp = false
                if (LauncherMotion.animationsOff) veil.snapTo(1f) else veil.animateTo(1f, LauncherMotion.dissolveIn)
                // Android snapshots the screen when the orientation changes: let the opaque veil reach it first.
                view.awaitFrameCommit()
                currentOnVeiled()
            }
            else -> veil.snapTo(1f)
        }
    }
    val veiled by remember { derivedStateOf { veil.value > 0f } }
    Box(Modifier.fillMaxSize()) {
        ParkedLayout(standbyParksHome(surface), Modifier.fillMaxSize().graphicsLayer {
            if (!LauncherMotion.reduced) { val scale = 1f + .04f * veil.value; scaleX = scale; scaleY = scale }
        }, home)
        // Leaving from a face, the face itself is the veil: a straight face → Home dissolve, no dip through black.
        if (veiled && !(showFace && surface == LauncherSurface.HOME)) Box(Modifier.fillMaxSize().testTag("standby-veil").graphicsLayer {
            alpha = veil.value
        }.background(Color.Black))
        if (showFace) {
            val active = surface == LauncherSurface.STANDBY
            Box(Modifier.fillMaxSize().graphicsLayer { if (surface == LauncherSurface.HOME) alpha = veil.value }) {
                standby(active)
            }
        }
    }
}

/** Suspends until a frame drawn after this call has been committed to the screen. */
private suspend fun View.awaitFrameCommit() = suspendCancellableCoroutine { continuation ->
    val callback = Runnable { if (continuation.isActive) continuation.resume(Unit) }
    viewTreeObserver.registerFrameCommitCallback(callback)
    continuation.invokeOnCancellation { if (viewTreeObserver.isAlive) viewTreeObserver.unregisterFrameCommitCallback(callback) }
    invalidate()
}

/** While [parked], [content] stays composed but is neither measured nor placed, so it costs no layout or drawing. */
@Composable
private fun ParkedLayout(parked: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, if (parked) modifier.clearAndSetSemantics { } else modifier) { measurables, constraints ->
        if (parked) return@Layout layout(constraints.maxWidth, constraints.maxHeight) { }
        val placeables = measurables.map { it.measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) { placeables.forEach { it.place(0, 0) } }
    }
}

/**
 * Spotlight / Settings destinations are one-shot: once Home has composed with a request (its `LaunchedEffect`
 * keyed on the counter has run), the counter returns to 0, so no later composition of Home can replay it — the
 * StandBy notification's Settings request used to reopen Settings on every StandBy exit (code review P1-2).
 */
@Composable
internal fun ConsumeLauncherRequests(vararg requests: MutableIntState) {
    for (request in requests) {
        val value = request.intValue
        LaunchedEffect(request, value) {
            if (value <= 0) return@LaunchedEffect
            withFrameNanos { }
            if (request.intValue == value) request.intValue = 0
        }
    }
}
