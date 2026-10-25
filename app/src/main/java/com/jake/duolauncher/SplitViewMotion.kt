package com.jake.duolauncher

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Which half of the inner panel the dragged app should take. */
internal enum class SplitSide { LEFT, RIGHT;
    val other: SplitSide get() = if (this == LEFT) RIGHT else LEFT
    /** AOSP/HyperOS `SplitScreenConstants`: 0 = SPLIT_POSITION_TOP_OR_LEFT, 1 = BOTTOM_OR_RIGHT. */
    val position: Int get() = if (this == LEFT) 0 else 1
}

/** The two Liquid-Glass panes, in panel dp. */
internal data class SplitPanes(val left: ZoomBounds, val right: ZoomBounds, val radius: Float)

/**
 * Pure geometry and decisions for iPhone Duo's Split View gesture (round 8, photos 9–10; round 11,
 * the owner's recording `reference/user-videos-r11/iphone-duo-inner-swipe-up-split.mov`): swipe an
 * app up and, as its card shrinks, two tall frosted Liquid-Glass panes fade in at the left and
 * right of the inner panel while the card is still centred (recording 30 fps f034–f040: the panes
 * are in by the time the card is ≈ 0.6 of the panel). Carrying the card to a side makes that pane
 * spring outward and grow toward its half while the other gives way (f040–f050); letting go over
 * it makes the app the split app of that side (f052–f062).
 *
 * Coexistence with the App Switcher (same gesture): a pause in the centre still opens the two-card
 * deck, and the panes recede behind it (photo 10 shows them faint beside the deck); carried clearly
 * to one side the panes come back fully and the deck steps aside; back near the centre the deck
 * returns. Everything is a continuous function of the card's size and position, so it is reversible.
 */
internal object SplitViewMotion {
    /** Inner panel unfolded, held landscape (860 × 608 dp on the Xiaomi). Not the cover, not portrait. */
    const val MIN_SHORT_SIDE = 560f
    const val MIN_LONG_SIDE = 760f

    /** Card centre offset from the panel centre, in panel widths: inside this the switcher owns the hover. */
    const val CENTRE_ZONE = .08f
    /** Offset at which the card counts as fully carried to a side (the deck has stepped aside). */
    const val FULL_ZONE = .17f
    /** Offset at which the targeted pane has grown all the way (keeps growing with the finger past [FULL_ZONE]). */
    const val GROW_ZONE = .3f
    /** Reveal from which the panes may own the hover (with the card carried to a side). */
    const val OWN_REVEAL = .3f
    /** Sideness from which the panes own the hover and the switcher deck steps aside. */
    const val OWN_SIDENESS = .5f
    /** The panes start fading in once the card has shrunk to this scale (recording f031–f034: ≈ 0.8)… */
    const val REVEAL_SCALE_START = .8f
    /** …and are fully in by this scale, the hover size (recording f036–f040: ≈ 0.6). */
    const val REVEAL_SCALE_FULL = .6f
    /** With the deck engaged in the centre the panes recede to this fraction (photo 10: faint beside the deck). */
    const val DECK_RECEDE = .6f
    /** Release over a side commits once the card centre is at least this far out (with the panes mostly in). */
    const val COMMIT_ZONE = .13f
    /** A flick up this fast still goes Home, panes or not (iOS). */
    const val HOME_FLICK_VELOCITY = SwitcherMotion.HOME_FLICK_VELOCITY
    /** Sideways momentum carries the release this far ahead (seconds), so a toss toward a side counts. */
    const val PROJECTION = .12f

    /**
     * Card width (panel widths) the app shrinks to once carried into a pane (recording f044–f048:
     * ≈ 0.2–0.24 W under the finger; photo 9: ≈ ¼ of the panel). Centred it keeps its hover size.
     */
    const val HOVER_CARD_WIDTH = .24f

    // Pane geometry in panel fractions, measured on the recording (ROUND11: rest 0.40 W × 0.86 H
    // inset 0.025 W, f040; grown ≈ 0.5 W × 0.95 H, f046–f050; the other pane shrinks a little).
    const val EDGE_INSET = .025f
    const val GROWN_EDGE_INSET = .015f
    const val REST_WIDTH = .4f
    const val GROWN_WIDTH = .5f
    const val SHRUNK_WIDTH = .34f
    const val REST_HEIGHT = .86f
    const val GROWN_HEIGHT = .95f
    const val SHRUNK_HEIGHT = .8f
    /** Not yet in: each pane sits this much smaller, at its outer edge (it materialises outward). */
    const val ENTRY_SCALE = .9f
    /** Divider between the two halves once in split (dp; AOSP 24 px, HyperOS 24 px at 440 dpi). */
    const val DIVIDER = 9f

