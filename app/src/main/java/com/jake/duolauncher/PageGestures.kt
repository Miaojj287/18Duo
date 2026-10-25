package com.jake.duolauncher

import androidx.compose.animation.core.animate
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput

/** One adjacent page per ordinary pointer gesture, including its release fling. */
internal class PageGestureLimits(private val pager: PagerState) : NestedScrollConnection, PagerSnapDistance {
    var anchor: Int? = null
    var pointerDown = false
    var editing = false
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val start = anchor ?: return Offset.Zero
        if (!pointerDown || editing || source != NestedScrollSource.UserInput || available.x == 0f) return Offset.Zero
        val stride = pager.layoutInfo.pageSize + pager.layoutInfo.pageSpacing
        if (stride <= 0) return Offset.Zero
        val position = pager.currentPage + pager.currentPageOffsetFraction
        val allowed = boundedPagePosition(position - available.x / stride, start, pager.pageCount)
        return Offset(available.x + (allowed - position) * stride, 0f)
    }
    override fun calculateTargetPage(startPage: Int, suggestedTargetPage: Int, velocity: Float,
        pageSize: Int, pageSpacing: Int): Int =
        if (editing) suggestedTargetPage else boundedPagePosition(suggestedTargetPage.toFloat(), anchor ?: startPage, pager.pageCount).toInt()
}

internal fun boundedPagePosition(position: Float, anchor: Int, count: Int): Float =
    position.coerceIn((anchor - 1).coerceAtLeast(0).toFloat(), (anchor + 1).coerceAtMost(count - 1).coerceAtLeast(0).toFloat())

internal fun shadePanelForStart(startX: Float, width: Float): ShadePanel =
    if (startX < width * .7f) ShadePanel.NOTIFICATIONS else ShadePanel.QUICK_SETTINGS

/** The visible leading pane owns vertical drags, including empty space between widgets. */
internal fun startsInExpandedLeadingPane(expanded: Boolean, startX: Float, paneWidth: Float,
    scrollOffset: Float): Boolean = expanded && startX >= 0f && startX < (paneWidth - scrollOffset).coerceAtLeast(0f)

/**
 * UIScrollView-style paging over the native [PagerState], in the workspace's visual offset space
 * ([WorkspacePageMotion]): 1:1 finger tracking, iOS rubber band beyond the reachable pages, an
 * iOS projected-target release decision and a critically damped settle that inherits the finger's
 * velocity. The legal part of the offset drives the pager; the rest is [WorkspaceScrollEffects.overscroll].
 */
private class PagingDriver(
    private val pager: PagerState,
    private val motion: WorkspacePageMotion?,
    private val effects: WorkspaceScrollEffects?,
) {
    /** Incremented by every new drag/settle/catch; an older job that is cancelled by a newer one
     * leaves the page where the newer one (the catching finger) took it over. */
    var generation = 0

    fun nativeStride(): Float = (pager.layoutInfo.pageSize + pager.layoutInfo.pageSpacing).toFloat().coerceAtLeast(1f)
    fun position(): Float = pager.currentPage + pager.currentPageOffsetFraction
    fun offsetOf(position: Float): Float = motion?.offset(position) ?: position * nativeStride()
    fun positionOf(offset: Float): Float = motion?.position(offset) ?: offset / nativeStride()
    fun legalOffset(): Float = offsetOf(position())
    fun overscroll(): Float = effects?.overscroll ?: 0f
    fun visualOffset(): Float = legalOffset() + overscroll()
    fun lastPage(): Int = (pager.pageCount - 1).coerceAtLeast(0)

    /** Moves the pager to the legal [offset] and shows [overscroll] beyond it. */
    fun ScrollScope.show(offset: Float, overscroll: Float) {
        // Base every frame on the pager's real position so a rejected or rounded delta can
        // never accumulate into a persistent offset.
        val target = positionOf(offset)
        scrollBy((target - position()) * nativeStride())
        effects?.overscroll = if (abs(overscroll) < .01f) 0f else overscroll
    }

    /** Finger tracking from [anchor]; [positions] are finger x-distances since the drag started. */
    suspend fun ScrollScope.track(anchor: Int, positions: ReceiveChannel<Float>, viewport: Float): Float {
        val low = offsetOf((anchor - 1).coerceIn(0, lastPage()).toFloat())
        val high = offsetOf((anchor + 1).coerceIn(0, lastPage()).toFloat())
        // Undo any rubber band a catching touch froze on screen so the content does not jump.
        val start = legalOffset() + IosScrollPhysics.rubberBandInverse(overscroll(), viewport)
        var raw = start
        for (dx in positions) {
            raw = start - dx
            with(pager) { updateTargetPage((anchor + sign(raw - offsetOf(anchor.toFloat())).toInt()).coerceIn(0, lastPage())) }
            val split = rubberBandedOffset(raw, low, high, viewport)
            show(split.legal, split.overscroll)
        }
        return raw - raw.coerceIn(minOf(low, high), maxOf(low, high))
    }

    /** Settles on [target] with the paging spring, starting at the finger's [velocity] (offset px/s). */
    suspend fun ScrollScope.settle(target: Int, velocity: Float) {
        val spring = LauncherMotion.page
        with(pager) { updateTargetPage(target) }
        val from = visualOffset()
        val to = offsetOf(target.toFloat())
        val low = minOf(offsetOf(0f), offsetOf(lastPage().toFloat()))
        val high = maxOf(offsetOf(0f), offsetOf(lastPage().toFloat()))
        val initialVelocity = IosScrollPhysics.settleVelocity(velocity, to - from, spring)
        // Half a pixel: the sub-pixel tail is invisible but would keep the panel at 120 Hz for ~0.2 s.
        if (from != to) animate(from, to, initialVelocity, LauncherMotion.springSpec(spring, .5f)) { value, _ ->
            val legal = value.coerceIn(low, high)
            show(legal, value - legal)
        }
        show(to, 0f)
    }

    fun nearestPage(): Int = iosPagingTarget(visualOffset(), 0f, pager.currentPage, pager.pageCount,
        { offsetOf(it.toFloat()) })

    /** Leaves no stranded fractional page or overscroll when a job is cancelled by anything but a newer one. */
    fun abandon(token: Int) {
        if (token != generation) return
        effects?.overscroll = 0f
        if (pager.currentPageOffsetFraction != 0f) pager.requestScrollToPage(position().roundToInt().coerceIn(0, lastPage()))
    }
}

