package com.jake.duolauncher

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * QA tool, not a regression test (round 4, r4-widgets): replaces Home on a QA emulator with the
 * round-4 widgets — Clock Digital, City Digital, World Clock, Weather, Up Next — and an icon-dense
 * page with an Up Next small whose growth must move icons (and overflow to a new page). It
 * overwrites the layout, so it only runs when asked for explicitly:
 * `am instrument -w -e seedWidgetHome 1 -e class com.jake.duolauncher.WidgetEvidenceSeed ...`.
 */
@RunWith(AndroidJUnit4::class)
class WidgetEvidenceSeed {
    @Test fun seedWidgetHome() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("seedWidgetHome") == "1")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            var model: LauncherModel? = null
            scenario.onActivity { model = ViewModelProvider(it)[LauncherModel::class.java] }
            val end = SystemClock.uptimeMillis() + 15_000
            while (model!!.state.value.loading) {
                assertTrue(SystemClock.uptimeMillis() < end); Thread.sleep(100)
            }
            scenario.onActivity { activity ->
                val m = model!!
                val apps = m.state.value.apps.map { it.id }
                var next = 0
                fun app(): String? = apps.getOrNull(next++)
                val slots = MutableList<String?>(3 * HOME_CELLS) { null }
                fun put(page: Int, row: Int, column: Int) { slots[homeCellIndex(page, row * GRID_COLUMNS + column)] = app() }
                val placements = mutableListOf<WidgetPlacement>()
                fun widget(id: Int, page: Int, column: Int, row: Int) {
                    val span = nativeWidgetSize(id)!!.span
                    placements += WidgetPlacement(placements.size, id, page, column, row, span.width, span.height)
                }
                // Home 1: Clock Digital + City Digital, World Clock medium, Weather small + Up Next small.
                widget(-122, 0, 0, 0); widget(-125, 0, 2, 0)
                widget(-105, 0, 0, 2)
                widget(nativeWidgetId(NativeWidgetKind.WEATHER, NativeWidgetSize.SMALL), 0, 0, 4)
                widget(nativeWidgetId(NativeWidgetKind.CALENDAR, NativeWidgetSize.SMALL), 0, 2, 4)
                // Home 2: Up Next small at the top left with icons all around it (resize → icons flow on).
                widget(nativeWidgetId(NativeWidgetKind.CALENDAR, NativeWidgetSize.SMALL), 1, 0, 0)
                for (row in 0..1) for (column in 2..3) put(1, row, column)
                for (row in 2..4) for (column in 0..3) put(1, row, column)
                put(1, 5, 0); put(1, 5, 1)
                // Home 3: Up Next large, Sunrise and Sunset + World Clock small.
                widget(nativeWidgetId(NativeWidgetKind.CALENDAR, NativeWidgetSize.LARGE), 2, 0, 0)
                widget(-163, 2, 0, 4)
                widget(-120, 2, 2, 4)
                m.restoreLayout(HomeLayout(slots = slots.dropLastWhile { it == null }, dock = List(4) { app() },
                    widgetPlacements = placements))
            }
            Thread.sleep(1_500)
        } finally {
            scenario.close()
        }
    }
}
