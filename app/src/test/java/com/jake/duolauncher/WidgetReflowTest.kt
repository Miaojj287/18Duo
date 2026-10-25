package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS widget resize: icons in the way flow on instead of the size being refused (round-3 review §4). */
class WidgetReflowTest {
    private val upNextSmall = nativeWidgetId(NativeWidgetKind.CALENDAR, NativeWidgetSize.SMALL)
    private fun shortcuts(layout: HomeLayout) = (layout.slots + layout.leadingSlots).filterNotNull().sorted()
    private fun page(vararg ids: String?) = ids.toList() + List(HOME_CELLS - ids.size) { null }

    @Test fun `growing to medium pushes the icons beside it along the page in reading order`() {
        // Row 0: widget (cols 0–1) + a b; row 1: widget + c d; row 2: e f _ _.
        val widget = WidgetPlacement(0, upNextSmall, 0, 0, 0, 2, 2)
        val layout = HomeLayout(slots = page(null, null, "a", "b", null, null, "c", "d", "e", "f"), dock = List(4) { null },
            widgetPlacements = listOf(widget))
        val plan = widgetReflowPlan(layout, 0, IosWidgetSize.MEDIUM)!!
        assertEquals(4, plan.moved)
        assertEquals(listOf(0, 0, 4, 2), plan.placement.let { listOf(it.column, it.row, it.spanX, it.spanY) })
        assertEquals(NativeWidgetSize.MEDIUM, nativeWidgetSize(plan.placement.id))
        // a b c d flow into row 2 ahead of e f, which move on; order is kept.
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), plan.layout.slots.drop(8).take(6))
        assertEquals(shortcuts(layout), shortcuts(plan.layout))
        // Undo-safe: one commit; the widget's slot keeps its identity.
        assertEquals(0, plan.placement.slot)
    }

    @Test fun `a gap absorbs the push, so icons after it stay put`() {
        val widget = WidgetPlacement(0, upNextSmall, 0, 0, 0, 2, 2)
        val layout = HomeLayout(slots = page(null, null, "a", null, null, null, null, null, "x", null, "y"), dock = List(4) { null },
            widgetPlacements = listOf(widget))
        val result = reflowedWidgetLayout(layout, 0, IosWidgetSize.MEDIUM)!!
        assertEquals("a", result.slots[8]); assertEquals("x", result.slots[9]); assertEquals("y", result.slots[10])
    }

    @Test fun `overflow goes to a new page right after this one, and later pages shift with their widgets`() {
        val widget = WidgetPlacement(0, upNextSmall, 0, 0, 0, 2, 2)
        val later = WidgetPlacement(1, upNextSmall, 1, 2, 0, 2, 2)
        val full = (0 until HOME_CELLS).map { if (it % GRID_COLUMNS < 2 && it / GRID_COLUMNS < 2) null else "p$it" }
        val layout = HomeLayout(slots = full + page("next"), dock = List(4) { null }, widgetPlacements = listOf(widget, later))
        val result = reflowedWidgetLayout(layout, 0, IosWidgetSize.LARGE)!!
        assertEquals(shortcuts(layout), shortcuts(result))
        assertEquals(3, result.pageCount)
        // The old page 1 (its icon and its widget) is now page 2.
        assertEquals("next", result.slots[2 * HOME_CELLS])
        assertEquals(2, result.placement(1)!!.page)
        // The new page 1 starts with the icons that no longer fit.
        assertNotNull(result.slots[HOME_CELLS])
        assertEquals(listOf(0, 0, 4, 4), result.placement(0)!!.let { listOf(it.column, it.row, it.spanX, it.spanY) })
    }

    @Test fun `other widgets never move, and a size with no free spot stays unavailable`() {
        val widget = WidgetPlacement(0, upNextSmall, 0, 0, 0, 2, 2)
        val neighbour = WidgetPlacement(1, upNextSmall, 0, 2, 0, 2, 2)
        val layout = HomeLayout(slots = emptyList(), dock = List(4) { null }, widgetPlacements = listOf(widget, neighbour))
        assertNull(widgetReflowPlan(layout, 0, IosWidgetSize.MEDIUM))
        assertTrue(!widgetSizeChoices(layout, 0, nativeWidgetSpanConstraints(upNextSmall))[1].enabled)
        // Large fits below the neighbour? No: rows 0–3 at either column overlap it; the menu dims it too.
        assertNull(widgetReflowPlan(layout, 0, IosWidgetSize.LARGE))
    }

    @Test fun `at the bottom of a page the widget moves up only as far as the size needs`() {
        val widget = WidgetPlacement(0, upNextSmall, 0, 0, 4, 2, 2)
        val layout = HomeLayout(slots = page("a", "b", "c", "d"), dock = List(4) { null }, widgetPlacements = listOf(widget))
        val plan = widgetReflowPlan(layout, 0, IosWidgetSize.LARGE)!!
        assertEquals(2, plan.placement.row)
        assertEquals(shortcuts(layout), shortcuts(plan.layout))
        // Rows 0–1 keep a b c d; nothing needed to move.
        assertEquals(0, plan.moved)
    }

    @Test fun `the Today page has no next page, so an overflow there is refused rather than losing icons`() {
        val widget = WidgetPlacement(0, upNextSmall, -1, 0, 0, 2, 2)
        val leading = (0 until HOME_CELLS).map { if (it % GRID_COLUMNS < 2 && it / GRID_COLUMNS < 2) null else "t$it" }
        val layout = HomeLayout(slots = emptyList(), dock = List(4) { null }, widgetPlacements = listOf(widget), leadingSlots = leading)
        assertNull(widgetReflowPlan(layout, 0, IosWidgetSize.MEDIUM))
        // With room left on the page it reflows like any other page.
        val roomy = layout.copy(leadingSlots = leading.mapIndexed { i, id -> if (i >= 16) null else id })
        val plan = widgetReflowPlan(roomy, 0, IosWidgetSize.MEDIUM)!!
        assertEquals(shortcuts(roomy), shortcuts(plan.layout))
    }

    @Test fun `inserting a page keeps every icon and shifts later widgets`() {
        val layout = HomeLayout(slots = page("a") + page("b"), dock = List(4) { null },
            widgetPlacements = listOf(WidgetPlacement(3, 42, 1, 0, 2, 2, 2)))
        val result = insertHomePageAfter(layout, 0, listOf("x", "y"))
        assertEquals(listOf("a", "x", "y", "b"), result.slots.filterNotNull())
        assertEquals("x", result.slots[HOME_CELLS]); assertEquals("b", result.slots[2 * HOME_CELLS])
        assertEquals(2, result.placement(3)!!.page)
    }
}
