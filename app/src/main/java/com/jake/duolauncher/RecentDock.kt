package com.jake.duolauncher

import android.graphics.Rect
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Which app each recent slot of the Dock shows. An app that is still recent keeps the slot it is
 * in; newcomers (most recent first) take the slots that were freed, then any new slots. Reordering
 * alone (the same apps, a different recency order) therefore changes nothing on screen.
 */
internal fun stableRecentSlots(shown: List<String>, next: List<String>): List<String> {
    if (shown.isEmpty() || next.isEmpty()) return next
    val incoming = next.filter { it !in shown }.iterator()
    val slots = shown.mapNotNull { id -> if (id in next) id else if (incoming.hasNext()) incoming.next() else null }
    return slots + incoming.asSequence().toList()
}

/** How long Home must have been idle (no app zoom) before a recent slot changes while Home is on screen. */
internal const val RECENT_DOCK_SETTLE_MS = 350L

/**
 * The recent apps the Dock shows, kept steady while Home is on screen (owner/r3-nav: a recent icon
 * "faded out and back in" ≈ 0.6 s after returning Home, when the resume refresh re-read the history
 * and then re-ranked it by usage). A change made while Home is covered by an app is applied at once,
 * so it is already in Home's first frame back; on screen it waits until no app zoom is running and
 * the history has stopped changing, and then only replaces what actually left ([stableRecentSlots]).
 * Switching the feature on or off applies immediately.
 */
@Composable
internal fun rememberSettledRecentIds(target: List<String>, busy: () -> Boolean): List<String> {
    var shown by remember { mutableStateOf(target) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val currentBusy by rememberUpdatedState(busy)
    LaunchedEffect(target) {
        if (stableRecentSlots(shown, target) == shown) return@LaunchedEffect
        if (shown.isNotEmpty() && target.isNotEmpty()) {
            var idle = 0L
            while (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
                idle < RECENT_DOCK_SETTLE_MS) {
                kotlinx.coroutines.delay(50L)
                idle = if (currentBusy()) 0L else idle + 50L
            }
        }
        shown = stableRecentSlots(shown, target)
    }
    return shown
}

@Composable
internal fun RecentDockApps(apps: List<AppEntry>, horizontal: Boolean, rowSize: Float,
    onLaunch: (AppEntry, Rect?) -> Unit, iconSize: Float = minOf(48f, rowSize - 4f)) {
    val content: @Composable () -> Unit = {
        // Keyed: a slot's node (press state, launch bounds, zoom target) belongs to its app.
        apps.forEach { app -> key(app.id) {
            val bounds = remember { Rect() }
            val interaction = remember { MutableInteractionSource() }
            Box(Modifier.then(if (horizontal) Modifier.width(rowSize.dp).fillMaxHeight()
                else Modifier.fillMaxWidth().height(rowSize.dp))
                .clickable(interactionSource = interaction, indication = null) { onLaunch(app, bounds) }
                .semantics { contentDescription = uxText("最近使用：${app.label}", "Recent: ${app.label}") }.testTag("recent-app-${app.id}"),
                contentAlignment = Alignment.Center) {
                // Same artwork size and mask as the four Dock apps above the hairline.
                Image(app.icon.asImageBitmap(), null, Modifier.size(iconSize.dp)
                    .onGloballyPositioned { bounds.set(it.boundsInWindow().toAndroidBounds()) }
                    .homeIconTarget(app.id, HomeIconKind.RECENT)
                    .iosIconHighlight(interaction).clip(homeIconShape(iconSize)))
            }
        } }
    }
    if (horizontal) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("recent-dock")) {
        Box(Modifier.width(12.dp).height(26.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.width(1.dp).height(24.dp)) {
                drawRect(androidx.compose.ui.graphics.Color.White.copy(alpha = .45f))
            }
        }
        content()
    } else Column(Modifier.testTag("recent-dock")) {
        // Hairline between the four Dock apps and the recents, like the iPad Dock's divider.
        Box(Modifier.padding(horizontal = 14.dp, vertical = 6.dp).fillMaxWidth().height(.5.dp)
            .background(LocalContentColor.current.copy(alpha = .28f)))
        content()
    }
}
