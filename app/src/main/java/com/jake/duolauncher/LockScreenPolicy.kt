package com.jake.duolauncher

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Pure logic of the 18Duo lock screen (LockScreenActivity): which panel layout applies, Apple's
 * lock-screen geometry transferred to the Xiaomi panels, the clock/date text, the swipe-up unlock
 * decision and the launch/health rules that keep the feature from ever standing in the user's way.
 */

internal enum class LockPanel { COVER, INNER_LANDSCAPE, INNER_PORTRAIT }

/** Same split as Home: below 600 dp smallest width is the cover. */
internal fun lockPanelFor(widthDp: Float, heightDp: Float): LockPanel = when {
    min(widthDp, heightDp) < 600f -> LockPanel.COVER
    widthDp >= heightDp -> LockPanel.INNER_LANDSCAPE
    else -> LockPanel.INNER_PORTRAIT
}

/** Centre of one round Lock Screen control, in dp from the panel's top-left corner. */
internal data class LockControlSpot(val x: Float, val y: Float)

internal data class LockScreenGeometry(
    val panel: LockPanel,
    /** Horizontal centre of the date and the clock (dp): the panel's centre, 0.5 W, as on iOS (round 12). */
    val textCenterX: Float,
    /**
     * The horizontal band the text block may use (dp): from the widget row's inset to the side column
     * (flashlight/camera) less a gap, or to the mirrored inset where there is no column. 靠左 / 靠右
     * anchor to its edges; a stretched clock never grows past it.
     */
    val textAreaLeft: Float,
    val textAreaRight: Float,
    /** Vertical centre of the date line's cap height (dp). */
    val dateCenterY: Float,
    /** Cap height of the date line (dp); the font size follows from it. */
    val dateCapHeight: Float,
    /** Top of the clock digits' ink (dp). */
    val clockTop: Float,
    /** Height of the clock digits' ink (dp). */
    val clockDigitHeight: Float,
    val controlDiameter: Float,
    val flashlight: LockControlSpot,
    val camera: LockControlSpot,
)

/**
 * Apple's lock screen in points. iPhone (iOS 17–26, 393 × 852 pt): date cap centre 83 pt, clock ink
 * 97–167 pt (70 pt digits), Lock Screen controls Ø 50 pt centred 71 pt from the side and 70 pt from
 * the bottom. iPhone Duo (newsroom: "Lock Screen controls … now appear on the side") moves the two
 * controls into the side column the Dock and status use: x = 89.6 %W on the cover, 94.9 %W on the
 * unfolded inner panel (BRIEF geometry), stacked flashlight above camera, the camera at the iPhone's
 * bottom distance. The date and the clock are centred on the panel (0.5 W) as on every iOS lock
 * screen — round 12 dropped the round-8 "over the Home grid" offsets (40.8 %W / 45.5 %W) the owner
 * saw as off-centre; the column only bounds how wide a stretched clock may grow ([TEXT_COLUMN_GAP]).
 * Points become dp by the panel / iPhone Duo width ratio (cover 466 pt = 1398 px @3x, inner 890 pt).
 */
