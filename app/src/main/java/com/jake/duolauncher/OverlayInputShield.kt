package com.jake.duolauncher

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize

/**
 * Root-coordinate rectangles of everything that floats above Home (menus, their scrims, alerts,
 * pickers, Spotlight, the Edit/Done and Today capsules).
 *
 * Compose already keeps a touch on an overlay away from the tiles *beside* it in the tree, but
 * Home's long-press-to-lift detector sits on the root (so paging cannot dispose a drag) and sees
 * every pointer: before round 3 a long press on the jiggle "编辑" menu lifted the app icon under the
 * menu row (owner item 2). Detectors above the overlays consult [blocks] before they start.
 */
@Stable
internal class OverlayInputShield {
    private val areas = HashMap<Any, Rect>()

    fun put(owner: Any, area: Rect) { areas[owner] = area }
    fun release(owner: Any) { areas.remove(owner) }

    /** True when a pointer that went down at [point] (root px) belongs to an overlay. */
    fun blocks(point: Offset): Boolean = areas.values.any { it.contains(point) }
    val size: Int get() = areas.size
}

internal val LocalOverlayInputShield = staticCompositionLocalOf<OverlayInputShield?> { null }

/**
 * Makes this element an input shield: its bounds are registered with the [LocalOverlayInputShield]
 * while it is attached, and it is a pointer-input target itself, so Compose's hit test stops at it
 * and nothing drawn beneath it (sibling tiles, the pager's tap/long-press detectors) is offered the
 * touch. It does not consume anything: the overlay's own buttons keep normal press/cancel
 * semantics. [enabled] = false removes both effects (e.g. a menu animating out).
 */
internal fun Modifier.overlayInputShield(enabled: Boolean = true): Modifier =
    if (!enabled) this else this then OverlayInputShieldElement

private object OverlayInputShieldElement : ModifierNodeElement<OverlayInputShieldNode>() {
    override fun create() = OverlayInputShieldNode()
    override fun update(node: OverlayInputShieldNode) = Unit
    override fun InspectorInfo.inspectableProperties() { name = "overlayInputShield" }
    override fun hashCode(): Int = 0x5ad1e1d
    override fun equals(other: Any?): Boolean = other === this
}

private class OverlayInputShieldNode : Modifier.Node(), GlobalPositionAwareModifierNode, PointerInputModifierNode,
    CompositionLocalConsumerModifierNode {
    private var registry: OverlayInputShield? = null

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (!coordinates.isAttached) return
        val shield = currentValueOf(LocalOverlayInputShield) ?: return
        if (registry !== shield) registry?.release(this)
        registry = shield
        shield.put(this, coordinates.boundsInRoot())
    }

    override fun onDetach() {
        registry?.release(this)
        registry = null
    }

    // Being a pointer target is the point; the overlay's children handle (and consume) the events.
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) = Unit
    override fun onCancelPointerInput() = Unit
}
