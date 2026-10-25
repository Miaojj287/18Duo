package com.jake.duolauncher

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * Round 12: why the 18Duo lock screen did or did not come up. The owner kept finding the system
 * keyguard instead of ours and the phone's logcat buffer is minutes long, so every launch decision is
 * (a) logged at INFO under `18DuoLockScreen` and (b) kept in a small ring in `duo18_lock_screen`
 * that Settings shows under the switch ("上次：13:41 显示"). Pure Kotlin: JVM-tested in
 * LockScreenDiagnosticsTest. The Android side (reading the ops, writing the prefs) is in
 * LockScreenController.kt.
 */

/** Outcome of one launch decision, as the Settings line and the log name it. */
internal enum class LockLaunchResult(val key: String, val zh: String, val en: String) {
    /** `startActivity` returned; the activity has not been seen yet. */
    LAUNCHED("launched", "已启动", "Launched"),
    /** The activity resumed or drew a frame while the keyguard was locked: the owner saw 18Duo's lock screen. */
    SEEN("seen", "显示", "Shown"),
    /** `startActivity` threw (HyperOS refused the background start). */
    REJECTED("rejected", "系统拒绝", "Rejected by the system"),
    /** A launch the system swallowed: nothing was seen before the next decision, after the grace. */
    NOT_SEEN("unseen", "未显示", "Not shown"),
    NOT_LOCKED("not_locked", "未锁定", "Not locked"),
    ALREADY_SHOWING("showing", "已在显示", "Already showing"),
    DISABLED("disabled", "已关闭", "Off"),
    NOT_DEFAULT_HOME("not_home", "非默认桌面", "Not the default Home"),
    STANDBY("standby", "站立时钟", "StandBy showing"),
    CALL_OR_ALARM("call", "通话或闹钟", "Call or alarm"),
    UNHEALTHY("unhealthy", "连续未显示，已自动关闭", "Not shown twice; turned itself off");

    companion object { fun fromKey(key: String?): LockLaunchResult? = entries.firstOrNull { it.key == key } }
}

/** The first condition of [lockScreenShouldLaunch] that fails, or null when the launch goes ahead. */
internal fun lockLaunchBlocker(state: LockLaunchState): LockLaunchResult? = with(state) {
    when {
        alreadyShowing -> LockLaunchResult.ALREADY_SHOWING
        !enabled -> LockLaunchResult.DISABLED
        !healthy -> LockLaunchResult.UNHEALTHY
        !isDefaultHome -> LockLaunchResult.NOT_DEFAULT_HOME
        !keyguardLocked -> LockLaunchResult.NOT_LOCKED
        standbyClockVisible -> LockLaunchResult.STANDBY
        callOrAlarm -> LockLaunchResult.CALL_OR_ALARM
        else -> null
    }
}

/** One ring entry. [trigger] names the event that caused the decision ("display on", "keyguard locked +300ms"). */
internal data class LockLaunchEvent(
    val timeMillis: Long,
    val trigger: String,
    val result: LockLaunchResult,
    val keyguardLocked: Boolean,
    val interactive: Boolean,
    /** Exception text, the HyperOS op states, … Never contains a tab or a newline (the ring's separators). */
    val detail: String = "",
) {
    /** One logcat line: `adb logcat | grep 18DuoLockScreen` tells the story. */
    fun logLine(): String = buildString {
        append(result.key).append(" trigger=").append(trigger)
        append(" keyguardLocked=").append(keyguardLocked).append(" interactive=").append(interactive)
        if (detail.isNotEmpty()) append(' ').append(detail)
    }
}

/** The last [SIZE] decisions, oldest first; persisted as one string. */
internal object LockLaunchRing {
    const val SIZE = 8
    private const val FIELD = '\t'
    private const val LINE = '\n'

    fun add(ring: List<LockLaunchEvent>, event: LockLaunchEvent): List<LockLaunchEvent> =
        (ring + event.sanitised()).takeLast(SIZE)