internal object LockScreenSpec {
    const val DUO_COVER_WIDTH_PT = 466f
    const val DUO_INNER_LONG_PT = 890f
    const val DATE_CENTER_PT = 83f
    const val DATE_CAP_PT = 15f
    const val CLOCK_TOP_PT = 97f
    const val CLOCK_DIGIT_PT = 70f
    /**
     * Round-11: the controls are the owner's iOS 26 recording (440×956 pt): Ø 56 pt, centres 87 pt
     * from the bottom, 0.19 W from the sides (f001: flashlight (249, 2607) px, camera (1071, 2607) px),
     * converted to Duo-cover points by 466/440.
     */
    /**
     * Round 13 (owner: "手电筒和相机图标太大"): Apple's Lock Screen controls are Ø 50 pt on iPhone and iPad;
     * dp ≈ pt, no panel scaling (was the recording's 56 pt scaled to the 466 pt Duo cover: 54 dp cover, 57 dp inner).
     */
    const val CONTROL_DP = 50f
    const val CONTROL_SIDE = .19f
    const val CONTROL_BOTTOM_PT = 87f * 466f / 440f
    /** Flashlight above camera in the side column: one button plus a 20 dp gap. */
    const val CONTROL_PITCH_DP = 70f
    /** iPadOS-sized clock on the big panel (iPad's lock clock is ≈ 1.2× the iPhone's). */
    const val INNER_TEXT_SCALE = 1.2f
    const val COVER_COLUMN_X = .896f
    const val INNER_COLUMN_X = .949f
    /** Gap (fraction of W) between the text area and the side column's buttons. */
    const val TEXT_COLUMN_GAP = .03f
    /** Side inset of the text area, the recording's widget-row inset (44 pt of 440) scaled by the short edge. */
    const val TEXT_INSET_PT = 44f
}

internal fun lockScreenGeometry(widthDp: Float, heightDp: Float): LockScreenGeometry {
    val panel = lockPanelFor(widthDp, heightDp)
    val s = when (panel) {
        LockPanel.COVER -> widthDp / LockScreenSpec.DUO_COVER_WIDTH_PT
        else -> maxOf(widthDp, heightDp) / LockScreenSpec.DUO_INNER_LONG_PT
    }
    val text = if (panel == LockPanel.COVER) 1f else LockScreenSpec.INNER_TEXT_SCALE
    val d = LockScreenSpec.CONTROL_DP
    val bottomY = heightDp - LockScreenSpec.CONTROL_BOTTOM_PT * s
    val (flashlight, camera) = when (panel) {
        LockPanel.COVER, LockPanel.INNER_LANDSCAPE -> {
            val x = widthDp * if (panel == LockPanel.COVER) LockScreenSpec.COVER_COLUMN_X else LockScreenSpec.INNER_COLUMN_X
            LockControlSpot(x, bottomY - LockScreenSpec.CONTROL_PITCH_DP) to LockControlSpot(x, bottomY)
        }
        // Inner portrait has no side column (Dock bottom-centre): the iPhone's corners.
        LockPanel.INNER_PORTRAIT -> LockControlSpot(widthDp * LockScreenSpec.CONTROL_SIDE, bottomY) to
            LockControlSpot(widthDp * (1f - LockScreenSpec.CONTROL_SIDE), bottomY)
    }
    val inset = LockScreenSpec.TEXT_INSET_PT * (min(widthDp, heightDp) / 440f)
    val areaRight = when (panel) {
        LockPanel.INNER_PORTRAIT -> widthDp - inset
        else -> flashlight.x - d / 2f - widthDp * LockScreenSpec.TEXT_COLUMN_GAP
    }
    return LockScreenGeometry(
        panel = panel,
        textCenterX = widthDp * .5f,
        textAreaLeft = inset,
        textAreaRight = areaRight,
        dateCenterY = LockScreenSpec.DATE_CENTER_PT * s,
        dateCapHeight = LockScreenSpec.DATE_CAP_PT * s * text,
        clockTop = LockScreenSpec.DATE_CENTER_PT * s + (LockScreenSpec.CLOCK_TOP_PT - LockScreenSpec.DATE_CENTER_PT) * s * text,
        clockDigitHeight = LockScreenSpec.CLOCK_DIGIT_PT * s * text,
        controlDiameter = d,
        flashlight = flashlight,
        camera = camera,
    )
}

/** Where a line of lock-screen text is anchored: the x to draw at, how the glyphs hang on it, and the widest it may be. */
internal data class LockTextAnchor(val x: Float, val align: LockTextAlign, val maxWidth: Float) {
    /** Left edge of a block [width] wide hung on this anchor. */
    fun left(width: Float): Float = when (align) {
        LockTextAlign.CENTER -> x - width / 2f
        LockTextAlign.LEFT -> x
        LockTextAlign.RIGHT -> x - width
    }
}

