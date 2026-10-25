package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IosControlsTest {
    @Test fun switchKnobRestsInsideTheTrackAtBothEnds() {
        val (offLeft, offWidth) = iosSwitchKnob(0f, 0f)
        assertEquals(2f, offLeft, 1e-4f)
        assertEquals(27f, offWidth, 1e-4f)
        val (onLeft, onWidth) = iosSwitchKnob(1f, 0f)
        assertEquals(51f - 2f - 27f, onLeft, 1e-4f)
        assertEquals(51f - 2f, onLeft + onWidth, 1e-4f)
    }

    @Test fun pressedKnobStretchesTowardTheCentreKeepingItsOuterEdge() {
        val (offLeft, offWidth) = iosSwitchKnob(0f, 1f)
        assertEquals(2f, offLeft, 1e-4f) // left edge pinned while off
        assertEquals(33f, offWidth, 1e-4f)
        val (onLeft, onWidth) = iosSwitchKnob(1f, 1f)
        assertEquals(49f, onLeft + onWidth, 1e-4f) // right edge pinned while on
        assertEquals(33f, onWidth, 1e-4f)
    }

    @Test fun knobSpringOvershootIsClampedToTheTrack() {
        val (left, width) = iosSwitchKnob(1.2f, 0f)
        assertTrue(left + width <= 49f + 1e-4f)
        val (low, _) = iosSwitchKnob(-.3f, 0f)
        assertEquals(2f, low, 1e-4f)
    }

    @Test fun sliderFractionHandlesDegenerateRanges() {
        assertEquals(.5f, iosSliderFraction(58f, 48f..68f), 1e-4f)
        assertEquals(0f, iosSliderFraction(10f, 5f..5f), 0f)
        assertEquals(1f, iosSliderFraction(99f, 0f..10f), 0f)
        assertEquals(0f, iosSliderFraction(Float.NaN, 0f..10f), 0f)
    }

    @Test fun sliderValueSnapsToSteps() {
        // StandBy hinge range: 5…170 in 32 interior steps → 5° stops.
        val range = 5f..170f
        assertEquals(10f, iosSliderValue(iosSliderFraction(11.9f, range), range, 32), 1e-3f)
        assertEquals(15f, iosSliderValue(iosSliderFraction(13f, range), range, 32), 1e-3f)
        assertEquals(170f, iosSliderValue(1f, range, 32), 1e-3f)
        assertEquals(5f, iosSliderValue(-1f, range, 32), 1e-3f)
        assertEquals(58f, iosSliderValue(.5f, 48f..68f), 1e-4f) // continuous
    }

    @Test fun rangeSliderGrabsTheNearerThumb() {
        assertEquals(0, iosRangeThumbFor(.1f, .2f, .8f))
        assertEquals(1, iosRangeThumbFor(.7f, .2f, .8f))
        // Both thumbs on the same spot: dragging from beyond them moves the end thumb.
        assertEquals(1, iosRangeThumbFor(.9f, .5f, .5f))
        assertEquals(0, iosRangeThumbFor(.3f, .5f, .5f))
    }

    @Test fun bannerStaysLongerForLongerTextButIsCapped() {
        assertTrue(iosBannerMillis("OK", false) in 2000L..2200L)
        assertTrue(iosBannerMillis("OK", true) > iosBannerMillis("OK", false))
        assertEquals(6000L, iosBannerMillis("x".repeat(500), true))
    }

    @Test fun restingDockCentresWhateverAppsItHolds() {
        assertEquals(listOf(0f, 1f, 2f, 3f), dockRestingPositions(listOf("a", "b", "c", "d")))
        // Three apps: a centred block (½ pitch in), order kept, the empty slot draws nothing.
        assertEquals(listOf(.5f, null, 1.5f, 2.5f), dockRestingPositions(listOf("a", null, "b", "c")))
        assertEquals(listOf(null, null, 1.5f, null), dockRestingPositions(listOf(null, null, "a", null)))
        val empty = dockRestingPositions(listOf(null, null, null, null))
        empty.forEach { assertNull(it) }
    }

    @Test fun groupedListRoundsOnlyTheEnds() {
        assertEquals(true to true, groupedRowCorners(0, 1))
        assertEquals(true to false, groupedRowCorners(0, 3))
        assertEquals(false to false, groupedRowCorners(1, 3))
        assertEquals(false to true, groupedRowCorners(2, 3))
    }
}
