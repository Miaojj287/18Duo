package com.jake.duolauncher

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

/** Core.17 fix wave: icon registry lifetime, folder naming, widget size row. */
class EditFixWaveTest {
    @Test fun `an icon rectangle leaves the registry with the tile that wrote it`() {
        val registry = HomeIconRegistry()
        val homeTile = Any()
        val dockTile = Any()
        registry.put("app", Rect(0f, 0f, 54f, 54f), homeTile)
        assertEquals(Rect(0f, 0f, 54f, 54f), registry["app"])
        // The app moves into the Dock: the new tile writes before the old one is disposed.
        registry.put("app", Rect(360f, 200f, 404f, 244f), dockTile)
        registry.release("app", homeTile)
        assertEquals("a stale writer never clears the live rectangle", Rect(360f, 200f, 404f, 244f), registry["app"])
        // Fold/unfold or a page scrolled away disposes the tile: nothing stale is left behind.
        registry.release("app", dockTile)
        assertNull(registry["app"])
        assertEquals(0, registry.size)
    }

    @Test fun `a new folder is named after its apps' category, the app that stays decides`() {
        assertEquals(LibraryCategory.UTILITIES, suggestedFolderCategory(LibraryCategory.UTILITIES, LibraryCategory.UTILITIES))
        assertEquals(LibraryCategory.SOCIAL, suggestedFolderCategory(LibraryCategory.SOCIAL, LibraryCategory.UTILITIES))
        assertEquals(LibraryCategory.CREATIVITY, suggestedFolderCategory(LibraryCategory.OTHER, LibraryCategory.CREATIVITY))
        assertEquals(LibraryCategory.GAMES, suggestedFolderCategory(null, LibraryCategory.GAMES))
        assertNull(suggestedFolderCategory(LibraryCategory.OTHER, LibraryCategory.OTHER))
        assertNull(suggestedFolderCategory(LibraryCategory.SUGGESTIONS, null))
        // Settings + Clock (both "工具" in the App Library) → "工具"/"Utilities", not "文件夹".
        val settings = categorizeApp("com.android.settings", AndroidAppCategory.UNDEFINED, isSystemApp = true)
        val clock = categorizeApp("com.android.deskclock", AndroidAppCategory.UNDEFINED, isSystemApp = true)
        assertEquals(LibraryCategory.UTILITIES.title, suggestedFolderTitle(settings, clock))
        assertEquals(uxText("文件夹", "Folder"), suggestedFolderTitle(null, null))
    }

    private val calendarSmall = nativeWidgetId(NativeWidgetKind.CALENDAR, NativeWidgetSize.SMALL)

    @Test fun `native widget menu offers the widget's iOS sizes, moving left only as far as medium needs`() {
        val widget = WidgetPlacement(slot = 0, id = calendarSmall, page = 0, column = 2, row = 0, spanX = 2, spanY = 2)
        val layout = HomeLayout(slots = listOf("a", "b", null, null, "c"), dock = List(4) { null },
            widgetPlacements = listOf(widget))
        val choices = widgetSizeChoices(layout, 0, nativeWidgetSpanConstraints(calendarSmall))
        // Calendar ▸ Up Next comes in small, medium and large (iOS).
        assertEquals(listOf(IosWidgetSize.SMALL, IosWidgetSize.MEDIUM, IosWidgetSize.LARGE), choices.map { it.size })
        assertTrue(choices[0].selected)
        // Columns 0–1 of rows 0–1 hold apps: iOS moves them out of the way instead of refusing (round 4).
        assertEquals(0, choices[1].column)
        assertTrue(choices[1].enabled)
        assertEquals(3, choices[1].movedIcons)

        val free = layout.copy(slots = listOf(null, null, null, null, null, null, null, null, "c"))
        val medium = widgetSizeChoices(free, 0, nativeWidgetSpanConstraints(calendarSmall))[1]
        assertEquals(0, medium.column)
        val resized = resizedWidgetLayout(free, 0, IosWidgetSize.MEDIUM, 0)!!.placement(0)!!
        assertEquals(listOf(0, 0, 4, 2), listOf(resized.column, resized.row, resized.spanX, resized.spanY))
        assertEquals(NativeWidgetSize.MEDIUM, nativeWidgetSize(resized.id))
    }

    @Test fun `a widget with a single size, or unknown constraints, shows no size row`() {
        val widget = WidgetPlacement(0, 42, 0, 0, 0, 4, 2)
        val layout = HomeLayout(slots = emptyList(), dock = List(4) { null }, widgetPlacements = listOf(widget))
        assertTrue(widgetSizeChoices(layout, 0, null).isEmpty())
        val fixed = WidgetSpanConstraints(WidgetSpan(4, 2), WidgetSpan(4, 2), WidgetSpan(4, 2), false, false)
        assertTrue(widgetSizeChoices(layout, 0, fixed).isEmpty())
        val free = WidgetSpanConstraints(WidgetSpan(4, 2), WidgetSpan(2, 2), WidgetSpan(4, 4), true, true)
        assertEquals(IosWidgetSize.entries, widgetSizeChoices(layout, 0, free).map { it.size })
        assertTrue(widgetSizeChoices(layout, 0, free).all { it.enabled })
    }
}