/**
 * Round 12 「对齐」: 居中 draws at 0.5 W and may grow symmetrically until it meets the nearer edge of
 * the text area (on the cover that is the side column: `2 × (areaRight − 0.5 W)`); 靠左 / 靠右 hang on
 * the area's edge and may use the whole area. The same anchor places the clock, the date and the
 * under-date widget row, so the editor's preview, the live lock screen and the arrival hand-off agree.
 */
internal fun lockTextAnchor(geometry: LockScreenGeometry, align: LockTextAlign): LockTextAnchor = when (align) {
    LockTextAlign.CENTER -> LockTextAnchor(geometry.textCenterX, align,
        2f * minOf(geometry.textCenterX - geometry.textAreaLeft, geometry.textAreaRight - geometry.textCenterX))
    LockTextAlign.LEFT -> LockTextAnchor(geometry.textAreaLeft, align, geometry.textAreaRight - geometry.textAreaLeft)
    LockTextAlign.RIGHT -> LockTextAnchor(geometry.textAreaRight, align, geometry.textAreaRight - geometry.textAreaLeft)
}

/** iOS lock clock: no AM/PM, no leading zero in 12-hour time; 24-hour time keeps it ("09:41"). */
internal fun lockTimeText(hour: Int, minute: Int, is24Hour: Boolean): String {
    val h = if (is24Hour) hour else (hour % 12).let { if (it == 0) 12 else it }
    return if (is24Hour) "%02d:%02d".format(Locale.ROOT, h, minute) else "%d:%02d".format(Locale.ROOT, h, minute)
}

/** iOS lock date line: "10月21日 星期三" / "Wednesday, October 21" (US) / "Wednesday 21 October". */
/**
 * The date line. Chinese: "10月21日 星期三"; with the lunar date on (iOS 「中国农历」, the owner's iPhone,
 * round 12.4) iOS switches to the short weekday and appends the lunar line: "10月21日周三 · 丙午年九月十二".
 */
internal fun lockDateText(date: LocalDate, locale: Locale, lunar: String? = null): String {
    if (locale.language == "zh") {
        if (lunar != null) {
            val short = DateTimeFormatter.ofPattern("EEE", Locale.SIMPLIFIED_CHINESE).format(date)
            return "${date.monthValue}月${date.dayOfMonth}日$short · $lunar"
        }
        val weekday = DateTimeFormatter.ofPattern("EEEE", Locale.SIMPLIFIED_CHINESE).format(date)
        return "${date.monthValue}月${date.dayOfMonth}日 $weekday"
    }
    val pattern = if (locale.country in setOf("US", "CA", "PH")) "EEEE, MMMM d" else "EEEE d MMMM"
    return DateTimeFormatter.ofPattern(pattern, locale).format(date)
}

/**
 * Swipe up to unlock, measured on the owner's iOS 26 recording (`ios26-lock-swipe-unlock.mov`,
 * 1320×2868, frames at 30 fps; the home indicator inside the slab tracks the finger 1:1):
 * - the bottom block is one Liquid-Glass slab that lifts 1:1 with the finger (f003 → f106: indicator
 *   y 2832 → 242 px, monotonic), a rubber band downward;
 * - the date and clock translate with it and fade: still full at ≈ 0.04 H of travel (f005), ≈ half at
 *   0.18 H (f026), gone at 0.28 H (f040);
 * - the wallpaper zooms with the travel: 1.035 at 0.42 H (f060), 1.05 at 0.57 H (f080), 1.075 at
 *   0.88 H (f104) → 1 + 0.085·travel/H, i.e. [HANDOFF_WALLPAPER_SCALE] when the slab has left;
 * - released past the threshold the slab keeps going at the release speed (SwiftUI `.smooth`) until
 *   it has cleared the top edge, Home is underneath by then; a cancel springs everything back.
 */
