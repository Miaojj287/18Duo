package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitViewMotionTest {
    private val w = 860f
    private val h = 608f
    private val lift = h * .3f

    @Test fun `only the inner panel held landscape offers split view`() {
        assertTrue(SplitViewMotion.available(860f, 608f))
        assertFalse(SplitViewMotion.available(425f, 622f)) // cover
        assertFalse(SplitViewMotion.available(608f, 860f)) // inner portrait
    }

    @Test fun `the panes fade in as the card shrinks, with the card still centred`() {
        // Recording f031–f040: nothing at full screen, in from ≈ 0.8, fully in by the hover size ≈ 0.6.
        assertEquals(0f, SplitViewMotion.reveal(0f, 1f, 0f, calm = true), 0f)
        assertEquals(0f, SplitViewMotion.reveal(0f, SplitViewMotion.REVEAL_SCALE_START, 0f, calm = true), 0f)
        val partial = SplitViewMotion.reveal(0f, .7f, 0f, calm = true)
        assertTrue(partial > 0f && partial < 1f)
        assertEquals(1f, SplitViewMotion.reveal(0f, SplitViewMotion.REVEAL_SCALE_FULL, 0f, calm = true), 1e-4f)
        assertEquals(1f, SplitViewMotion.reveal(0f, .5f, 0f, calm = true), 1e-4f)
        // They never wait for the card to be carried to a side.
        assertEquals(SplitViewMotion.reveal(0f, .6f, 0f, true), SplitViewMotion.reveal(.25f, .6f, 0f, true), 1e-6f)
    }

    @Test fun `the reveal only ever grows as the card shrinks`() {
        var last = -1f
        for (i in 0..20) {
            val r = SplitViewMotion.shrinkReveal(1f - i * .03f)
            assertTrue(r >= last && r in 0f..1f); last = r
        }
        assertEquals(0f, SplitViewMotion.sideness(0f), 0f)
        assertEquals(1f, SplitViewMotion.sideness(-SplitViewMotion.FULL_ZONE), 1e-5f)
    }

    @Test fun `a flick home never shows the panes`() {
        assertEquals(0f, SplitViewMotion.reveal(0f, .5f, 0f, calm = false), 0f)
    }

    @Test fun `the deck engaging in the centre makes the panes recede, carrying the card out brings them back`() {
        val centred = SplitViewMotion.reveal(0f, .6f, 1f, calm = true)
        assertEquals(1f - SplitViewMotion.DECK_RECEDE, centred, 1e-4f) // faint beside the deck (photo 10)
        assertTrue(centred > 0f)
        assertEquals(1f, SplitViewMotion.reveal(.25f, .6f, 1f, calm = true), 1e-4f)
        assertEquals(1f, SplitViewMotion.reveal(-.25f, .6f, 1f, calm = true), 1e-4f)
        val half = SplitViewMotion.reveal(.12f, .6f, 1f, calm = true)
        assertTrue(half > centred && half < 1f)
    }

    @Test fun `the centre stays the switcher's, a side is the panes'`() {
        assertFalse(SplitViewMotion.ownsHover(0f, 1f))
        assertFalse(SplitViewMotion.ownsHover(SplitViewMotion.CENTRE_ZONE, 1f))
        assertTrue(SplitViewMotion.ownsHover(.2f, 1f))
        assertTrue(SplitViewMotion.ownsHover(-.2f, 1f))
        // Panes not in yet (card still big): the deck keeps the hover even at a side.
        assertFalse(SplitViewMotion.ownsHover(.2f, .1f))
    }

    @Test fun `wherever a release can commit, the panes already own the hover`() {
        val r = SplitViewMotion.reveal(SplitViewMotion.COMMIT_ZONE, .6f, 1f, calm = true)
        assertTrue(r >= .5f)
        assertTrue(SplitViewMotion.ownsHover(SplitViewMotion.COMMIT_ZONE, r))
    }

    @Test fun `growth keeps rising with the finger past full reveal`() {
        assertTrue(SplitViewMotion.grow(.2f) < SplitViewMotion.grow(.26f))
        assertEquals(1f, SplitViewMotion.grow(.35f), 1e-4f)
        assertEquals(0f, SplitViewMotion.grow(.05f), 0f)
    }

    @Test fun `release over a pane picks its side`() {
        assertEquals(SplitSide.RIGHT, SplitViewMotion.commit(.22f, 0f, 0f, w, 1f, true))
        assertEquals(SplitSide.LEFT, SplitViewMotion.commit(-.22f, 0f, 0f, w, 1f, true))
    }

    @Test fun `release keeps today's behaviour in the centre, on a home flick, or without panes`() {
        assertNull(SplitViewMotion.commit(.05f, 0f, 0f, w, 1f, true))
        assertNull(SplitViewMotion.commit(.25f, 0f, 2_000f, w, 1f, true))
        assertNull(SplitViewMotion.commit(.25f, 0f, 0f, w, .2f, true))
        assertNull(SplitViewMotion.commit(.25f, 0f, 0f, w, 1f, false))
    }

    @Test fun `a toss back toward the centre cancels, a toss outward counts`() {
        assertNull(SplitViewMotion.commit(.16f, -1_500f, 0f, w, 1f, true))
        assertEquals(SplitSide.LEFT, SplitViewMotion.commit(-.12f, -800f, 0f, w, 1f, true))
    }

    @Test fun `the targeted pane grows outward and the other gives way`() {
        val rest = SplitViewMotion.panes(w, h, 1f, 0f, 0f, 28f)
        val grown = SplitViewMotion.panes(w, h, 1f, 0f, 1f, 28f)
        assertEquals(rest.left.width, rest.right.width, 1e-3f)
        // Recording f040: at rest 0.40 W × 0.86 H, inset 0.025 W; f046–f050: grown ≈ 0.5 W × 0.95 H.
        assertEquals(.40f * w, rest.right.width, .5f); assertEquals(.86f * h, rest.right.height, .5f)
        assertEquals(.025f * w, w - rest.right.right, .5f)
        assertEquals(.5f * w, grown.right.width, .5f); assertEquals(.95f * h, grown.right.height, .5f)
        assertTrue(grown.right.width > rest.right.width)
        assertTrue(grown.right.height > rest.right.height)
        assertTrue(grown.right.right > rest.right.right) // springs toward its outer edge
        assertTrue(grown.left.width < rest.left.width)
        // Never past the panel, never overlapping.
        assertTrue(grown.right.right <= w && grown.left.left >= 0f && grown.left.right < grown.right.left)
    }

    @Test fun `a spring overshoot swells a little past grown without breaking the layout`() {
        val over = SplitViewMotion.panes(w, h, 1f, 0f, 1.2f, 28f)
        val grown = SplitViewMotion.panes(w, h, 1f, 0f, 1f, 28f)
        assertTrue(over.right.width > grown.right.width)
        assertTrue(over.right.right <= w + 1f)
    }

    @Test fun `panes materialise outward from their edges`() {
        val half = SplitViewMotion.panes(w, h, .5f, 0f, 0f, 28f)
        val full = SplitViewMotion.panes(w, h, 1f, 0f, 0f, 28f)
        assertTrue(half.left.width < full.left.width)
        assertEquals(full.left.left, half.left.left, 1e-3f)
        assertEquals(full.right.right, half.right.right, 1e-3f)
    }

    @Test fun `halves leave a divider at the hinge`() {
        val l = SplitViewMotion.half(SplitSide.LEFT, w, h)
        val r = SplitViewMotion.half(SplitSide.RIGHT, w, h)
        assertEquals(SplitViewMotion.DIVIDER, r.left - l.right, 1e-3f)
        assertEquals(0f, l.left, 0f); assertEquals(w, r.right, 0f)
    }

    @Test fun `the card shrinks under the finger only as it is carried into a pane`() {
        val drag = ZoomBounds(200f, 100f, 200f + w * .6f, 100f + h * .6f)
        val card = SplitViewMotion.hoverCard(drag, w, 1f)
        assertEquals(drag, SplitViewMotion.hoverCard(drag, w, SplitViewMotion.sideness(0f)))
        assertTrue(SplitViewMotion.hoverCard(drag, w, SplitViewMotion.sideness(.12f)).width < drag.width)
        assertEquals(w * SplitViewMotion.HOVER_CARD_WIDTH, card.width, 1e-3f)
        assertEquals(drag.bottom, card.bottom, 1e-3f)
        assertEquals(drag.centerX, card.centerX, 1e-3f)
        assertEquals(drag.width / drag.height, card.width / card.height, 1e-3f)
        assertEquals(drag, SplitViewMotion.hoverCard(drag, w, 0f))
    }

    @Test fun `sides map to WMShell split positions`() {
        assertEquals(0, SplitSide.LEFT.position)
        assertEquals(1, SplitSide.RIGHT.position)
        assertEquals(SplitSide.RIGHT, SplitSide.LEFT.other)
    }

    // ---- shell commands

    @Test fun `HyperOS help picks the SoSc family, AOSP the side stage`() {
        val hyper = "      SoScSplitScreen\n        moveToSoScSplitStage <taskId> <position>\n      splitscreen\n        moveToSideStage <taskId> <SideStagePosition>"
        assertEquals(SplitShellCommands.Family.HYPEROS_SOSC, SplitShellCommands.family(hyper))
        assertEquals(SplitShellCommands.Family.AOSP, SplitShellCommands.family("splitscreen\n moveToSideStage <taskId> <SideStagePosition>"))
        assertNull(SplitShellCommands.family("no split here"))
    }

    @Test fun `commands carry only the validated task id and position`() {
        val aosp = SplitShellCommands.moves(SplitShellCommands.Family.AOSP, 536, SplitSide.RIGHT)
        assertEquals(listOf("dumpsys activity service SystemUIService WMShell splitscreen moveToSideStage 536 1"), aosp)
        val hyper = SplitShellCommands.moves(SplitShellCommands.Family.HYPEROS_SOSC, 880, SplitSide.LEFT)
        assertEquals("dumpsys activity service SystemUIService WMShell SoScSplitScreen moveToSoScSplitStage 880 0", hyper.first())
        assertEquals(3, hyper.size)
        assertTrue(hyper.last().endsWith("splitscreen moveToSideStage 880 0"))
    }

    @Test(expected = IllegalArgumentException::class) fun `no command for an invalid task`() {
        SplitShellCommands.moves(SplitShellCommands.Family.AOSP, 0, SplitSide.LEFT)
    }

    private val stackList = """
          taskId=538: com.android.contacts/com.android.contacts.activities.PeopleActivity bounds=[0,0][2364,1672] userId=0 visible=false topActivity=ComponentInfo{x/y}
          taskId=536: com.android.settings/com.android.settings.Settings bounds=[1196,0][2364,1672] userId=0 visible=true topActivity=ComponentInfo{com.android.settings/com.android.settings.Settings}
          taskId=537: com.android.deskclock/com.android.deskclock.DeskClock bounds=[0,0][1168,1672] userId=0 visible=true topActivity=ComponentInfo{a/b}
          taskId=600: com.android.settings/com.android.settings.SubSettings bounds=[0,0][2364,1672] userId=10 visible=false topActivity=ComponentInfo{a/b}
    """.trimIndent()

    @Test fun `stack list lines parse into tasks with bounds`() {
        val tasks = SplitShellCommands.parseTasks(stackList)
        assertEquals(listOf(538, 536, 537, 600), tasks.map { it.id })
        assertEquals(1196, tasks[1].left)
        assertTrue(tasks[1].visible)
    }

    @Test fun `the app's task is found per profile, visible first`() {
        val tasks = SplitShellCommands.parseTasks(stackList)
        assertEquals(536, SplitShellCommands.taskOf(tasks, "com.android.settings", 0)?.id)
        assertEquals(600, SplitShellCommands.taskOf(tasks, "com.android.settings", 10)?.id)
        assertNull(SplitShellCommands.taskOf(tasks, "com.example", 0))
    }

    @Test fun `a half-width task tells its side, a full one none`() {
        val tasks = SplitShellCommands.parseTasks(stackList)
        assertEquals(SplitSide.RIGHT, SplitShellCommands.sideOf(tasks[1], 2364))
        assertEquals(SplitSide.LEFT, SplitShellCommands.sideOf(tasks[2], 2364))
        assertNull(SplitShellCommands.sideOf(tasks[0], 2364))
    }
}