    /** The activity was seen: the newest LAUNCHED entry becomes SEEN (nothing to do when there is none). */
    fun markSeen(ring: List<LockLaunchEvent>, timeMillis: Long, how: String): List<LockLaunchEvent> {
        val index = ring.indexOfLast { it.result == LockLaunchResult.LAUNCHED }
        if (index < 0) return ring
        val seen = ring[index].copy(result = LockLaunchResult.SEEN, detail = appendDetail(ring[index].detail, "seen=$how +${(timeMillis - ring[index].timeMillis).coerceAtLeast(0L)}ms"))
        return ring.toMutableList().apply { set(index, seen.sanitised()) }
    }

    /**
     * A new decision finds the previous launch still pending past the grace: that launch was swallowed.
     * Marks the newest LAUNCHED entry NOT_SEEN so the Settings line says so.
     */
    fun markUnseen(ring: List<LockLaunchEvent>): List<LockLaunchEvent> {
        val index = ring.indexOfLast { it.result == LockLaunchResult.LAUNCHED }
        if (index < 0) return ring
        return ring.toMutableList().apply { set(index, ring[index].copy(result = LockLaunchResult.NOT_SEEN)) }
    }

    fun encode(ring: List<LockLaunchEvent>): String = ring.joinToString(LINE.toString()) { e ->
        listOf(e.timeMillis.toString(), e.trigger, e.result.key, if (e.keyguardLocked) "1" else "0",
            if (e.interactive) "1" else "0", e.detail).joinToString(FIELD.toString())
    }

    /** Tolerant: a damaged line is dropped, the rest survive. */
    fun decode(text: String?): List<LockLaunchEvent> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split(LINE).mapNotNull { line ->
            val f = line.split(FIELD)
            if (f.size < 5) return@mapNotNull null
            val time = f[0].toLongOrNull() ?: return@mapNotNull null
            val result = LockLaunchResult.fromKey(f[2]) ?: return@mapNotNull null
            LockLaunchEvent(time, f[1], result, f[3] == "1", f[4] == "1", f.getOrNull(5) ?: "")
        }.takeLast(SIZE)
    }

    private fun appendDetail(detail: String, more: String): String = if (detail.isEmpty()) more else "$detail; $more"

    private fun LockLaunchEvent.sanitised(): LockLaunchEvent =
        copy(trigger = trigger.clean(), detail = detail.clean())

    private fun String.clean(): String = replace(FIELD, ' ').replace(LINE, ' ').replace('\r', ' ')
}

/** Settings' line under the switch: "上次：13:41 显示" / "Last: 13:41 Shown"; null when nothing happened yet. */
internal fun lockLaunchSettingsLine(event: LockLaunchEvent?, zh: Boolean, zone: ZoneId = ZoneId.systemDefault()): String? {
    if (event == null) return null
    val time = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(event.timeMillis).atZone(zone))
    val what = if (zh) event.result.zh else event.result.en
    return if (zh) "上次：$time $what" else "Last: $time $what"
}

// HyperOS app-ops ---------------------------------------------------------------------------------------

/** Mode of one app-op as the launcher could read it. */
internal enum class LockOpState(val mark: String) {
    ALLOWED("✓"),
    DENIED("✗"),
    /** MODE_DEFAULT: HyperOS asks or decides per launch; treated as "not confirmed". */
    DEFAULT("？"),
    /** The op could not be read (not Xiaomi, reflection blocked, service missing). */
    UNKNOWN("？");

    val ok: Boolean get() = this == ALLOWED
}

/**
 * The two HyperOS permissions the 18Duo lock screen needs beyond Android's showWhenLocked: MIUI op
 * 10020 「锁屏显示」(SHOW_WHEN_LOCKED) and 10021 「后台弹出界面」(BACKGROUND_START_ACTIVITY). Modes are
 * AppOpsManager's: 0 allowed, 1 ignored, 2 errored, 3 default.
 */
internal object HyperOsLockOps {
    const val OP_SHOW_WHEN_LOCKED = 10020
    const val OP_BACKGROUND_START_ACTIVITY = 10021
    const val MODE_ALLOWED = 0
    const val MODE_IGNORED = 1
    const val MODE_ERRORED = 2
    const val MODE_DEFAULT = 3

