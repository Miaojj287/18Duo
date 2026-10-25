package com.jake.duolauncher

import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetAppearanceTest {
    @Test fun `automatic widgets follow the system appearance and explicit styles win`() {
        assertEquals(WidgetTone.LIGHT, resolveWidgetTone(WidgetStyle.AUTO, systemDark = false))
        assertEquals(WidgetTone.DARK, resolveWidgetTone(WidgetStyle.AUTO, systemDark = true))
        assertEquals(WidgetTone.LIGHT, resolveWidgetTone(WidgetStyle.LIGHT, systemDark = true))
        assertEquals(WidgetTone.DARK, resolveWidgetTone(WidgetStyle.DARK, systemDark = false))
        assertEquals(WidgetTone.CLEAR, resolveWidgetTone(WidgetStyle.CLEAR, systemDark = false))
        WidgetStyle.entries.forEach { assertEquals(it, WidgetStyle.fromKey(it.key)) }
        // r21: nothing saved = Dark, as on the real iPhone Duo.
        assertEquals(WidgetStyle.DARK, WidgetStyle.fromKey(null))
        assertEquals(WidgetStyle.AUTO, WidgetStyle.fromKey("tinted"))
    }

    @Test fun `light and dark widgets are opaque cards with iOS label contrast`() {
        for (tone in listOf(WidgetTone.LIGHT, WidgetTone.DARK)) {
            val colors = widgetColors(tone)
            assertEquals("$tone background must not let the wallpaper through", 1f, colors.background.alpha, 0f)
            assertFalse(colors.clear)
            assertTrue(contrastRatio(colors.primary, colors.background) >= 15f)
            // iOS secondaryLabel sits around 3.5:1 on white; keep at least that.
            assertTrue(contrastRatio(colors.secondary.compositeOver(colors.background), colors.background) >= 3f)
            assertTrue(contrastRatio(colors.red, colors.background) >= 3f)
        }
        assertEquals(Color.White, widgetColors(WidgetTone.LIGHT).background)
        assertEquals(Color(0xFF1C1C1E), widgetColors(WidgetTone.DARK).background)
    }

    @Test fun `clear widgets keep white content legible on the bright sky and the dark band`() {
        val clear = widgetColors(WidgetTone.CLEAR)
        assertTrue(clear.clear)
        assertTrue(clear.background.alpha in 0.2f..0.5f)
        val sky = Color(0xFFC9D2DA); val band = Color(0xFF3A342E)
        for (backdrop in listOf(sky, band)) {
            assertTrue(contrastRatio(clear.primary, clear.background.compositeOver(backdrop)) >= 2.6f)
        }
    }

    @Test fun `wallpaper mean colour and system night mode`() {
        assertEquals(0xFF808080.toInt(), wallpaperMeanColor(IntArray(0)))
        assertEquals(0xFF7F7F7F.toInt(), wallpaperMeanColor(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())))
        assertEquals(0xFF102030.toInt(), wallpaperMeanColor(IntArray(4) { 0xFF102030.toInt() }))
        assertTrue(systemNightMode(Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL))
        assertFalse(systemNightMode(Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_NORMAL))
        assertFalse(systemNightMode(Configuration.UI_MODE_NIGHT_UNDEFINED))
    }

    @Test fun `a stale locale is acted on once per new locale list`() {
        val guard = StaleLocaleGuard<String>()
        assertFalse(guard.shouldRecreate("zh-CN", "zh-CN"))
        assertTrue(guard.shouldRecreate("en-US", "zh-CN"))
        // Still stale after the recreate (e.g. a device that keeps them apart): never loop.
        assertFalse(guard.shouldRecreate("en-US", "zh-CN"))
        assertTrue(guard.shouldRecreate("zh-CN", "en-US"))
    }

    @Test fun `gallery lists each app once and hides system providers`() {
        assertTrue(isHiddenWidgetPackage("com.android.systemui"))
        assertTrue(isHiddenWidgetPackage("android"))
        assertFalse(isHiddenWidgetPackage("com.android.calendar"))
        assertFalse(isHiddenWidgetPackage("com.miui.weather2"))
        assertEquals(NativeWidgetKind.CALENDAR, nativeKindForWidgetApp("com.android.calendar", "Whatever"))
        assertEquals(NativeWidgetKind.CLOCK, nativeKindForWidgetApp("com.example.clock", "时钟"))
        assertEquals(NativeWidgetKind.WEATHER, nativeKindForWidgetApp("com.miui.weather2", "天气"))
        assertNull(nativeKindForWidgetApp("com.android.quicksearchbox", "Search"))
        val owners = nativeKindOwners(listOf("com.android.calendar" to "Calendar", "com.google.android.calendar" to "Calendar",
            "com.android.deskclock" to "Clock", "com.android.quicksearchbox" to "Search"),
            listOf(NativeWidgetKind.CALENDAR, NativeWidgetKind.CLOCK, NativeWidgetKind.WEATHER))
        assertEquals(mapOf("com.android.calendar" to NativeWidgetKind.CALENDAR, "com.android.deskclock" to NativeWidgetKind.CLOCK), owners)
        // A kind 18Duo doesn't offer is never claimed.
        assertTrue(nativeKindOwners(listOf("com.miui.gallery" to "相册"), listOf(NativeWidgetKind.CLOCK)).isEmpty())
    }

    @Test fun `the featured row leads with live widgets`() {
        val featured = featuredNativeWidgetIds()
        assertEquals(NativeWidgetKind.CLOCK, nativeWidgetKind(featured.first()))
        assertTrue(featured.indexOfFirst { nativeWidgetKind(it) == NativeWidgetKind.WEATHER } > 2)
    }
}
