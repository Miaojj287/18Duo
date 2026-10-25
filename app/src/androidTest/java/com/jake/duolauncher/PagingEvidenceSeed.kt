package com.jake.duolauncher

import android.appwidget.AppWidgetManager
import android.os.ParcelFileDescriptor
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
 * QA tool, not a regression test (round 3, r3-paging): replaces Home on a QA emulator with a
 * realistic multi-page layout — Today View widgets, 18Duo's own iOS widgets, provider-owned
 * AppWidgetHostViews, a folder and icons in the edge columns — so paging can be screen-recorded.
 * It overwrites the layout, so it only runs when asked for explicitly:
 * `am instrument -w -e seedPagingHome 1 -e class com.jake.duolauncher.PagingEvidenceSeed ...`.
 */
@RunWith(AndroidJUnit4::class)
class PagingEvidenceSeed {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    @Test fun seedRealisticHome() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("seedPagingHome") == "1")
        val packageName = instrumentation.targetContext.packageName
        shell("appwidget grantbind --package $packageName --user 0")
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
                val widgets = MainActivity::class.java.getDeclaredField("widgets").apply { isAccessible = true }
                    .get(activity) as WidgetController
                val providers = AppWidgetManager.getInstance(activity).installedProviders
                fun bind(className: String): Int? {
                    val provider = providers.firstOrNull { it.provider.className == className } ?: return null
                    val id = widgets.host.allocateAppWidgetId()
                    return if (widgets.manager.bindAppWidgetIdIfAllowed(id, provider.profile, provider.provider, null)) id
                    else { widgets.host.deleteAppWidgetId(id); null }
                }
                val apps = m.state.value.apps
                fun app(pkg: String) = apps.firstOrNull { it.packageName == pkg }?.id
                val folder = FolderEntry(newFolderId(), "Utilities",
                    listOfNotNull(app("com.android.stk"), app("org.chromium.webview_shell")))
                val slots = MutableList<String?>(4 * HOME_CELLS) { null }
                fun put(page: Int, row: Int, column: Int, id: String?) { slots[homeCellIndex(page, row * GRID_COLUMNS + column)] = id }
                val placements = mutableListOf<WidgetPlacement>()
                fun widget(id: Int?, page: Int, column: Int, row: Int, spanX: Int, spanY: Int) {
                    if (id != null) placements += WidgetPlacement(placements.size, id, page, column, row, spanX, spanY)
                }
                val small = NativeWidgetSize.SMALL
                val medium = NativeWidgetSize.MEDIUM
                // Today View: an iOS weather card, the provider calendar, clock + battery.
                widget(nativeWidgetId(NativeWidgetKind.WEATHER, medium), -1, 0, 0, 4, 2)
                widget(bind("com.android.calendar.widget.CalendarAppWidgetProvider"), -1, 0, 2, 4, 2)
                widget(nativeWidgetId(NativeWidgetKind.CLOCK, small), -1, 0, 4, 2, 2)
                widget(nativeWidgetId(NativeWidgetKind.BATTERY, small), -1, 2, 4, 2, 2)
                // Home 1: two iOS widgets, then icons and a folder in both edge columns.
                widget(nativeWidgetId(NativeWidgetKind.CLOCK, small), 0, 0, 0, 2, 2)
                widget(nativeWidgetId(NativeWidgetKind.CALENDAR, small), 0, 2, 0, 2, 2)
                put(0, 2, 0, app("com.android.settings")); put(0, 2, 1, app("com.android.deskclock"))
                put(0, 2, 2, app("com.android.calendar")); put(0, 2, 3, app("com.android.camera2"))
                put(0, 3, 0, folder.id); put(0, 3, 3, app("com.android.contacts"))
                // Home 2: a provider analog clock at the leading edge, an iOS battery at the trailing edge.
                widget(bind("com.android.alarmclock.AnalogAppWidgetProvider"), 1, 0, 0, 2, 2)
                widget(nativeWidgetId(NativeWidgetKind.BATTERY, small), 1, 2, 0, 2, 2)
                put(1, 2, 0, app("com.android.documentsui")); put(1, 2, 3, app("com.android.gallery3d"))
                widget(bind("com.android.alarmclock.DigitalAppWidgetProvider"), 1, 0, 3, 4, 2)
                // Home 3: iOS weather + photos, an icon in the last column.
                widget(nativeWidgetId(NativeWidgetKind.WEATHER, small), 2, 0, 0, 2, 2)
                put(2, 0, 3, app("com.android.quicksearchbox"))
                widget(nativeWidgetId(NativeWidgetKind.PHOTOS, medium), 2, 0, 2, 4, 2)
                // Home 4: iOS battery (medium).
                widget(nativeWidgetId(NativeWidgetKind.BATTERY, medium), 3, 0, 0, 4, 2)
                val dock = listOf(app("com.android.dialer"), app("com.android.messaging"), null, null)
                activity.corePreferences.setWidgetPage(true)
                m.restoreLayout(HomeLayout(slots = slots.dropLastWhile { it == null }, dock = dock,
                    widgetPlacements = placements, folders = listOf(folder)))
            }
            Thread.sleep(1_500)
        } finally {
            scenario.close()
        }
    }
}
