package com.jake.duolauncher

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope

/**
 * Blank desktop space has no vertical scroll consumer to cancel a stock long press.
 * Resolve intent from distance instead, and yield to child controls/native widgets
 * or a pager that consumes the stream. A swipe never becomes a tap on release.
 */
internal const val HOME_OPTIONS_HOLD_MS = 800L

internal suspend fun PointerInputScope.detectStationaryHomeTaps(
    onTap: ((Offset) -> Unit)? = null,
    onLongPress: (Offset) -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown()
    var released = false
    var canceled = false
    withTimeoutOrNull(HOME_OPTIONS_HOLD_MS) {
        while (!released && !canceled) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id }
            canceled = change == null || event.changes.any { it.isConsumed } ||
                event.changes.any { it.id != down.id && it.pressed } ||
                (change.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                change.position.x !in 0f..size.width.toFloat() ||
                change.position.y !in 0f..size.height.toFloat()
            if (!canceled) {
                released = change?.pressed == false
                if (!released) {
                    val finalEvent = awaitPointerEvent(PointerEventPass.Final)
                    canceled = finalEvent.changes.any { it.isConsumed }
                }
            }
        }
    }
    if (canceled) return@awaitEachGesture
    if (released) {
        // Only a short, still tap on bare space counts (iOS ends jiggle mode this way).
        onTap?.invoke(down.position)
        return@awaitEachGesture
    }
    onLongPress(down.position)
    // Long press owns the release, so it cannot also trigger a click.
    do {
        val event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
    } while (event.changes.any { it.pressed })
}