    fun available(width: Float, height: Float): Boolean =
        width > height && min(width, height) >= MIN_SHORT_SIDE && max(width, height) >= MIN_LONG_SIDE

    /** Signed card-centre offset from the panel centre in panel widths (negative = left). */
    fun offset(cardCenterX: Float, width: Float): Float = (cardCenterX - width / 2f) / max(width, 1f)

    fun side(offset: Float): SplitSide = if (offset < 0f) SplitSide.LEFT else SplitSide.RIGHT

    /** How far the panes are in (0…1) purely from the card's [scale]: 0 at full screen, 1 at the hover size. */
    fun shrinkReveal(scale: Float): Float =
        SwitcherMotion.smooth((REVEAL_SCALE_START - scale) / (REVEAL_SCALE_START - REVEAL_SCALE_FULL))

    /** How far the card is carried to a side (0 in the centre zone, 1 from [FULL_ZONE] on). */
    fun sideness(offset: Float): Float = SwitcherMotion.smooth((abs(offset) - CENTRE_ZONE) / (FULL_ZONE - CENTRE_ZONE))

    /**
     * How far the panes are in (0…1): a function of how far the card has shrunk ([scale]), with
     * the card still centred — they never wait for a hover or a side. With the switcher [deck]
     * engaged (0…1) in the centre they recede; carried to a side they come back fully. [calm]:
     * a deliberate drag (a flick home never shows them, like the deck).
     */
    fun reveal(offset: Float, scale: Float, deck: Float, calm: Boolean): Float {
        if (!calm) return 0f
        val recede = 1f - DECK_RECEDE * deck.coerceIn(0f, 1f)
        return shrinkReveal(scale) * max(sideness(offset), recede)
    }

    /** The panes own the hover (the deck must stay out) once the card is clearly at a side with the panes in. */
    fun ownsHover(offset: Float, reveal: Float): Boolean = sideness(offset) >= OWN_SIDENESS && reveal >= OWN_REVEAL

    /** Growth (0…1) of the pane on the card's side; the other pane gives way by the same amount. */
    fun grow(offset: Float): Float = SwitcherMotion.smooth((abs(offset) - CENTRE_ZONE) / (GROW_ZONE - CENTRE_ZONE))

    /**
     * The side the release puts the app on, or null for today's behaviour (switcher, Home, back to
     * the app). [velocityX] dp/s rightward, [velocityUp] dp/s upward.
     */
    fun commit(offset: Float, velocityX: Float, velocityUp: Float, width: Float, reveal: Float, engaged: Boolean): SplitSide? {
        if (!engaged || reveal < .5f) return null
        if (velocityUp >= HOME_FLICK_VELOCITY) return null
        val projected = offset + velocityX * PROJECTION / max(width, 1f)
        // A toss back toward the centre cancels; a toss outward from just inside the zone counts.
        if (abs(projected) < COMMIT_ZONE || projected * offset < 0f) return null
        return side(projected)
    }

    /**
     * Both panes. [leftGrow]/[rightGrow] are each pane's spring (0 rest, 1 grown, may overshoot a
     * little: the "弹" the owner asked for); a pane shrinks while the other one grows.
     */
    fun panes(width: Float, height: Float, reveal: Float, leftGrow: Float, rightGrow: Float, screenRadius: Float): SplitPanes {
        val r = reveal.coerceIn(0f, 1f)
        fun pane(own: Float, other: Float, left: Boolean): ZoomBounds {
            val shrink = (other - own).coerceIn(0f, 1f)
            val g = own
            var w = lerpOver(REST_WIDTH, GROWN_WIDTH, g) - (REST_WIDTH - SHRUNK_WIDTH) * shrink
            var h = lerpOver(REST_HEIGHT, GROWN_HEIGHT, g) - (REST_HEIGHT - SHRUNK_HEIGHT) * shrink
            val inset = lerpOver(EDGE_INSET, GROWN_EDGE_INSET, g.coerceAtLeast(0f))
            val s = ENTRY_SCALE + (1f - ENTRY_SCALE) * r
            w *= s; h *= s
            val pw = w * width; val ph = h * height
            val outer = inset * width
            return if (left) ZoomBounds(outer, height / 2f - ph / 2f, outer + pw, height / 2f + ph / 2f)
                else ZoomBounds(width - outer - pw, height / 2f - ph / 2f, width - outer, height / 2f + ph / 2f)
        }
        return SplitPanes(pane(leftGrow, rightGrow, true), pane(rightGrow, leftGrow, false), paneRadius(width, screenRadius))
    }

