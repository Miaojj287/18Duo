package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test

class Phase22AppLayoutTest {
    private fun app(name: String, label: String = name, work: Boolean = false) =
        Phase22AppCandidate("$name/Main", name, label, work)

    @Test fun `real apps retain dock leading widgets and do not duplicate identities`() {
        val original = HomeLayout(emptyList(), listOf("phone/Main", null, null, null), DEFAULT_WIDGET_PLACEMENTS,
            leadingSlots = List(HOME_CELLS) { if (it == 20) "leading/Main" else null })
        val apps = listOf(app("com.android.settings"), app("com.miui.gallery"), app("org.telegram.messenger"),
            app("com.android.settings", work = true), app("extra"))
        val result = phase22AppLayout(original, apps)
        assertEquals(original.dock, result.dock)
        assertEquals(original.leadingSlots, result.leadingSlots)
        assertEquals(original.widgetPlacements, result.widgetPlacements)
        val placed = result.slots.filterNotNull()
        assertTrue(placed.contains("org.telegram.messenger/Main"))
        assertEquals(placed.distinct().size, placed.size)
        val blocked = original.widgetPlacements.flatMap { it.coveredIndices() }.toSet()
        assertTrue(result.slots.indices.filter { result.slots[it] != null }.none { it in blocked })
    }

    @Test fun `app arrangement is deterministic and supports additional pages`() {
        val apps = (0..85).map { app("package$it", "App %03d".format(it)) }
        val original = HomeLayout(emptyList(), List(4) { null }, DEFAULT_WIDGET_PLACEMENTS)
        val first = phase22AppLayout(original, apps)
        assertEquals(86, first.slots.filterNotNull().size)
        assertEquals(first, phase22AppLayout(first, apps.reversed()))
        assertTrue(first.slots.size > HOME_CELLS)
    }
}