internal object LockSwipe {
    const val COMMIT_FRACTION = .12f
    const val FLING_DP_PER_S = 450f
    const val MIN_FLING_TRAVEL_DP = 12f
    const val CANCEL_FLING_DP_PER_S = -300f
    /**
     * Round 12.2, owner's iOS 26 recording `reference/user-videos-r12/core29-lock-unlock.mp4` (1320×2868, 30 fps,
     * frames fNNN of `fps=30`): the clock, date and widgets are not dissolved in place — they ride up 1:1 with
     * the finger and are cut off at the status bar's lower edge (f021: the date is under the status bar at
     * 162 px of travel; f025: the digits' top is cut; f029–f033: gone, at ≈ 0.12 H). That line is 180 px of
     * 2868 = 0.063 H. Round 11 read the same frames as a fade over 0.04–0.28 H: it was the clip.
     */
    const val CLOCK_CLIP_FRACTION = .063f
    /** Reduce Motion only: the clock dissolves in place over this travel instead of riding out. */
    const val CLOCK_FADE_START = .04f
    const val CLOCK_FADE_END = .28f
    /** Largest downward rubber-band offset, fraction of H. */
    const val RUBBER_BAND_FRACTION = .08f
    /**
     * At rest the bottom block sits plainly on the wallpaper (f001: no edge, no tint); the glass
     * materialises as the drag begins — in by f005 (0.033 H of travel), so over the first 0.04 H.
     */
    const val MATERIAL_IN_FRACTION = .04f
    /**
     * Home takes over once the released slab has done this much of its exit (f130–f133: the slab is parked
     * with its top edge at the status bar line when Home cuts in, icons still large).
     */
    const val CLEARED_FRACTION = .97f
    /**
     * Round 13: where the released slab goes. The home indicator (12 pt above the slab's bottom edge) is at
     * 2832 px at rest, 1393 px at the release (f100) and 250 px when it parks (f130): 0.90 H of travel, the
     * slab's bottom edge ≈ 0.10 H from the top, only its last sliver under the status bar (f129). Core.29.2
     * parked its *top* edge there and left a 221 dp glass band (widgets, flashlight, camera) across the top
     * 40 % of the cover when Home cut in. Home cuts in at [CLEARED_FRACTION] of it (bottom edge ≈ 0.13 H).
     */
    const val EXIT_TRAVEL_FRACTION = .90f
    /**
     * Wallpaper zoom reached when the slab has left (travel = H): one constant with the arrival's
     * first frame (`UnlockArrivalMotion.WALLPAPER_START_SCALE`), so the last lock frame and Home's
     * first are the same image at the same scale.
     */
    const val HANDOFF_WALLPAPER_SCALE = UnlockArrivalMotion.WALLPAPER_START_SCALE
    /**
     * The released slab: from the release at f100 (bar 1393 px) it first gathers speed, peaks near
     * 1200 px/s around f110 and settles at the status bar line by f130 — a critically damped spring of
     * ≈ 1.0 s, i.e. SwiftUI response 0.65 s, no bounce.
     */
    val exitSpring = IosSpring(.65f, 1f)
    /** Round 13: unlocked by the side-key fingerprint (no finger on the glass): the same exit, a little quicker. */
    val autoExitSpring = IosSpring(.5f, 1f)
    /** Cancel / bouncer cancelled: everything springs back, ζ 0.86. */
    val cancelSpring = IosSpring.fromStiffness(260f, .86f)

    fun shouldUnlock(travelUpDp: Float, velocityUpDpPerS: Float, heightDp: Float): Boolean {
        if (velocityUpDpPerS <= CANCEL_FLING_DP_PER_S) return false
        return travelUpDp >= heightDp * COMMIT_FRACTION ||
            (velocityUpDpPerS >= FLING_DP_PER_S && travelUpDp >= MIN_FLING_TRAVEL_DP)
    }

    /** Offset of the slab and the clock in dp (negative = up): 1:1 upward, a rubber band downward. */
    fun contentOffset(travelUpDp: Float, heightDp: Float): Float {
        if (travelUpDp >= 0f) return -travelUpDp
        val limit = heightDp * RUBBER_BAND_FRACTION
        val pull = -travelUpDp
        return limit * (1f - 1f / (pull / limit * .55f + 1f))
    }