    /** Concentric with the display corner, as Apple insets its glass. */
    fun paneRadius(width: Float, screenRadius: Float): Float = max(18f, screenRadius - EDGE_INSET * width * .35f)

    /** Where each app sits once the panel is split (dp). */
    fun half(side: SplitSide, width: Float, height: Float): ZoomBounds =
        if (side == SplitSide.LEFT) ZoomBounds(0f, 0f, width / 2f - DIVIDER / 2f, height)
        else ZoomBounds(width / 2f + DIVIDER / 2f, 0f, width, height)

    /**
     * The card in hand: carried into a pane it eases from its drag size toward [HOVER_CARD_WIDTH]
     * with [sideness] (recording f040–f046), its bottom edge staying where the finger holds it;
     * centred it keeps its size (the panes alone do not shrink it).
     */
    fun hoverCard(drag: ZoomBounds, width: Float, sideness: Float): ZoomBounds {
        val r = sideness.coerceIn(0f, 1f)
        val targetW = min(drag.width, width * HOVER_CARD_WIDTH)
        val nw = SwitcherMotion.lerp(drag.width, targetW, r)
        val nh = drag.height * (nw / max(drag.width, 1f))
        return ZoomBounds(drag.centerX - nw / 2f, drag.bottom - nh, drag.centerX + nw / 2f, drag.bottom)
    }

    /** Linear between [from] and [to] that keeps going past 1 (spring overshoot) and below 0. */
    private fun lerpOver(from: Float, to: Float, t: Float) = from + (to - from) * t.coerceIn(-.2f, 1.25f)
}

/**
 * Fixed shell commands for the local-debugging route and parsers for their output. Only integers
 * we validated are ever interpolated (task ids and 0/1 positions): no caller-supplied text reaches
 * the shell.
 */
internal object SplitShellCommands {
    const val HELP = "dumpsys activity service SystemUIService WMShell help"
    const val TASKS = "cmd activity stack list | grep taskId="

    enum class Family { HYPEROS_SOSC, AOSP }

    fun family(help: String): Family? = when {
        "moveToSoScSplitStage" in help -> Family.HYPEROS_SOSC
        "moveToSideStage" in help -> Family.AOSP
        else -> null
    }

    /** Commands to try in order for putting [taskId] at [side]; the first that splits wins. */
    fun moves(family: Family, taskId: Int, side: SplitSide): List<String> {
        require(taskId > 0)
        val p = side.position
        val shell = "dumpsys activity service SystemUIService WMShell"
        val aosp = "$shell splitscreen moveToSideStage $taskId $p"
        return when (family) {
            Family.HYPEROS_SOSC -> listOf(
                "$shell SoScSplitScreen moveToSoScSplitStage $taskId $p",
                "$shell SoScSplitScreen soScStartTask $taskId $p",
                aosp,
            )
            Family.AOSP -> listOf(aosp)
        }
    }

    /** Put the split back the other way round (the app landed on the wrong side). */
    fun swap(family: Family): String = "dumpsys activity service SystemUIService WMShell " +
        if (family == Family.HYPEROS_SOSC) "SoScSplitScreen swapSplitTasks" else "splitscreen switchSplitPosition"

    data class TaskLine(val id: Int, val packageName: String, val userId: Int, val visible: Boolean,
        val left: Int, val right: Int)

    private val line = Regex("""taskId=(\d+): ([\w.]+)/\S+ bounds=\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)] userId=(\d+) visible=(true|false)""")

    /** `cmd activity stack list` task lines, top-most first. */
    fun parseTasks(output: String): List<TaskLine> = output.lineSequence().mapNotNull { l ->
        line.find(l)?.destructured?.let { (id, pkg, left, _, right, _, user, visible) ->
            TaskLine(id.toInt(), pkg, user.toInt(), visible == "true", left.toInt(), right.toInt())
        }
    }.toList()

    /** The task of [packageName] for [userId], preferring a visible one (the app the gesture started over). */
    fun taskOf(tasks: List<TaskLine>, packageName: String, userId: Int): TaskLine? {
        val mine = tasks.filter { it.packageName == packageName && it.userId == userId }
        return mine.firstOrNull { it.visible } ?: mine.firstOrNull()
    }

    /** Which half a task occupies, or null while it is (still) full width. [panelWidth] in px. */
    fun sideOf(task: TaskLine, panelWidth: Int): SplitSide? {
        val w = task.right - task.left
        if (w <= 0 || w > panelWidth * .75f) return null
        return if ((task.left + task.right) / 2 < panelWidth / 2) SplitSide.LEFT else SplitSide.RIGHT
    }
}
