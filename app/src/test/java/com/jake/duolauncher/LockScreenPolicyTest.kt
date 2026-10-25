package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class LockScreenPolicyTest {
    private val cover = lockScreenGeometry(425f, 622f)
    private val inner = lockScreenGeometry(860f, 608f)

    @Test fun `panels split like Home`() {
        assertEquals(LockPanel.COVER, lockPanelFor(425f, 622f))
        assertEquals(LockPanel.COVER, lockPanelFor(622f, 425f))
        assertEquals(LockPanel.INNER_LANDSCAPE, lockPanelFor(860f, 608f))
        assertEquals(LockPanel.INNER_PORTRAIT, lockPanelFor(608f, 860f))
    }

    @Test fun `cover controls are both on the right side in the Dock column, flashlight above camera`() {
        assertEquals(.896f * 425f, cover.flashlight.x, .01f)
        assertEquals(cover.flashlight.x, cover.camera.x, 0f)
        assertTrue(cover.flashlight.y < cover.camera.y)
        // The recording's 87 pt bottom distance and Ø 56 pt button (440 pt phone), scaled by 425 / 466 Duo points.
        assertEquals(622f - LockScreenSpec.CONTROL_BOTTOM_PT * 425f / 466f, cover.camera.y, .01f)
        assertEquals(87f * 425f / 440f, 622f - cover.camera.y, .05f)
        // Round 13: Apple's Ø 50 pt controls, dp ≈ pt on every panel.
        assertEquals(50f, cover.controlDiameter, 0f)
        assertEquals(50f, inner.controlDiameter, 0f)
        // The two buttons never touch, and stay clear of the bottom edge and the right edge.
        assertTrue(cover.camera.y - cover.flashlight.y > cover.controlDiameter)
        assertTrue(cover.camera.y + cover.controlDiameter / 2f < 622f)
        assertTrue(cover.camera.x + cover.controlDiameter / 2f < 425f)
    }

    @Test fun `inner landscape keeps the side column and portrait uses the iPhone corners`() {
        assertEquals(.949f * 860f, inner.camera.x, .01f)
        assertEquals(inner.camera.x, inner.flashlight.x, 0f)
        val portrait = lockScreenGeometry(608f, 860f)
        assertTrue(portrait.flashlight.x < 608f / 2f && portrait.camera.x > 608f / 2f)
        assertEquals(portrait.flashlight.y, portrait.camera.y, 0f)
        assertEquals(.5f * 608f, portrait.textCenterX, .01f)
    }

    @Test fun `clock is centred on every panel at 0_5 W (round 12), below the date`() {
        assertEquals(.5f * 425f, cover.textCenterX, .01f)
        assertEquals(.5f * 860f, inner.textCenterX, .01f)
        assertEquals(.5f * 608f, lockScreenGeometry(608f, 860f).textCenterX, .01f)
        assertTrue(cover.clockTop > cover.dateCenterY + cover.dateCapHeight / 2f)
        // Cover cutout reaches 46 dp: the date's ink starts below it.
        assertTrue(cover.dateCenterY - cover.dateCapHeight / 2f > 46f)
        assertEquals(70f * 425f / 466f, cover.clockDigitHeight, .01f)
        // iPad-sized clock on the inner panel.
        assertTrue(inner.clockDigitHeight > cover.clockDigitHeight * 1.15f)
    }

    @Test fun `text area runs from the row inset to the side column and the centred clock may not reach the column`() {
        val s = 425f / 440f
        assertEquals(44f * s, cover.textAreaLeft, .01f)
        val columnLeft = cover.flashlight.x - cover.controlDiameter / 2f
        assertEquals(columnLeft - .03f * 425f, cover.textAreaRight, .01f)
        assertTrue(cover.textAreaRight < columnLeft)
        // Inner portrait has no column: mirrored insets.
        val portrait = lockScreenGeometry(608f, 860f)
        assertEquals(608f - 44f * 608f / 440f, portrait.textAreaRight, .01f)
        // 居中: symmetric about 0.5 W, bounded by the nearer edge (the column on the cover).
        val centre = lockTextAnchor(cover, LockTextAlign.CENTER)
        assertEquals(212.5f, centre.x, .01f)
        assertEquals(2f * (cover.textAreaRight - 212.5f), centre.maxWidth, .01f)
        assertTrue(centre.x + centre.maxWidth / 2f <= cover.textAreaRight + 1e-3f)
        assertEquals(centre.x - 50f, centre.left(100f), 1e-4f)
        // The cover's centred block is at least 250 dp wide: "10:41" at the default size (≈ 230 dp) fits,
        // and the 2.2× stretch is capped by fittedWidthScale rather than reaching the column.
        assertTrue(centre.maxWidth > 250f)
        assertTrue(LockClockSize.fittedWidthScale(1f, 230f, centre.maxWidth) < LockClockSize.MAX_WIDTH_SCALE)
        // 靠左 / 靠右 hang on the area's edges and may use the whole area.
        val left = lockTextAnchor(cover, LockTextAlign.LEFT)
        assertEquals(cover.textAreaLeft, left.x, 0f); assertEquals(cover.textAreaLeft, left.left(100f), 0f)
        val right = lockTextAnchor(cover, LockTextAlign.RIGHT)
        assertEquals(cover.textAreaRight, right.x, 0f); assertEquals(cover.textAreaRight - 100f, right.left(100f), 0f)
        assertEquals(cover.textAreaRight - cover.textAreaLeft, left.maxWidth, 0f)
        assertEquals(left.maxWidth, right.maxWidth, 0f)
        // The inner panels centre too and have room for the full 1.35× width.
        val innerCentre = lockTextAnchor(inner, LockTextAlign.CENTER)
        assertEquals(430f, innerCentre.x, .01f)
        assertTrue(innerCentre.maxWidth > 290f * LockClockSize.MAX_WIDTH_SCALE)
    }

    @Test fun `time text follows iOS`() {
        assertEquals("9:41", lockTimeText(9, 41, false))
        assertEquals("12:05", lockTimeText(0, 5, false))
        assertEquals("1:00", lockTimeText(13, 0, false))
        assertEquals("09:41", lockTimeText(9, 41, true))
        assertEquals("21:07", lockTimeText(21, 7, true))
    }

    @Test fun `date line follows iOS per language`() {
        val day = LocalDate.of(2026, 10, 21)
        assertEquals("10月21日 星期三", lockDateText(day, Locale.SIMPLIFIED_CHINESE))
        // The owner's iPhone with the lunar calendar on (round 12.4): short weekday, " · ", the lunar line.
        assertEquals("10月21日周三 · 丙午年九月十二", lockDateText(day, Locale.SIMPLIFIED_CHINESE, "丙午年九月十二"))
        assertEquals("Wednesday, October 21", lockDateText(day, Locale.US, "丙午年九月十二"))
        assertEquals("Wednesday, October 21", lockDateText(day, Locale.US))
        assertEquals("Wednesday 21 October", lockDateText(day, Locale.UK))
    }

    @Test fun `swipe unlocks past the threshold or on a flick, never on a downward flick`() {
        assertFalse(LockSwipe.shouldUnlock(40f, 0f, 622f))
        assertTrue(LockSwipe.shouldUnlock(.12f * 622f, 0f, 622f))
        assertTrue(LockSwipe.shouldUnlock(20f, 600f, 622f))
        assertFalse(LockSwipe.shouldUnlock(8f, 2000f, 622f))
        assertFalse(LockSwipe.shouldUnlock(200f, -800f, 622f))
    }

    @Test fun `slab and clock follow the finger up and rubber-band down`() {
        assertEquals(-50f, LockSwipe.contentOffset(50f, 622f), 0f)
        val down = LockSwipe.contentOffset(-400f, 622f)
        assertTrue(down > 0f && down < 622f * .08f)
        assertTrue(LockSwipe.contentOffset(-40f, 622f) < down)
        // Round 13: released, the slab rides out until its bottom edge is 0.10 H from the top (f130: indicator 250 px).
        assertEquals(622f * .90f, LockSwipe.exitTravel(622f), 1e-3f)
        assertEquals(2832f - 250f, LockSwipe.exitTravel(2868f), 3f)
        assertTrue(LockSwipe.autoExitSpring.response < LockSwipe.exitSpring.response)
        assertEquals(1f, LockSwipe.exitSpring.dampingFraction, 0f)
        assertEquals(.65f, LockSwipe.exitSpring.response, 1e-4f)
        assertEquals(.063f * 2868f, LockSwipe.clockClipTop(2868f), 1e-2f) // f021–f033: cut at the status bar
    }

    @Test fun `reduce motion dissolves the clock - full at 0_04 H, half at 0_18 H, gone at 0_28 H`() {
        val h = 2868f
        assertEquals(1f, LockSwipe.clockAlpha(0f, h), 0f)
        assertEquals(1f, LockSwipe.clockAlpha(.04f * h, h), 1e-4f)       // f005
        assertEquals(.42f, LockSwipe.clockAlpha(.18f * h, h), .1f)        // f026 ≈ half
        assertEquals(0f, LockSwipe.clockAlpha(.28f * h, h), 1e-4f)       // f040
        assertEquals(0f, LockSwipe.clockAlpha(.6f * h, h), 0f)
        assertTrue(LockSwipe.clockAlpha(.1f * h, h) > LockSwipe.clockAlpha(.2f * h, h))
    }

    @Test fun `glass materialises over the first 4 percent of travel, either way`() {
        assertEquals(0f, LockSwipe.slabMaterialAlpha(0f, 622f), 0f)
        assertEquals(.5f, LockSwipe.slabMaterialAlpha(.02f * 622f, 622f), 1e-3f)
        assertEquals(1f, LockSwipe.slabMaterialAlpha(.04f * 622f, 622f), 1e-4f)
        assertEquals(1f, LockSwipe.slabMaterialAlpha(-.1f * 622f, 622f), 0f)
        assertFalse(LockSwipe.slabCleared(.5f * 433f, 433f))
        assertFalse(LockSwipe.slabCleared(.85f * 433f, 433f))
        assertFalse(LockSwipe.slabCleared(.92f * 433f, 433f))
        assertTrue(LockSwipe.slabCleared(.97f * 433f, 433f))
    }

    @Test fun `slab never fades - solid up to the moment Home cuts in`() {
        assertEquals(1f, LockSwipe.slabAlpha(0f, 622f), 0f)
        assertEquals(1f, LockSwipe.slabAlpha(.85f * 622f, 622f), 0f)
        assertEquals(1f, LockSwipe.slabAlpha(622f, 622f), 0f)
    }

    @Test fun `wallpaper stays still through the drag and the hand-off - iOS 26 recording`() {
        val h = 2868f
        assertEquals(1f, LockSwipe.wallpaperScale(0f, h), 0f)
        assertEquals(1f, LockSwipe.wallpaperScale(.57f * h, h), 1e-6f)
        assertEquals(1f, LockSwipe.wallpaperScale(h, h), 1e-6f)
        assertEquals(1f, LockSwipe.HANDOFF_WALLPAPER_SCALE, 1e-6f)
        assertEquals(UnlockArrivalMotion.WALLPAPER_START_SCALE, LockSwipe.HANDOFF_WALLPAPER_SCALE, 0f)
    }

    @Test fun `slab layout on the cover keeps the row left of the side column at the flashlight's height`() {
        val slab = lockSlabLayout(425f, 622f, cover)
        val s = 425f / 440f
        assertEquals(56f * s, slab.unit + 0f, 4f * s + .05f) // Ø 60 pt recording units (scaled), ≈ the control
        assertEquals(60f * s, slab.unit, .01f)
        assertEquals(cover.flashlight.y, slab.widgetRowCenterY, 0f)
        val rowRight = slab.widgetRowLeft + slab.unit + slab.pitch * 3f
        assertTrue(rowRight < cover.flashlight.x - cover.controlDiameter / 2f)
        assertTrue(slab.pitch > slab.unit) // slots never overlap
        assertTrue(slab.slabTop < slab.widgetRowCenterY - slab.unit / 2f)
        assertTrue(slab.slabTop < cover.flashlight.y - cover.controlDiameter / 2f)
        assertEquals(36f * s, (slab.widgetRowCenterY - slab.unit / 2f) - slab.slabTop, .05f)
        assertTrue(slab.slabTop > 622f * .5f) // the slab is the bottom block, not half the screen
        assertEquals(slab.unit + slab.pitch, slab.rectangularWidth, 0f)
        assertEquals(slab.widgetRowLeft + slab.pitch * 2f, slab.slotLeft(slab.widgetRowLeft, 2), 0f)
        // Under the date: centred on the clock, below its ink.
        assertTrue(slab.underDateRowCenterY - slab.unit / 2f > cover.clockTop + cover.clockDigitHeight)
        assertEquals(slab.unit + slab.pitch * 3f, slab.rowWidth, 0f)
        assertEquals(cover.textCenterX, lockUnderDateRowLeft(cover, slab, LockTextAlign.CENTER) + slab.rowWidth / 2f, .01f)
        assertEquals(cover.textAreaLeft, lockUnderDateRowLeft(cover, slab, LockTextAlign.LEFT), 0f)
        assertEquals(cover.textAreaRight - slab.rowWidth, lockUnderDateRowLeft(cover, slab, LockTextAlign.RIGHT), 0f)
        // The bottom row and a left-aligned clock share the same left edge.
        assertEquals(slab.widgetRowLeft, cover.textAreaLeft, .01f)
        assertTrue(lockSlabTopWithoutWidgets(cover, slab, 425f, 622f) >= slab.slabTop)
        // A clock stretched by the size handle pushes the under-date row down by the extra ink height.
        assertEquals(slab.underDateRowCenterY, lockUnderDateRowCenterY(cover, slab, 0f), 0f)
        assertEquals(slab.underDateRowCenterY + cover.clockDigitHeight * 1.2f, lockUnderDateRowCenterY(cover, slab, 1f), 1e-3f)
    }

    @Test fun `slab layout in inner portrait is the recording's`() {
        val geometry = lockScreenGeometry(608f, 860f)
        val slab = lockSlabLayout(608f, 860f, geometry)
        val s = 608f / 440f
        assertEquals(860f - 167f * s, slab.widgetRowCenterY, .01f)
        assertEquals(96f * s, slab.pitch, .01f)
        assertEquals(608f * .1f, slab.widgetRowLeft, .01f)
        assertEquals(860f - 233f * s, slab.slabTop, 1.5f)
        assertEquals(12f * s, slab.homeBarBottomInset, .01f)
        assertEquals(55f * s, slab.cornerRadius, .01f)
        assertEquals(.19f * 608f, geometry.flashlight.x, .01f)
    }

    @Test fun `launch needs every condition and leaves on any unlock`() {
        val ok = LockLaunchState(enabled = true, isDefaultHome = true, keyguardLocked = true, standbyClockVisible = false,
            callOrAlarm = false, alreadyShowing = false, healthy = true)
        assertTrue(lockScreenShouldLaunch(ok))
        assertFalse(lockScreenShouldLaunch(ok.copy(enabled = false)))
        assertFalse(lockScreenShouldLaunch(ok.copy(isDefaultHome = false)))
        assertFalse(lockScreenShouldLaunch(ok.copy(keyguardLocked = false)))
        assertFalse(lockScreenShouldLaunch(ok.copy(standbyClockVisible = true)))
        assertFalse(lockScreenShouldLaunch(ok.copy(callOrAlarm = true)))
        assertFalse(lockScreenShouldLaunch(ok.copy(alreadyShowing = true)))
        assertFalse(lockScreenShouldLaunch(ok.copy(healthy = false)))
        assertTrue(lockScreenShouldLeave(keyguardLocked = false, enabled = true))
        assertTrue(lockScreenShouldLeave(keyguardLocked = true, enabled = false))
        assertFalse(lockScreenShouldLeave(keyguardLocked = true, enabled = true))
    }

    @Test fun `two unseen launches in a row turn the feature off`() {
        var unseen = LockScreenHealth.afterLaunch(previousStillPending = false, unseen = 0)
        assertTrue(LockScreenHealth.healthy(unseen))
        unseen = LockScreenHealth.afterLaunch(previousStillPending = true, unseen = unseen)
        assertTrue(LockScreenHealth.healthy(unseen))
        unseen = LockScreenHealth.afterLaunch(previousStillPending = true, unseen = unseen)
        assertFalse(LockScreenHealth.healthy(unseen))
    }

    @Test fun `a catch-up retry inside the grace does not count the pending launch as unseen`() {
        assertEquals(0, LockScreenHealth.afterLaunch(previousStillPending = true, unseen = 0, pendingAgeMs = 300L))
        assertEquals(0, LockScreenHealth.afterLaunch(previousStillPending = true, unseen = 0, pendingAgeMs = LockScreenHealth.PENDING_GRACE_MS - 1))
        assertEquals(1, LockScreenHealth.afterLaunch(previousStillPending = true, unseen = 0, pendingAgeMs = LockScreenHealth.PENDING_GRACE_MS))
        assertEquals(1, LockScreenHealth.afterLaunch(previousStillPending = true, unseen = 0, pendingAgeMs = 60_000L))
        assertEquals(1, LockScreenHealth.afterLaunch(previousStillPending = false, unseen = 1, pendingAgeMs = 60_000L))
        assertTrue(LockScreenHealth.PENDING_GRACE_MS > LockScreenController.CATCH_UP_RETRY_MS)
    }

    @Test fun `screen-off checks cover the default lock-after-timeout grace`() {
        assertEquals(0L, LOCK_SCREEN_CHECK_DELAYS_MS.first())
        assertTrue(LOCK_SCREEN_CHECK_DELAYS_MS.any { it > 5000L })
        assertTrue(LOCK_SCREEN_CHECK_DELAYS_MS.toList().zipWithNext().all { (a, b) -> b > a })
    }
}