    private fun fraction(travelUpDp: Float, heightDp: Float): Float =
        if (heightDp <= 0f) 0f else (travelUpDp / heightDp).coerceAtLeast(0f)

    /** Reduce Motion: clock + date opacity for a finger travel (with motion the clock rides out under [clockClipTop]). */
    fun clockAlpha(travelUpDp: Float, heightDp: Float): Float {
        val t = fraction(travelUpDp, heightDp)
        return (1f - (t - CLOCK_FADE_START) / (CLOCK_FADE_END - CLOCK_FADE_START)).coerceIn(0f, 1f)
    }

    /** The slab never fades (f100–f130: solid up to the moment Home cuts in); kept for Reduce Motion's dissolve. */
    fun slabAlpha(travelUpDp: Float, heightDp: Float): Float = 1f

    /** Top of the region the moving clock layer is drawn in (dp from the panel's top): the status bar line. */
    fun clockClipTop(heightDp: Float): Float = heightDp.coerceAtLeast(0f) * CLOCK_CLIP_FRACTION

    /** Opacity of the slab's glass material: none at rest, full once the drag has moved it 4 % of H either way. */
    fun slabMaterialAlpha(travelUpDp: Float, heightDp: Float): Float =
        if (heightDp <= 0f) 0f else (kotlin.math.abs(travelUpDp) / (heightDp * MATERIAL_IN_FRACTION)).coerceIn(0f, 1f)

    /** Released and this far along its exit, Home may take over underneath (f130: slab parked at the top). */
    fun slabCleared(travelUpDp: Float, exitTravelDp: Float): Boolean = exitTravelDp > 0f && travelUpDp >= exitTravelDp * CLEARED_FRACTION

    /** Wallpaper zoom: 1 at rest, [HANDOFF_WALLPAPER_SCALE] once the slab has left (round 12.2: iOS 26 keeps it at 1). */
    fun wallpaperScale(travelUpDp: Float, heightDp: Float): Float =
        1f + (HANDOFF_WALLPAPER_SCALE - 1f) * fraction(travelUpDp, heightDp).coerceAtMost(1f)

    /** Where the released slab goes: its bottom edge parked ≈ 0.10 H from the top (f130), see [EXIT_TRAVEL_FRACTION]. */
    fun exitTravel(heightDp: Float): Float = heightDp.coerceAtLeast(0f) * EXIT_TRAVEL_FRACTION
}

/**
 * The bottom block of the lock screen — the glass slab and what rides in it. Measured on the
 * recording's rest frame (f001, 1320×2868 = 440×956 pt @3×) and expressed in those points, scaled
 * by the panel's short edge / 440 pt (the panels' aspect ratios transfer Apple layouts 1:1, BRIEF):
 * - slab top 233 pt from the bottom (f058/f080: the slab is ≈ 0.244 H tall), corners ≈ the display's;
 * - widget row: circular widgets Ø 60 pt, slot pitch 96 pt, left inset 44 pt, centre 167 pt from the
 *   bottom (ring at (807, 2367) px, battery at (1096, 2367) px); the rectangular weather widget spans
 *   two slots (Ø + pitch wide);
 * - flashlight/camera Ø 56 pt (167 px), centres 87 pt from the bottom at 0.19 W / 0.81 W;
 * - home indicator 0.354 W × 4.3 pt, 12 pt from the bottom.
 * On the cover and the unfolded inner panel the two buttons keep the owner's round-8 side column
 * (x = 89.6 %W / 94.9 %W, flashlight above camera) and the widget row sits beside them, left of the
 * column, at the flashlight's height; inner portrait is the recording's layout.
 */
