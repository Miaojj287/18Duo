package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test

class WidgetGalleryModelTest {
    private fun constraints(preferred: WidgetSpan, min: WidgetSpan, max: WidgetSpan, h: Boolean = true, v: Boolean = true,
        fits: Boolean = true) = WidgetSpanConstraints(preferred, min, max, h, v, fits)

    @Test fun resizableProvidersOfferEveryIosFamilyTheyCanTake() {
        val any = constraints(WidgetSpan(4, 2), WidgetSpan(2, 1), WidgetSpan(4, 4))
        assertEquals(listOf(WidgetSpan(2, 2), WidgetSpan(4, 2), WidgetSpan(4, 4)), widgetSizeVariants(any))
        val noLarge = constraints(WidgetSpan(4, 2), WidgetSpan(2, 2), WidgetSpan(4, 3))
        assertEquals(listOf(WidgetSpan(2, 2), WidgetSpan(4, 2)), widgetSizeVariants(noLarge))
    }

    @Test fun fixedAxesKeepThePreferredValue() {
        val fixedHeight = constraints(WidgetSpan(4, 2), WidgetSpan(2, 2), WidgetSpan(4, 4), v = false)
        assertEquals(listOf(WidgetSpan(2, 2), WidgetSpan(4, 2)), widgetSizeVariants(fixedHeight))
        val fixed = constraints(WidgetSpan(4, 4), WidgetSpan(4, 4), WidgetSpan(4, 4), h = false, v = false)
        assertEquals(listOf(WidgetSpan(4, 4)), widgetSizeVariants(fixed))
    }

    @Test fun oddSizedProvidersKeepTheirOwnSizeAndMisfitsHaveNone() {
        val searchBar = constraints(WidgetSpan(4, 1), WidgetSpan(3, 1), WidgetSpan(4, 1))
        assertEquals(listOf(WidgetSpan(4, 1)), widgetSizeVariants(searchBar))
        assertTrue(widgetSizeVariants(null).isEmpty())
        assertTrue(widgetSizeVariants(constraints(WidgetSpan(2, 2), WidgetSpan(5, 2), WidgetSpan(5, 2), fits = false)).isEmpty())
    }

    @Test fun previewsKeepTheHomeGridAspect() {
        val (sw, sh) = galleryPreviewSize(WidgetSpan(2, 2))
        assertEquals(150f, sw, .01f); assertEquals(150f, sh, .01f)
        val (mw, mh) = galleryPreviewSize(WidgetSpan(4, 2))
        assertEquals(2 * 150f + 16f, mw, .01f); assertEquals(150f, mh, .01f)
        val (lw, lh) = galleryPreviewSize(WidgetSpan(4, 4))
        assertEquals(lw, lh, .01f)
    }

    @Test fun featuredGridPairsSmallWidgetsAndGivesMediumARow() {
        val small = nativeWidgetId(NativeWidgetKind.CLOCK, NativeWidgetSize.SMALL)
        val small2 = nativeWidgetId(NativeWidgetKind.BATTERY, NativeWidgetSize.SMALL)
        val medium = nativeWidgetId(NativeWidgetKind.WEATHER, NativeWidgetSize.MEDIUM)
        assertEquals(listOf(listOf(medium), listOf(small, small2)), featuredRows(listOf(medium, small, small2)))
        assertEquals(listOf(listOf(small), listOf(medium)), featuredRows(listOf(small, medium)))
        // The unfolded sheet (~590dp) holds seven grid columns: a medium beside a small.
        assertEquals(7, featuredColumns(590f))
        assertEquals(4, featuredColumns(393f))
        assertEquals(listOf(listOf(medium, small), listOf(small2)), featuredRows(listOf(medium, small, small2), featuredColumns(590f)))
        assertTrue(featuredNativeWidgetIds().all(::isNativeWidgetId))
        assertEquals(featuredNativeWidgetIds().size, featuredNativeWidgetIds().toSet().size)
    }

    @Test fun todayViewStaysUnlessExplicitlyHidden() {
        assertTrue(todayViewVisible("AUTO", hasContent = false))
        assertTrue(todayViewVisible("SHOW", hasContent = false))
        assertFalse(todayViewVisible("HIDE", hasContent = true))
    }

    @Test fun todayContentRowsCoverWidgetsAndLegacyShortcuts() {
        assertEquals(0, todayContentRows(emptyList(), List(HOME_CELLS) { null }))
        val widget = WidgetPlacement(0, -101, -1, 0, 2, 4, 2)
        assertEquals(4, todayContentRows(listOf(widget, widget.copy(page = 0, row = 5)), emptyList()))
        val slots = MutableList<String?>(HOME_CELLS) { null }.also { it[9] = "app" }
        assertEquals(3, todayContentRows(emptyList(), slots))
    }
}