/**
 * Feeds the rest of a recognised vertical drag to [session]: finger travel from [originY], the
 * release velocity, or a cancel when the stream ends any other way (a second finger, disposal).
 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.trackVerticalSession(
    session: VerticalDragSession, pointer: androidx.compose.ui.input.pointer.PointerId, originY: Float,
    tracker: VelocityTracker, recognisedAt: androidx.compose.ui.input.pointer.PointerInputChange,
) {
    var ended = false
    var lastTime = recognisedAt.uptimeMillis
    var lastY = recognisedAt.position.y
    try {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == pointer }
            if (change == null || event.changes.any { it.id != pointer && it.pressed }) break
            tracker.addPointerInputChange(change)
            change.consume()
            if (!change.pressed) {
                ended = true
                // The lift's own position counts: a busy frame can leave the last MOVEs undelivered.
                session.drag(change.position.y - originY)
                session.release(releaseVelocity(tracker.calculateVelocity().y, lastTime, lastY,
                    change.uptimeMillis, change.position.y))
                break
            }
            session.drag(change.position.y - originY)
            lastTime = change.uptimeMillis; lastY = change.position.y
        }
    } finally {
        if (!ended) session.cancel()
    }
}

/**
 * The tracker reads a lift that comes more than 40 ms after the last MOVE as a finger that had
 * (nearly) stopped. When the lift is somewhere else, the finger was still moving and only the
 * MOVEs in between were lost (a long frame): that last stretch's speed is the better estimate.
 */
internal fun releaseVelocity(tracked: Float, lastMoveTime: Long, lastMoveY: Float, upTime: Long, upY: Float): Float {
    if (lastMoveTime == Long.MIN_VALUE) return tracked
    val dt = upTime - lastMoveTime
    if (dt <= 40L || upY == lastMoveY) return tracked
    val stretch = (upY - lastMoveY) / dt * 1000f
    return if (kotlin.math.abs(stretch) > kotlin.math.abs(tracked)) stretch else tracked
}

/** Choose an axis before children see the slop-crossing event. A vertical list must not
 * steal a mostly-horizontal swipe simply because one fast sample crossed both thresholds.
 * PagerState still owns the scroll position, layout and cancellation; this drives it like
 * UIScrollView paging (see [PagingDriver]). A touch during a settle catches the page (iOS). */