internal data class LockSlabLayout(
    /** Top edge of the slab at rest (dp). */
    val slabTop: Float,
    /** Vertical centre of the widget row (dp); where the row goes when it is under the date instead. */
    val widgetRowCenterY: Float,
    val underDateRowCenterY: Float,
    val widgetRowLeft: Float,
    /** Diameter of a circular widget = one unit (dp). */
    val unit: Float,
    /** Distance between the starts of two consecutive units (dp). */
    val pitch: Float,
    /** Fallback corner radius when the display reports none (dp). */
    val cornerRadius: Float,
    val homeBarBottomInset: Float,
    val homeBarHeight: Float,
) {
    val rectangularWidth: Float get() = unit + pitch
    /** Width of the full four-unit row (dp). */
    val rowWidth: Float get() = unit + pitch * 3f
    fun slotLeft(rowLeft: Float, startUnit: Int): Float = rowLeft + pitch * startUnit
    fun widgetWidth(kind: LockWidgetKind): Float = if (kind.circular) unit else rectangularWidth
}

internal object LockSlabSpec {
    const val REC_WIDTH_PT = 440f
    const val SLAB_TOP_PT = 233f
    const val ROW_CENTER_PT = 167f
    const val ROW_INSET_PT = 44f
    const val UNIT_PT = 60f
    const val PITCH_PT = 96f
    const val CONTROL_PT = 56f
    const val CONTROL_BOTTOM_PT = 87f
    const val CONTROL_PITCH_PT = 76f
    const val CONTROL_X = .19f
    const val CORNER_PT = 55f
    const val HOME_BAR_BOTTOM_PT = 12f
    const val HOME_BAR_HEIGHT_PT = 4.3f
    const val HOME_BAR_WIDTH = .354f
    /** Gap between the row/buttons and the slab's top edge (233 − 167 − 30 = 36 pt). */
    const val SLAB_PADDING_PT = 36f
    /** Under-date row: this far below the clock's ink. */
    const val UNDER_DATE_GAP_PT = 24f
    /** Gap between the row and the side column on panels that have one. */
    const val COLUMN_GAP = .03f
}

internal fun lockSlabLayout(widthDp: Float, heightDp: Float, geometry: LockScreenGeometry): LockSlabLayout {
    val s = min(widthDp, heightDp) / LockSlabSpec.REC_WIDTH_PT
    val unit = LockSlabSpec.UNIT_PT * s
    val sideColumn = geometry.panel != LockPanel.INNER_PORTRAIT
    val rowCenterY = if (sideColumn) geometry.flashlight.y else heightDp - LockSlabSpec.ROW_CENTER_PT * s
    val left = if (sideColumn) LockSlabSpec.ROW_INSET_PT * s else widthDp * .1f
    val pitch = if (sideColumn) {
        // Four units between the inset and the column: the recording's 96 pt where it fits, less on the cover.
        val right = geometry.flashlight.x - geometry.controlDiameter / 2f - widthDp * LockSlabSpec.COLUMN_GAP
        minOf(LockSlabSpec.PITCH_PT * s, (right - left - unit) / 3f)
    } else LockSlabSpec.PITCH_PT * s
    val slabTop = minOf(rowCenterY - unit / 2f, geometry.flashlight.y - geometry.controlDiameter / 2f) - LockSlabSpec.SLAB_PADDING_PT * s
    return LockSlabLayout(
        slabTop = slabTop,
        widgetRowCenterY = rowCenterY,
        underDateRowCenterY = geometry.clockTop + geometry.clockDigitHeight + LockSlabSpec.UNDER_DATE_GAP_PT * s + unit / 2f,
        widgetRowLeft = left,
        unit = unit,
        pitch = pitch,
        cornerRadius = LockSlabSpec.CORNER_PT * s,
        homeBarBottomInset = LockSlabSpec.HOME_BAR_BOTTOM_PT * s,
        homeBarHeight = LockSlabSpec.HOME_BAR_HEIGHT_PT * s,
    )
}

