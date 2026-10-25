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
 * QA tool, not a regression test (round 9): Today View with a World Clock and a Clock widget (both open
 * the Clock app, the widget → app zoom and the swipe back into the widget), and a first Home page of
 * icons for long drags. It overwrites the layout, so it only runs when asked for explicitly:
 * `am instrument -w -e seedWidgetLaunch 1 -e class com.jake.duolauncher.WidgetLaunchSeed ...`.
 */
@RunWith(AndroidJUnit4::class)
class WidgetLaunchSeed {
    @Test fun seedWidgetLaunch() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("seedWidgetLaunch") == "1")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            var model: LauncherModel? = null
            scenario.onActivity { model = ViewModelProvider(it)[LauncherModel::class.java] }
            val end = SystemClock.uptimeMillis() + 15_000
            while (model!!.state.value.loading) {
                assertTrue(SystemClock.uptimeMillis() < end); Thread.sleep(100)
            }
            scenario.onActivity {
                val m = model!!
                val apps = m.state.value.apps.filter { it.isPersonal }.map { it.id }
                val slots = MutableList<String?>(2 * HOME_CELLS) { null }
                apps.take(HOME_CELLS + 6).forEachIndexed { i, id -> slots[i] = id }
                val placements = mutableListOf<WidgetPlacement>()
                fun widget(id: Int, page: Int, column: Int, row: Int) {
                    val span = nativeWidgetSize(id)!!.span
                    placements += WidgetPlacement(placements.size, id, page, column, row, span.width, span.height)
                }
                widget(-105, -1, 0, 0)
                widget(-122, -1, 0, 2)
                m.restoreLayout(HomeLayout(slots = slots.dropLastWhile { it == null }, dock = List(4) { null },
                    widgetPlacements = placements))
            }
            Thread.sleep(1_500)
        } finally {
            scenario.close()
        }
    }
}