    fun fromMode(mode: Int?): LockOpState = when (mode) {
        null -> LockOpState.UNKNOWN
        MODE_ALLOWED -> LockOpState.ALLOWED
        MODE_IGNORED, MODE_ERRORED -> LockOpState.DENIED
        MODE_DEFAULT -> LockOpState.DEFAULT
        else -> LockOpState.UNKNOWN
    }
}

internal data class LockPermissionState(
    /** False off Xiaomi: the two ops do not exist and Settings does not mention them. */
    val hyperOs: Boolean,
    val showWhenLocked: LockOpState,
    val backgroundStart: LockOpState,
) {
    val anyDenied: Boolean get() = showWhenLocked == LockOpState.DENIED || backgroundStart == LockOpState.DENIED
    val allAllowed: Boolean get() = !hyperOs || (showWhenLocked.ok && backgroundStart.ok)

    /** Appended to the launch log and the ring entry: `ops: showWhenLocked=DENIED backgroundStart=ALLOWED`. */
    fun logNote(): String = if (!hyperOs) "" else "ops: showWhenLocked=$showWhenLocked backgroundStart=$backgroundStart"

    /** What Settings says under the switch when a permission is missing; null when there is nothing to say. */
    fun settingsNote(zh: Boolean): String? {
        if (!hyperOs) return null
        val missing = buildList {
            if (showWhenLocked == LockOpState.DENIED) add(if (zh) "「锁屏显示」" else "“Show on lock screen”")
            if (backgroundStart == LockOpState.DENIED) add(if (zh) "「后台弹出界面」" else "“Display pop-up windows while running in the background”")
        }
        if (missing.isEmpty()) return null
        return if (zh) "HyperOS 未允许 ${missing.joinToString("和")}，系统会拦下 18Duo 锁屏。"
            else "HyperOS does not allow ${missing.joinToString(" and ")}; the system will block the 18Duo Lock Screen."
    }

    companion object {
        val NOT_APPLICABLE = LockPermissionState(false, LockOpState.UNKNOWN, LockOpState.UNKNOWN)
        fun unreadable() = LockPermissionState(true, LockOpState.UNKNOWN, LockOpState.UNKNOWN)
    }
}

// The "打开 18Duo 锁屏" guidance card -------------------------------------------------------------------------

internal enum class LockSetupStep { DEFAULT_HOME, SHOW_WHEN_LOCKED, BACKGROUND_START }

internal data class LockSetupItem(val step: LockSetupStep, val state: LockOpState) {
    val title: String get() = when (step) {
        LockSetupStep.DEFAULT_HOME -> uxText("18Duo 已设为默认桌面", "18Duo is the default Home")
        LockSetupStep.SHOW_WHEN_LOCKED -> uxText("HyperOS 允许「锁屏显示」", "HyperOS allows “Show on lock screen”")
        LockSetupStep.BACKGROUND_START -> uxText("HyperOS 允许「后台弹出界面」", "HyperOS allows background pop-ups")
    }
}

/**
 * The checklist on the card: the 默认桌面 check everywhere, the two HyperOS ops on Xiaomi. A ✓ is
 * [LockOpState.ALLOWED]; a ✗ a denied op or a missing Home role; ？ an op the launcher could not read.
 */
internal fun lockSetupChecklist(isDefaultHome: Boolean, permissions: LockPermissionState): List<LockSetupItem> = buildList {
    add(LockSetupItem(LockSetupStep.DEFAULT_HOME, if (isDefaultHome) LockOpState.ALLOWED else LockOpState.DENIED))
    if (permissions.hyperOs) {
        add(LockSetupItem(LockSetupStep.SHOW_WHEN_LOCKED, permissions.showWhenLocked))
        add(LockSetupItem(LockSetupStep.BACKGROUND_START, permissions.backgroundStart))
    }
}

/** Every step confirmed: the card's action is a plain "turn it on". */
internal fun lockSetupReady(items: List<LockSetupItem>): Boolean = items.all { it.state.ok }