/** Left edge of the under-date row: hung on the same anchor as the clock (居中 centred on it, 靠左 / 靠右 at the area's edge). */
internal fun lockUnderDateRowLeft(geometry: LockScreenGeometry, layout: LockSlabLayout, align: LockTextAlign): Float =
    lockTextAnchor(geometry, align).left(layout.rowWidth)

/** Centre of the under-date row once the size handle has stretched the clock by [stretch] (0..1). */
internal fun lockUnderDateRowCenterY(geometry: LockScreenGeometry, layout: LockSlabLayout, stretch: Float): Float =
    layout.underDateRowCenterY + geometry.clockDigitHeight * (LockClockSize.heightScale(stretch) - 1f)

/** Slab top when the widgets are under the date: only the controls and the indicator ride in it. */
internal fun lockSlabTopWithoutWidgets(geometry: LockScreenGeometry, layout: LockSlabLayout, widthDp: Float, heightDp: Float): Float =
    geometry.flashlight.y - geometry.controlDiameter / 2f - LockSlabSpec.SLAB_PADDING_PT * (min(widthDp, heightDp) / LockSlabSpec.REC_WIDTH_PT)

/** Everything the screen-off launcher knows when it decides whether to put the 18Duo lock screen up. */
internal data class LockLaunchState(
    val enabled: Boolean,
    val isDefaultHome: Boolean,
    val keyguardLocked: Boolean,
    val standbyClockVisible: Boolean,
    /** A call, an incoming ring or an alarm: their own lock-screen UI must stay on top. */
    val callOrAlarm: Boolean,
    val alreadyShowing: Boolean,
    val healthy: Boolean,
)

internal fun lockScreenShouldLaunch(state: LockLaunchState): Boolean = with(state) {
    enabled && isDefaultHome && keyguardLocked && !standbyClockVisible && !callOrAlarm && !alreadyShowing && healthy
}

/** The 18Duo lock screen leaves the moment the device is unlocked by any path (fingerprint, PIN, Smart Lock). */
internal fun lockScreenShouldLeave(keyguardLocked: Boolean, enabled: Boolean): Boolean = !keyguardLocked || !enabled

/**
 * Self-protection: each launch is marked pending until the activity draws a frame (or finishes
 * normally). A launch HyperOS refused (no "Show on lock screen" / background pop-up permission) or one
 * that crashed leaves its mark; after [MAX_UNSEEN] such launches in a row the feature turns itself off
 * and Settings says why. Android's own keyguard is underneath the whole time.
 */
internal object LockScreenHealth {
    const val MAX_UNSEEN = 2
    /**
     * Round 12 launches catch up (display ON, keyguard locked, +300 ms): a retry fired while the
     * previous launch is still being created must not count it as unseen. A pending launch counts
     * only once it is older than this.
     */
    const val PENDING_GRACE_MS = 2_000L
    fun afterLaunch(previousStillPending: Boolean, unseen: Int, pendingAgeMs: Long = PENDING_GRACE_MS): Int =
        if (previousStillPending && pendingAgeMs >= PENDING_GRACE_MS) unseen + 1 else unseen
    fun healthy(unseen: Int): Boolean = unseen < MAX_UNSEEN
}

/**
 * Delays after ACTION_SCREEN_OFF at which the launcher re-checks the keyguard: it locks at once on a
 * power-button press, or after the "lock after timeout" grace (5 s by default) when the screen timed out.
 */
internal val LOCK_SCREEN_CHECK_DELAYS_MS = longArrayOf(0L, 350L, 1200L, 3000L, 5500L, 9000L)

/** Long-press for the flashlight and camera controls (iOS: press and hold until the haptic). */
internal const val LOCK_CONTROL_HOLD_MS = 350L

/** Long-press on the lock screen itself opens the customisation editor after the system unlock (iOS: Face ID). */
internal const val LOCK_EDIT_HOLD_MS = 600L

/** Distance in dp between two points, for "is the finger still on the control". */
internal fun lockDistance(ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = ax - bx; val dy = ay - by
    return sqrt(dx * dx + dy * dy)
}
