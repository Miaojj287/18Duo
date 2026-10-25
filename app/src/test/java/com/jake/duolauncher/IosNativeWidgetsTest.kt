package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class IosNativeWidgetsTest {
    @Test fun `every widget has a distinct reserved id that round-trips through its key`() {
        assertEquals(emptyList<String>(), iosWidgetRegistryProblems(iosWidgetSpecs))
        val ids = nativeWidgetCatalog().map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(ids.all { it in NATIVE_WIDGET_ID_RANGE && it !in BUILTIN_WIDGET_IDS && it != NEEDS_BINDING_WIDGET })
        nativeWidgetCatalog().forEach { entry ->
            val key = nativeWidgetKey(entry.id)
            assertEquals(entry.spec.key, key)
            assertTrue(key!!.startsWith("native:"))
            assertEquals(entry.id, parseNativeWidgetKey(key))
            assertEquals(entry.kind, nativeWidgetKind(entry.id))
            assertEquals(entry.size, nativeWidgetSize(entry.id))
        }
    }

    @Test fun `widgets placed by earlier builds keep their ids and keys`() {
        // Core.16/17 persisted "native:<app>:<size>" with ids BASE − (kind·4 + size).
        val legacy = mapOf("native:battery:small" to -100, "native:battery:medium" to -101,
            "native:clock:small" to -104, "native:clock:medium" to -105,
            "native:calendar:small" to -108, "native:calendar:medium" to -109,
            "native:weather:small" to -112, "native:weather:medium" to -113,
            "native:photos:small" to -116, "native:photos:medium" to -117)
        legacy.forEach { (key, id) ->
            assertEquals(key, id, parseNativeWidgetKey(key))
            assertEquals(key, nativeWidgetKey(id))
        }
        assertEquals(NATIVE_WIDGET_ID_BASE, nativeWidgetId(NativeWidgetKind.BATTERY, NativeWidgetSize.SMALL))
    }

    @Test fun `registry problems are caught`() {
        val render: @androidx.compose.runtime.Composable IosWidgetContext.() -> Unit = {}
        fun spec(id: Int, widget: String, family: NativeWidgetSize, key: String = "native:$widget:${family.key}") =
            IosWidgetSpec(id, NativeWidgetKind.CLOCK, widget, family, "", "", "", "", key = key, render = render)
        assertTrue(iosWidgetRegistryProblems(listOf(spec(-120, "a", NativeWidgetSize.SMALL), spec(-120, "b", NativeWidgetSize.SMALL))).isNotEmpty())
        assertTrue(iosWidgetRegistryProblems(listOf(spec(-120, "a", NativeWidgetSize.SMALL), spec(-121, "b", NativeWidgetSize.SMALL, "native:a:small"))).isNotEmpty())
        assertTrue(iosWidgetRegistryProblems(listOf(spec(-20, "a", NativeWidgetSize.SMALL))).isNotEmpty())
        assertTrue(iosWidgetRegistryProblems(listOf(spec(-120, "a", NativeWidgetSize.SMALL), spec(-121, "a", NativeWidgetSize.LARGE))).isNotEmpty())
        assertEquals(emptyList<String>(), iosWidgetRegistryProblems(listOf(spec(-120, "a", NativeWidgetSize.MEDIUM), spec(-121, "a", NativeWidgetSize.LARGE))))
    }

    @Test fun `unknown keys and stray reserved ids are rejected instead of guessed`() {
        assertNull(parseNativeWidgetKey(null))
        assertNull(parseNativeWidgetKey(""))
        assertNull(parseNativeWidgetKey("native:battery"))
        assertNull(parseNativeWidgetKey("native:stocks:small"))
        assertNull(parseNativeWidgetKey("native:battery:huge"))
        assertNull(parseNativeWidgetKey("widget:battery:small"))
        assertNull(nativeWidgetKind(NATIVE_WIDGET_ID_BASE - 1_000))
        assertNull(nativeWidgetKind(-199))
        assertNull(nativeWidgetKind(7))
        assertFalse(isNativeWidgetId(CLOCK_WIDGET))
        assertFalse(isNativeWidgetId(EMPTY_WIDGET))
    }

    @Test fun `persisted placements prefer the readable key and skip ids from newer builds`() {
        val clock = nativeWidgetId(NativeWidgetKind.CLOCK, NativeWidgetSize.MEDIUM)
        assertEquals(clock, resolvePersistedWidgetId(clock, null))
        assertEquals(clock, resolvePersistedWidgetId(-1, "native:clock:medium"))
        assertEquals(clock, resolvePersistedWidgetId(clock, "native:clock:medium"))
        // A host widget id is passed through untouched, with or without a stale key.
        assertEquals(42, resolvePersistedWidgetId(42, null))
        assertEquals(NEEDS_BINDING_WIDGET, resolvePersistedWidgetId(NEEDS_BINDING_WIDGET, null))
        // A reserved id without a key that no longer resolves is dropped, never shown as a host widget.
        assertNull(resolvePersistedWidgetId(-199, null))
        assertEquals(-250, resolvePersistedWidgetId(-250, null))
        assertNull(resolvePersistedWidgetId(clock, "native:stocks:small"))
    }

    @Test fun `resizing keeps native ids in step with iOS small and medium spans`() {
        val battery = nativeWidgetId(NativeWidgetKind.BATTERY, NativeWidgetSize.SMALL)
        val mediumBattery = nativeWidgetId(NativeWidgetKind.BATTERY, NativeWidgetSize.MEDIUM)
        assertEquals(mediumBattery, nativeWidgetIdForSpan(battery, 4, 2))
        assertEquals(mediumBattery, nativeWidgetIdForSpan(battery, 3, 2))
        assertEquals(battery, nativeWidgetIdForSpan(mediumBattery, 2, 2))
        assertEquals(17, nativeWidgetIdForSpan(17, 4, 2))
        assertEquals(2, nativeWidgetSnapSpanX(1)); assertEquals(2, nativeWidgetSnapSpanX(2))
        assertEquals(4, nativeWidgetSnapSpanX(3)); assertEquals(4, nativeWidgetSnapSpanX(4))
        val constraints = nativeWidgetSpanConstraints(battery)!!
        assertEquals(WidgetSpan(2, 2), constraints.preferred)
        assertEquals(WidgetSpan(2, 2), constraints.minimum)
        assertTrue(constraints.maximum.width == 4)
        assertTrue(constraints.canResizeHorizontally)
        assertNull(nativeWidgetSpanConstraints(42))
        val layout = HomeLayout(List(HOME_CELLS) { null }, List(4) { null },
            widgetPlacements = listOf(WidgetPlacement(0, battery, 0, 0, 0, 2, 2)))
        val resized = resizeWidget(layout, 0, 4, 2)
        assertEquals(mediumBattery, resized.placement(0)!!.id)
        assertEquals(4, resized.placement(0)!!.spanX)
        // A free-form 3-wide drag lands on the medium size, never a 3-wide card.
        assertEquals(4, resizeWidget(layout, 0, 3, 2).placement(0)!!.spanX)
    }

    @Test fun `free-form spans land on the nearest size the widget has`() {
        val all = NativeWidgetSize.entries
        assertEquals(NativeWidgetSize.SMALL, nativeWidgetFamilyForSpan(all, 2, 2))
        assertEquals(NativeWidgetSize.MEDIUM, nativeWidgetFamilyForSpan(all, 3, 2))
        assertEquals(NativeWidgetSize.LARGE, nativeWidgetFamilyForSpan(all, 4, 3))
        assertEquals(NativeWidgetSize.MEDIUM, nativeWidgetFamilyForSpan(listOf(NativeWidgetSize.SMALL, NativeWidgetSize.MEDIUM), 4, 4))
        assertEquals(NativeWidgetSize.MEDIUM, nativeWidgetFamilyForSpan(listOf(NativeWidgetSize.MEDIUM, NativeWidgetSize.LARGE), 2, 2))
        assertNull(nativeWidgetFamilyForSpan(emptyList(), 2, 2))
    }

    @Test fun `cards scale iPhone points uniformly and share the small widget's corner`() {
        // Cover: 143 dp small, 302.6 dp medium, 302.6 × 324 dp large (4 rows of 90.5 dp).
        assertEquals(143f / 158f, widgetPointScale(NativeWidgetSize.SMALL, 143f, 143f), 1e-4f)
        assertEquals(302.6f / 338f, widgetPointScale(NativeWidgetSize.MEDIUM, 302.6f, 143f), 1e-4f)
        assertEquals(302.6f / 338f, widgetPointScale(NativeWidgetSize.LARGE, 302.6f, 324f), 1e-4f)
        val small = nativeWidgetCornerRadius(143f, 143f)
        assertEquals(24.3f, small, .1f)
        assertEquals(small, nativeWidgetCornerRadius(302.6f, 143f), .01f)
        assertEquals(small, nativeWidgetCornerRadius(302.6f, 324f), 1.5f)
    }

    @Test fun `placing a native widget follows the same overlap rules as host widgets`() {
        val photos = nativeWidgetId(NativeWidgetKind.PHOTOS, NativeWidgetSize.SMALL)
        val occupied = HomeLayout(listOf("app"), List(4) { null })
        assertEquals(occupied, placeWidget(occupied, WidgetPlacement(0, photos, 0, 0, 0, 2, 2)))
        val placed = placeWidget(HomeLayout(emptyList(), List(4) { null }), WidgetPlacement(0, photos, 0, 2, 0, 2, 2))
        assertEquals(photos, placed.placement(0)!!.id)
        assertEquals(placed, withoutDemoWidgets(placed))
    }

    @Test fun `battery ring colours follow iOS thresholds`() {
        // r3-widgets-wb: green, red at or below 20 % unless charging, yellow only in Low Power Mode (iOS).
        fun color(percent: Int?, charging: Boolean = false, lowPower: Boolean = false) =
            appleBatteryColor(BatteryStatus(percent, charging, lowPower), monochrome = false)
        assertEquals(color(100), color(21))
        assertEquals(color(20), color(0))
        assertTrue(color(80) != color(10) && color(80) != color(80, lowPower = true))
        assertEquals(color(80), color(10, charging = true))
        assertEquals(color(null), color(null))
    }

    @Test fun `wallpaper luminance decides dark or light glass`() {
        assertTrue(wallpaperIsDark(IntArray(0)))
        assertTrue(wallpaperIsDark(IntArray(16) { 0xFF101418.toInt() }))
        assertFalse(wallpaperIsDark(IntArray(16) { 0xFFF4F1EA.toInt() }))
        assertEquals(1f, wallpaperLuminance(intArrayOf(0xFFFFFFFF.toInt())), .001f)
        assertEquals(0f, wallpaperLuminance(intArrayOf(0xFF000000.toInt())), .001f)
    }
}
