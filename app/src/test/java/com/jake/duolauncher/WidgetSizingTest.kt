package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSizingTest {
    @Test fun nativeWeatherFitKeepsBothAxesUniformAndInsideIconBounds() {
        val fit = nativeWidgetFit(789, 369, 550f)
        assertEquals(550, fit.height)
        assertEquals(789f, fit.width * fit.scale, .6f)
        assertEquals(369f, fit.height * fit.scale, .6f)
        assertEquals(NativeWidgetFit(789, 600, 1f), nativeWidgetFit(789, 600, 550f))
    }

    @Test fun referenceCardsAreMeasuredSquaresOnEveryPageIncludingToday() {
        val geometry = homeGeometry(860f, 608f, LayoutPreset(), labels = true)
        val sizing = geometry.widgetGridSizing()
        val side = geometry.referenceCardSize!!
        val pitch = geometry.gridWidth / GRID_COLUMNS
        val square = sizing.contentSize(0, 0, 2, 2)
        assertEquals(side, square.widthDp, .001f)
        assertEquals(side, square.heightDp, .001f)
        // The Today page (left half) uses the very same frame, so its cards share the right half's rows.
        assertEquals(square, sizing.contentSize(0, 0, 2, 2, page = -1))
        val wide = sizing.contentSize(0, 2, 4, 2)
        assertEquals(4f * pitch - (2f * pitch - side), wide.widthDp, .001f)
        assertEquals(side, wide.heightDp, .001f)
        val large = sizing.contentSize(0, 0, 4, 4)
        assertEquals(side + 2f * geometry.rowHeight, large.heightDp, .001f)
        // Providers lose the widget gaps, not the icon gaps.
        assertEquals(2f * pitch - side, sizing.horizontalGapDp, .001f)
        assertEquals(2f * geometry.rowHeight - side, sizing.verticalGapDp, .001f)
        assertEquals(geometry.topRowHeight, sizing.topRowHeightDp, 0f)
        assertEquals(geometry.rowHeight, sizing.appRowHeightDp, 0f)
        assertEquals(GRID_ROWS, sizing.rows)
    }

    private val grid = WidgetGridSizing(
        columns = 4,
        rows = 6,
        cellWidthDp = 80f,
        cellHeightDp = 72f,
        horizontalGapDp = 10f,
        verticalGapDp = 18f,
    )

    @Test fun targetCellsProvideThePreferredSpan() {
        val result = widgetSpanConstraints(WidgetProviderSizing(
            minWidthDp = 100f,
            minHeightDp = 100f,
            targetCellWidth = 3,
            targetCellHeight = 2,
        ), grid)!!

        assertEquals(WidgetSpan(3, 2), result.preferred)
        assertEquals(result.preferred, result.minimum)
        assertEquals(result.preferred, result.maximum)
    }

    @Test fun minimumDimensionsIncludeOnlyGapsBetweenSpannedCells() {
        val result = widgetSpanConstraints(WidgetProviderSizing(
            minWidthDp = 151f,
            minHeightDp = 127f,
        ), grid)!!

        assertEquals(WidgetSpan(3, 3), result.preferred)
    }

    @Test fun resizeBoundsUseLauncherCeilingForMaximum() {
        val result = widgetSpanConstraints(WidgetProviderSizing(
            minWidthDp = 150f,
            minHeightDp = 54f,
            minResizeWidthDp = 70f,
            maxResizeWidthDp = 229f,
            resizeMode = WidgetProviderSizing.RESIZE_HORIZONTAL,
        ), grid)!!

        assertEquals(WidgetSpan(2, 1), result.preferred)
        assertEquals(WidgetSpan(1, 1), result.minimum)
        assertEquals(WidgetSpan(3, 1), result.maximum)
    }

    @Test fun unboundedResizeMaximumUsesTheGridEdge() {
        val result = widgetSpanConstraints(WidgetProviderSizing(
            minWidthDp = 70f,
            minHeightDp = 54f,
            minResizeHeightDp = 54f,
            resizeMode = WidgetProviderSizing.RESIZE_VERTICAL,
        ), grid)!!

        assertEquals(WidgetSpan(1, 6), result.maximum)
    }

    @Test fun defaultAndMaximumSpansHonorContentBoundsWithUnequalRowPitches() {
        val unequalRows = grid.copy(cellHeightDp = 60f, maximumCellHeightDp = 90f, verticalGapDp = 10f)
        val provider = WidgetProviderSizing(
            minWidthDp = 150f,
            minHeightDp = 100f,
            minResizeHeightDp = 100f,
            maxResizeHeightDp = 260f,
            resizeMode = WidgetProviderSizing.RESIZE_VERTICAL,
        )
        val result = widgetSpanConstraints(provider, unequalRows)!!

        fun width(span: Int) = span * unequalRows.cellWidthDp - unequalRows.horizontalGapDp
        fun shortestHeight(span: Int) = span * unequalRows.cellHeightDp - unequalRows.verticalGapDp
        fun tallestHeight(span: Int) = span * unequalRows.maximumCellHeightDp - unequalRows.verticalGapDp
        assertTrue(width(result.preferred.width) >= provider.minWidthDp)
        assertTrue(shortestHeight(result.preferred.height) >= provider.minHeightDp)
        assertTrue(tallestHeight(result.maximum.height) <= provider.maxResizeHeightDp)
        assertEquals(WidgetSpan(2, 2), result.preferred)
        assertEquals(3, result.maximum.height)
    }

    @Test fun oversizedProviderUsesWholeGridAndReportsUnfulfilledMinimum() {
        val result = widgetSpanConstraints(WidgetProviderSizing(
            minWidthDp = 311f,
            minHeightDp = 72f,
        ), grid)!!
        assertEquals(WidgetSpan(4, 2), result.preferred)
        assertFalse(result.minimumFitsGrid)
    }

    @Test fun resizeMinimumAboveDefaultIsIgnoredPerProviderContract() {
        val result = widgetSpanConstraints(WidgetProviderSizing(
            minWidthDp = 150f,
            minHeightDp = 72f,
            minResizeWidthDp = 231f,
            maxResizeWidthDp = 150f,
            resizeMode = WidgetProviderSizing.RESIZE_HORIZONTAL,
        ), grid)!!
        assertEquals(WidgetSpan(2, 2), result.preferred)
        assertEquals(2, result.minimum.width)
    }

    @Test fun api31TargetCellsReplaceLegacyDefaultDimensions() {
        val result = widgetSpanConstraints(WidgetProviderSizing(
            minWidthDp = 151f,
            minHeightDp = 72f,
            targetCellWidth = 1,
            targetCellHeight = 1,
        ), grid)!!

        assertEquals(WidgetSpan(1, 1), result.preferred)
    }

    @Test fun weatherProviderTargetCellsFitDespiteLargerLegacyDimensions() {
        val current = widgetSpanConstraints(WidgetProviderSizing(
            minWidthDp = 150f, minHeightDp = 110f,
            minResizeWidthDp = 150f, minResizeHeightDp = 100f,
            targetCellWidth = 2, targetCellHeight = 2,
            resizeMode = WidgetProviderSizing.RESIZE_HORIZONTAL or WidgetProviderSizing.RESIZE_VERTICAL,
        ), grid)!!
        val timeline = widgetSpanConstraints(WidgetProviderSizing(
            minWidthDp = 250f, minHeightDp = 110f,
            minResizeWidthDp = 240f, minResizeHeightDp = 110f,
            targetCellWidth = 4, targetCellHeight = 2,
            resizeMode = WidgetProviderSizing.RESIZE_HORIZONTAL or WidgetProviderSizing.RESIZE_VERTICAL,
        ), grid)!!
        assertEquals(WidgetSpan(2, 2), current.preferred)
        assertEquals(WidgetSpan(4, 2), timeline.preferred)
    }

    @Test fun zeroPaddingBoundaryFitsAtExactProviderWidth() {
        val exact = widgetSpanConstraints(WidgetProviderSizing(
            minWidthDp = 150f,
            minHeightDp = 110f,
            minResizeWidthDp = 150f,
            minResizeHeightDp = 110f,
            resizeMode = WidgetProviderSizing.RESIZE_HORIZONTAL or WidgetProviderSizing.RESIZE_VERTICAL,
        ), grid.copy(cellWidthDp = 80f, cellHeightDp = 64f))!!
        val justShort = widgetSpanConstraints(WidgetProviderSizing(
            minWidthDp = 150.01f,
            minHeightDp = 110f,
        ), grid.copy(cellWidthDp = 80f, cellHeightDp = 64f))!!

        assertEquals(2, exact.preferred.width) // 2 * 80 - 10 = exactly 150dp of real content.
        assertEquals(3, justShort.preferred.width)
    }

    @Test fun anchoredContentSizeUsesExactMixedRowPitches() {
        val mixed = grid.copy(
            cellHeightDp = 60f,
            maximumCellHeightDp = 90f,
            topRowHeightDp = 90f,
            appRowHeightDp = 60f,
            verticalGapDp = 18f,
        )

        assertEquals(WidgetContentSize(150f, 162f), mixed.contentSize(0, 0, 2, 2))
        assertEquals(WidgetContentSize(310f, 102f), mixed.contentSize(0, 2, 4, 2))
        assertEquals(WidgetContentSize(150f, 132f), mixed.contentSize(0, 1, 2, 2))
    }
}
