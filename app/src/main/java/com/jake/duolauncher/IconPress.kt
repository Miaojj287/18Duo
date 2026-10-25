package com.jake.duolauncher

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import kotlinx.coroutines.launch

/**
 * iOS Home Screen icon press (round 11, goal row A1). SpringBoard never scales a touched icon:
 * it lays a dark highlight over the artwork the instant the finger lands and fades it out when
 * the finger lifts. Everything that shows an app tile — Home, the Dock, folder icons, the App
 * Library, Spotlight results, the recent-apps Dock — uses this one modifier so a press feels the
 * same everywhere. The highlight is drawn source-atop, so only the tile's own pixels darken
 * (the masked corners of a pre-rounded icon bitmap stay clear).
 *
 * Reduce Motion keeps the highlight (it is feedback, not motion); its fade is the shared opacity
 * dissolve, which the system animator scale makes instant.
 */
internal const val ICON_HIGHLIGHT_ALPHA = .32f

/** Darkness laid over the artwork for a press [progress] of 0..1. */
internal fun iconHighlightAlpha(progress: Float): Float =
    if (!progress.isFinite()) 0f else ICON_HIGHLIGHT_ALPHA * progress.coerceIn(0f, 1f)

/** [enabled] false (jiggle mode: a touch grabs the icon, it does not press it) draws nothing. */
internal fun Modifier.iosIconHighlight(interactionSource: InteractionSource, enabled: Boolean = true): Modifier =
    this then IconHighlightElement(interactionSource, enabled)

private data class IconHighlightElement(val source: InteractionSource, val enabled: Boolean) :
    ModifierNodeElement<IconHighlightNode>() {
    override fun create() = IconHighlightNode(source, enabled)
    override fun update(node: IconHighlightNode) {
        if (node.source !== source) { node.source = source; node.restart() }
        if (node.enabled != enabled) { node.enabled = enabled; node.invalidateDraw() }
    }
    override fun InspectorInfo.inspectableProperties() { name = "iosIconHighlight" }
}

private class IconHighlightNode(var source: InteractionSource, var enabled: Boolean) : Modifier.Node(), DrawModifierNode {
    private val amount = Animatable(0f)
    private val layerPaint = Paint()
    private var collector: kotlinx.coroutines.Job? = null

    override fun onAttach() { restart() }

    override fun onDetach() { collector?.cancel(); collector = null }

    fun restart() {
        collector?.cancel()
        collector = coroutineScope.launch {
            var presses = 0
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses++
                    is PressInteraction.Release, is PressInteraction.Cancel -> presses = (presses - 1).coerceAtLeast(0)
                    else -> return@collect
                }
                launch {
                    if (presses > 0) { amount.snapTo(1f); invalidateDraw() }
                    else amount.animateTo(0f, LauncherMotion.dissolveOut) { invalidateDraw() }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val alpha = if (enabled) iconHighlightAlpha(amount.value) else 0f
        if (alpha <= .001f) { drawContent(); return }
        // One layer per pressed icon, only while it is pressed: the darkening must stay inside
        // the artwork, which the pre-masked bitmap draws over a transparent background.
        val canvas = drawContext.canvas
        canvas.saveLayer(Rect(Offset.Zero, size), layerPaint)
        drawContent()
        drawRect(Color.Black, alpha = alpha, blendMode = BlendMode.SrcAtop)
        canvas.restore()
    }
}
