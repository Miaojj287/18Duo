package com.jake.duolauncher

/**
 * What the finger holds while it drags an app up from the bottom edge, in dp of the panel the stage
 * covers. Published by [NavigationStage] every frame of the drag so another feature (Split View's
 * hover panes, r8-split) can follow the card without reaching into the stage's springs.
 *
 * @property card where the shrinking app card is drawn right now (it eases toward its switcher
 *   place once the deck engages, so it is not exactly the finger position).
 * @property fingerX the finger's x; [travel] its upward travel from the edge, [dx] its sideways offset.
 * @property velocityY upward finger velocity (dp/s), [velocityX] rightward.
 * @property engaged the finger paused high enough for the switcher (iOS haptic moment).
 * @property deckReveal 0 … 1: how far the switcher deck has come in behind the card.
 * @property task the app being dragged, once the task list has named it.
 */
internal data class SwitcherDragState(
    val card: ZoomBounds,
    val fingerX: Float,
    val travel: Float,
    val dx: Float,
    val velocityY: Float,
    val velocityX: Float,
    val engaged: Boolean,
    val deckReveal: Float,
    val screenWidth: Float,
    val screenHeight: Float,
    val task: RecentTask?,
)

/**
 * Called on the stage thread on every frame of an app drag, and once with `null` when the drag ends
 * (released, cancelled, or the stage reset). Must be cheap: it runs inside the stage's frame.
 */
internal fun interface SwitcherDragListener {
    fun onDrag(state: SwitcherDragState?)
}
