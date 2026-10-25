package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class LockScreenDiagnosticsTest {
    private val ok = LockLaunchState(enabled = true, isDefaultHome = true, keyguardLocked = true, standbyClockVisible = false,
        callOrAlarm = false, alreadyShowing = false, healthy = true)
    private fun event(t: Long, result: LockLaunchResult, trigger: String = "display on", detail: String = "") =
        LockLaunchEvent(t, trigger, result, keyguardLocked = true, interactive = true, detail = detail)

    @Test fun `blocker names the first failing condition and agrees with lockScreenShouldLaunch`() {
        assertNull(lockLaunchBlocker(ok))
        assertEquals(LockLaunchResult.ALREADY_SHOWING, lockLaunchBlocker(ok.copy(alreadyShowing = true, keyguardLocked = false)))
        assertEquals(LockLaunchResult.DISABLED, lockLaunchBlocker(ok.copy(enabled = false)))
        assertEquals(LockLaunchResult.UNHEALTHY, lockLaunchBlocker(ok.copy(healthy = false)))
        assertEquals(LockLaunchResult.NOT_DEFAULT_HOME, lockLaunchBlocker(ok.copy(isDefaultHome = false)))
        assertEquals(LockLaunchResult.NOT_LOCKED, lockLaunchBlocker(ok.copy(keyguardLocked = false)))
        assertEquals(LockLaunchResult.STANDBY, lockLaunchBlocker(ok.copy(standbyClockVisible = true)))
        assertEquals(LockLaunchResult.CALL_OR_ALARM, lockLaunchBlocker(ok.copy(callOrAlarm = true)))
        for (state in listOf(ok, ok.copy(enabled = false), ok.copy(keyguardLocked = false), ok.copy(callOrAlarm = true)))
            assertEquals(lockScreenShouldLaunch(state), lockLaunchBlocker(state) == null)
    }

    @Test fun `ring keeps the last eight decisions, oldest first`() {
        var ring = emptyList<LockLaunchEvent>()
        for (i in 1..11) ring = LockLaunchRing.add(ring, event(i * 1000L, LockLaunchResult.NOT_LOCKED, trigger = "t$i"))
        assertEquals(LockLaunchRing.SIZE, ring.size)
        assertEquals("t4", ring.first().trigger)
        assertEquals("t11", ring.last().trigger)
    }

    @Test fun `ring survives its text form and drops a damaged line`() {
        val ring = listOf(
            event(1_000L, LockLaunchResult.LAUNCHED, detail = "ops: showWhenLocked=ALLOWED backgroundStart=DENIED"),
            LockLaunchEvent(2_000L, "keyguard locked +300ms", LockLaunchResult.REJECTED, keyguardLocked = true, interactive = false,
                detail = "java.lang.SecurityException: no"),
        )
        val text = LockLaunchRing.encode(ring)
        assertEquals(ring, LockLaunchRing.decode(text))
        assertEquals(ring, LockLaunchRing.decode("$text\nnot\ta\tline"))
        assertEquals(emptyList<LockLaunchEvent>(), LockLaunchRing.decode(null))
        assertEquals(emptyList<LockLaunchEvent>(), LockLaunchRing.decode(""))
        // Separators inside a trigger or a detail are flattened so one event never becomes two.
        val odd = LockLaunchRing.add(emptyList(), event(3_000L, LockLaunchResult.LAUNCHED, trigger = "a\tb", detail = "x\ny"))
        assertEquals("a b", odd[0].trigger)
        assertEquals("x y", odd[0].detail)
        assertEquals(odd, LockLaunchRing.decode(LockLaunchRing.encode(odd)))
    }

    @Test fun `seen turns the newest LAUNCHED into SEEN and a swallowed launch becomes NOT_SEEN`() {
        val ring = listOf(event(1_000L, LockLaunchResult.LAUNCHED), event(2_000L, LockLaunchResult.NOT_LOCKED),
            event(3_000L, LockLaunchResult.LAUNCHED))
        val seen = LockLaunchRing.markSeen(ring, 3_250L, "resumed over keyguard")
        assertEquals(LockLaunchResult.LAUNCHED, seen[0].result)
        assertEquals(LockLaunchResult.SEEN, seen[2].result)
        assertTrue(seen[2].detail, seen[2].detail.contains("seen=resumed over keyguard +250ms"))
        assertEquals(LockLaunchResult.NOT_SEEN, LockLaunchRing.markUnseen(ring)[2].result)
        val none = listOf(event(1_000L, LockLaunchResult.REJECTED))
        assertSame(none, LockLaunchRing.markSeen(none, 2_000L, "x"))
        assertSame(none, LockLaunchRing.markUnseen(none))
    }

    @Test fun `settings line is time plus the result in the UI language`() {
        val e = event(13 * 3_600_000L + 41 * 60_000L, LockLaunchResult.SEEN)
        assertEquals("上次：13:41 显示", lockLaunchSettingsLine(e, zh = true, zone = ZoneOffset.UTC))
        assertEquals("Last: 13:41 Shown", lockLaunchSettingsLine(e, zh = false, zone = ZoneOffset.UTC))
        assertEquals("上次：13:41 系统拒绝", lockLaunchSettingsLine(e.copy(result = LockLaunchResult.REJECTED), zh = true, zone = ZoneOffset.UTC))
        assertEquals("上次：13:41 未锁定", lockLaunchSettingsLine(e.copy(result = LockLaunchResult.NOT_LOCKED), zh = true, zone = ZoneOffset.UTC))
        assertNull(lockLaunchSettingsLine(null, zh = true))
        val line = e.copy(result = LockLaunchResult.REJECTED, detail = "boom").logLine()
        assertTrue(line, line.startsWith("rejected trigger=display on keyguardLocked=true interactive=true boom"))
        assertEquals(LockLaunchResult.entries.size, LockLaunchResult.entries.map { it.key }.toSet().size)
    }

    @Test fun `HyperOS op modes map to allowed, denied, default and unknown, softly`() {
        assertEquals(LockOpState.ALLOWED, HyperOsLockOps.fromMode(0))
        assertEquals(LockOpState.DENIED, HyperOsLockOps.fromMode(1))
        assertEquals(LockOpState.DENIED, HyperOsLockOps.fromMode(2))
        assertEquals(LockOpState.DEFAULT, HyperOsLockOps.fromMode(3))
        assertEquals(LockOpState.UNKNOWN, HyperOsLockOps.fromMode(null))
        assertEquals(LockOpState.UNKNOWN, HyperOsLockOps.fromMode(99))
        assertEquals(10020, HyperOsLockOps.OP_SHOW_WHEN_LOCKED)
        assertEquals(10021, HyperOsLockOps.OP_BACKGROUND_START_ACTIVITY)
        assertTrue(LockOpState.ALLOWED.ok && !LockOpState.DEFAULT.ok && !LockOpState.UNKNOWN.ok)
        assertEquals("✓", LockOpState.ALLOWED.mark); assertEquals("✗", LockOpState.DENIED.mark)
    }

    @Test fun `permission state to Settings note and log note`() {
        val both = LockPermissionState(true, LockOpState.ALLOWED, LockOpState.ALLOWED)
        assertTrue(both.allAllowed); assertFalse(both.anyDenied)
        assertNull(both.settingsNote(zh = true))
        assertEquals("ops: showWhenLocked=ALLOWED backgroundStart=ALLOWED", both.logNote())
        val popup = LockPermissionState(true, LockOpState.ALLOWED, LockOpState.DENIED)
        assertTrue(popup.anyDenied); assertFalse(popup.allAllowed)
        assertEquals("HyperOS 未允许 「后台弹出界面」，系统会拦下 18Duo 锁屏。", popup.settingsNote(zh = true))
        val none = LockPermissionState(true, LockOpState.DENIED, LockOpState.DENIED)
        assertEquals("HyperOS 未允许 「锁屏显示」和「后台弹出界面」，系统会拦下 18Duo 锁屏。", none.settingsNote(zh = true))
        assertTrue(none.settingsNote(zh = false)!!.contains("Show on lock screen"))
        // Unreadable ops: nothing is claimed, the card shows ？ and the launch still goes ahead.
        val unread = LockPermissionState.unreadable()
        assertFalse(unread.anyDenied); assertFalse(unread.allAllowed); assertNull(unread.settingsNote(zh = true))
        // Off Xiaomi there are no ops to talk about.
        assertTrue(LockPermissionState.NOT_APPLICABLE.allAllowed)
        assertEquals("", LockPermissionState.NOT_APPLICABLE.logNote())
        assertNull(LockPermissionState.NOT_APPLICABLE.settingsNote(zh = true))
    }

    @Test fun `guidance checklist - the Home check everywhere, the two ops on HyperOS`() {
        val plain = lockSetupChecklist(isDefaultHome = true, permissions = LockPermissionState.NOT_APPLICABLE)
        assertEquals(listOf(LockSetupStep.DEFAULT_HOME), plain.map { it.step })
        assertTrue(lockSetupReady(plain))
        assertFalse(lockSetupReady(lockSetupChecklist(false, LockPermissionState.NOT_APPLICABLE)))
        val hyper = lockSetupChecklist(true, LockPermissionState(true, LockOpState.ALLOWED, LockOpState.DENIED))
        assertEquals(listOf(LockSetupStep.DEFAULT_HOME, LockSetupStep.SHOW_WHEN_LOCKED, LockSetupStep.BACKGROUND_START), hyper.map { it.step })
        assertEquals(listOf(LockOpState.ALLOWED, LockOpState.ALLOWED, LockOpState.DENIED), hyper.map { it.state })
        assertFalse(lockSetupReady(hyper))
        assertTrue(lockSetupReady(lockSetupChecklist(true, LockPermissionState(true, LockOpState.ALLOWED, LockOpState.ALLOWED))))
        // ？ (unreadable) is not ready: the owner has to confirm in HyperOS.
        assertFalse(lockSetupReady(lockSetupChecklist(true, LockPermissionState.unreadable())))
        assertTrue(hyper.all { it.title.isNotBlank() })
    }
}