@Composable
internal fun Modifier.onePageGestures(
    pager: PagerState,
    limits: PageGestureLimits,
    motion: WorkspacePageMotion? = null,
    effects: WorkspaceScrollEffects? = null,
    enabled: Boolean = true,
    canStartDownwardSwipe: (Offset) -> Boolean = { true },
    onDownwardSwipe: ((ShadePanel) -> Unit)? = null,
    /**
     * Interactive alternative to [onDownwardSwipe]: a non-null session keeps receiving the rest of
     * the drag (finger-following, velocity on release); null falls back to [onDownwardSwipe].
     */
    onDownwardDrag: ((ShadePanel) -> VerticalDragSession?)? = null,
    onLeadingOverscroll: (() -> Unit)? = null,
) : Modifier {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentCanStartDownwardSwipe by rememberUpdatedState(canStartDownwardSwipe)
    val currentDownwardSwipe by rememberUpdatedState(onDownwardSwipe)
    val currentDownwardDrag by rememberUpdatedState(onDownwardDrag)
    val currentLeadingOverscroll by rememberUpdatedState(onLeadingOverscroll)
    return nestedScroll(limits).pointerInput(pager, limits, motion, effects) {
        val driver = PagingDriver(pager, motion, effects)
        coroutineScope {
            var motionJob: Job? = null
            fun launchSettle(target: () -> Int) {
                val token = ++driver.generation
                motionJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        pager.scroll(MutatePriority.UserInput) { with(driver) { settle(target(), 0f) } }
                    } catch (cancel: CancellationException) {
                        driver.abandon(token)
                        throw cancel
                    }
                }
            }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (!currentEnabled) return@awaitEachGesture
                // iOS: touching a page in flight (our settle, a bounce or a programmatic page move)
                // stops it under the finger. The catching touch is not also a tap on an icon.
                val caught = motionJob?.isActive == true || pager.isScrollInProgress || driver.overscroll() != 0f
                if (caught) {
                    driver.generation++
                    motionJob?.cancel()
                    motionJob = launch(start = CoroutineStart.UNDISPATCHED) {
                        runCatching { pager.scroll(MutatePriority.UserInput) { } }
                    }
                    down.consume()
                }
                val anchor = pager.currentPage
                limits.anchor = anchor
                limits.pointerDown = true
                val tracker = VelocityTracker().apply { addPointerInputChange(down) }
                var dragPositions: Channel<Float>? = null
                var dragSlopOffset = 0f
                var releaseVelocity = 0f
                var leadingDrag = 0f
                var canceled = false
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || change.isConsumed || limits.editing || event.changes.any { it.id != down.id && it.pressed }) {
                            canceled = true
                            break
                        }
                        // Match Compose's drag tracking, including Android's batched historical
                        // samples and its handling of the final finger-up event.
                        tracker.addPointerInputChange(change)
                        leadingDrag = maxOf(leadingDrag, change.position.x - down.position.x)
                        val terminalRelease = event.type == PointerEventType.Release && change.changedToUp()
                        if (dragPositions == null && (change.pressed || terminalRelease)) {
                            val distance = change.position - down.position
                            if (maxOf(abs(distance.x), abs(distance.y)) > viewConfiguration.touchSlop) {
                                if (abs(distance.y) >= abs(distance.x)) {
                                    // A terminal release can preserve meaningful travel after a
                                    // blocked frame dropped every MOVE. It can page horizontally,
                                    // but must not trigger a vertical system action on finger-up.
                                    val openDownward = change.pressed && distance.y > 0f &&
                                        (currentDownwardSwipe != null || currentDownwardDrag != null) &&
                                        currentCanStartDownwardSwipe(down.position)
                                    if (openDownward) {
                                        change.consume()
                                        val panel = shadePanelForStart(down.position.x, size.width.toFloat())
                                        val session = currentDownwardDrag?.invoke(panel)
                                        if (session == null) currentDownwardSwipe?.invoke(panel)
                                        // iOS Spotlight: the rest of the drag drives it; the slop is
                                        // not a jump, progress starts where the axis was recognised.
                                        else trackVerticalSession(session, down.id, down.position.y +
                                            sign(distance.y) * viewConfiguration.touchSlop, tracker, change)
                                    }
                                    break
                                }
                                val token = ++driver.generation
                                motionJob?.cancel()
                                // Pointer input can outpace layout on a busy frame. Keep the
                                // latest absolute drag position rather than queueing and replaying
                                // every stale delta after the finger has already moved on.
                                val channel = Channel<Float>(Channel.CONFLATED)
                                dragPositions = channel
                                dragSlopOffset = sign(distance.x) * viewConfiguration.touchSlop
                                val viewport = size.width.toFloat().coerceAtLeast(1f)
                                motionJob = launch(start = CoroutineStart.UNDISPATCHED) {
                                    try {
                                        pager.scroll(MutatePriority.UserInput) {
                                            with(driver) {
                                                val rawExcess = track(anchor, channel, viewport)
                                                // Finger px/s → content offset px/s; a stretched rubber band
                                                // moves slower than the finger by the band's slope.
                                                val fingerVelocity = if (canceled) 0f else releaseVelocity
                                                val velocity = -fingerVelocity * if (rawExcess != 0f)
                                                    IosScrollPhysics.rubberBandSlope(rawExcess, viewport) else 1f
                                                val target = if (canceled) nearestPage() else iosPagingTarget(
                                                    visualOffset(), velocity, anchor, pager.pageCount, { offsetOf(it.toFloat()) })
                                                settle(target, velocity)
                                            }
                                        }
                                    } catch (cancel: CancellationException) {
                                        driver.abandon(token)
                                        throw cancel
                                    }
                                    if (!canceled && anchor == 0 && leadingDrag >= 72f * density) {
                                        currentLeadingOverscroll?.invoke()
                                    }
                                }
                                channel.trySend(distance.x - dragSlopOffset)
                            }
                        } else if (dragPositions != null) {
                            dragPositions?.trySend(change.position.x - down.position.x - dragSlopOffset)
                        }
                        if (dragPositions != null || caught) change.consume()
                        if (!change.pressed) {
                            releaseVelocity = tracker.calculateVelocity().x
                            break
                        }
                    }
                } finally {
                    limits.pointerDown = false
                    dragPositions?.close()
                    // A caught page that the finger did not drag (a tap, a hold, a vertical swipe)
                    // still has to come to rest on a page.
                    if (dragPositions == null && caught) launchSettle({ driver.nearestPage() })
                }
            }
        }
    }
}
