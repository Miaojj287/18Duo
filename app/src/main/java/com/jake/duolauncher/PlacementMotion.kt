package com.jake.duolauncher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset

/*
 * Home item motion that tells a real move from a new layout. iOS lays out a rotated, folded or
 * unfolded Home Screen final the moment the rotation ends; only an edit (a reflow, a drop, a
 * resize, a Dock preference) moves items with a spring. So these behave like animate*AsState,
 * except that a change of [frameKey] (pose, cell size, orientation, cutouts) jumps straight to
 * the new target: the remembered Animatable is recreated at the target, in the same composition,
 * so not even one frame shows the old position (review interaction H1/H2).
 *
 * [snap] is for an item whose resting tile is hidden (it is under the finger, or a drop ghost is
 * settling onto it): it goes straight to its slot. Otherwise the hidden tile was still gliding
 * from its old slot when the ghost read its rectangle, so after a long move the ghost landed
 * short and the tile then jumped on to the real slot (owner round 9).
 */

@Composable
internal fun animatePlacementAsState(target: IntOffset, frameKey: Any, spec: AnimationSpec<IntOffset>,
    snap: Boolean = false): State<IntOffset> {
    val animatable = remember(frameKey) { Animatable(target, IntOffset.VectorConverter) }
    LaunchedEffect(animatable, target, snap) { if (snap) animatable.snapTo(target) else animatable.animateTo(target, spec) }
    val snapped = rememberUpdatedState(target)
    return if (snap) snapped else animatable.asState()
}

@Composable
internal fun animatePlacementAsState(target: Float, frameKey: Any, spec: AnimationSpec<Float>,
    snap: Boolean = false): State<Float> {
    val animatable = remember(frameKey) { Animatable(target) }
    LaunchedEffect(animatable, target, snap) { if (snap) animatable.snapTo(target) else animatable.animateTo(target, spec) }
    val snapped = rememberUpdatedState(target)
    return if (snap) snapped else animatable.asState()
}

@Composable
internal fun animatePlacementAsState(target: Dp, frameKey: Any, spec: AnimationSpec<Dp>,
    snap: Boolean = false): State<Dp> {
    val animatable = remember(frameKey) { Animatable(target, Dp.VectorConverter) }
    LaunchedEffect(animatable, target, snap) { if (snap) animatable.snapTo(target) else animatable.animateTo(target, spec) }
    val snapped = rememberUpdatedState(target)
    return if (snap) snapped else animatable.asState()
}

/** What a Home grid's placements are measured in; when any of it changes the layout is new. */
internal data class PlacementFrameKey(val columns: Int, val cellWidthPx: Int, val rowHeightPx: Int,
    val topPitchPx: Int, val horizontal: Boolean = false)
