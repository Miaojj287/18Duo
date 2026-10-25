package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test

/**
 * Round 4 (owner item 8, review r3 "cover landscape Dock"): in every side-column posture the Dock glass is
 * vertically centred on the panel and never overlaps the status block or Search, with or without the
 * two recent apps and with the real Xiaomi 18 Fold cutouts in both rotations.
 */
class DockFitTest {
    private val density = 2.75f
    private fun px(left: Int, top: Int, right: Int, bottom: Int) =
        DuoBox(left / density, top / density, right / density, bottom / density)

    private data class Posture(val name: String, val pose: DuoHomePose, val w: Float, val h: Float, val cutouts: List<DuoBox>)

    private val postures = listOf(
        Posture("cover portrait", DuoHomePose.COVER_PORTRAIT, 1168 / density, 1712 / density, listOf(px(557, 0, 611, 126))),
        Posture("cover landscape, camera left", DuoHomePose.COVER_LANDSCAPE, 1712 / density, 1168 / density,
            listOf(px(0, 557, 126, 611))),
        Posture("cover landscape, camera right", DuoHomePose.COVER_LANDSCAPE, 1712 / density, 1168 / density,
            listOf(px(1712 - 126, 557, 1712, 611))),
        Posture("cover landscape, emulator", DuoHomePose.COVER_LANDSCAPE, 1712 / density, 1168 / density, emptyList()),
        Posture("inner landscape", DuoHomePose.INNER_LANDSCAPE, 2364 / density, 1672 / density, listOf(px(2224, 0, 2364, 164))),
        Posture("inner landscape, emulator", DuoHomePose.INNER_LANDSCAPE, 2364 / density, 1672 / density, emptyList()),
    )

    private fun frame(p: Posture, recents: Int) =
        duoHomeFrame(p.pose, p.w, p.h, dockExtraSlots = recents).avoidingCutouts(p.cutouts)

    @Test fun `the side Dock is centred and clear of the status block and Search in every posture`() {
        for (p in postures) for (recents in listOf(0, 2)) {
            val f = frame(p, recents)
            val d = f.dock
            val label = "${p.name}, recents $recents"
            assertEquals(label, p.h / 2f, d.box.centerY, .01f)
            assertTrue("$label: below the status block", d.box.top >= f.status.bounds.bottom + 16f - .01f)
            assertTrue("$label: above Search", d.box.bottom <= f.search.top - 16f + .01f)
            p.cutouts.forEach { assertFalse("$label: off the camera", d.box.intersects(it)) }
            // Apple's proportions survive any fitting: inset, pitch and concentric corner scale together.
            val apple = duoHomeFrame(p.pose, p.w, p.h).sideDock!!
            val s = d.icon / apple.icon
            assertTrue("$label: scale $s", s in DOCK_MIN_FIT_SCALE..1f)
            assertEquals(label, apple.pitch * s, d.pitch, .001f)
            assertEquals(label, apple.width * s, d.box.width, .001f)
            assertEquals(label, d.icon * IOS_ICON_CORNER_FRACTION + d.padding, d.cornerRadius, .001f)
        }
    }

    @Test fun `apple size is kept wherever it fits`() {
        for (p in postures.filter { it.pose != DuoHomePose.COVER_LANDSCAPE }) {
            val f = frame(p, 0)
            assertEquals(p.name, f.sideDock!!.icon, f.dock.icon, 0f)
            assertEquals(p.name, 4, f.dock.slots)
        }
        // The emulator's inner panel (no camera) holds both recent apps at full size.
        val inner = frame(postures.first { it.name == "inner landscape, emulator" }, 2)
        assertEquals(6, inner.dock.slots)
        assertEquals(inner.sideDock!!.icon, inner.dock.icon, 0f)
    }

    @Test fun `the rotated cover lets recent apps overflow and fits the four apps`() {
        for (p in postures.filter { it.pose == DuoHomePose.COVER_LANDSCAPE }) {
            val f = frame(p, 2)
            assertEquals(p.name, 4, f.dock.slots)
            assertEquals(p.name, 0f, f.dock.extraGap, 0f)
            assertEquals(p.name, frame(p, 0).dock, f.dock)
            // ≈0.88 × Apple's Dock (39 dp icons): the 425 dp column has 213 dp between status and Search
            // around the centre.
            assertEquals(p.name, .88f, f.dock.icon / f.sideDock!!.icon, .01f)
        }
        // Round 3 drew it 372 dp tall on the 425 dp panel, over the time, ring and Search.
        val fitted = frame(postures.first { it.name == "cover landscape, emulator" }, 2)
        assertTrue(fitted.dock.box.height < 425f / 2f)
    }

    @Test fun `the rotated cover keeps the cover's time to ring rhythm`() {
        val portrait = duoHomeFrame(DuoHomePose.COVER_PORTRAIT, 425f, 622f).status
        val landscape = duoHomeFrame(DuoHomePose.COVER_LANDSCAPE, 622f, 425f).status
        assertEquals(portrait.ringCenterY - portrait.timeCenterY, landscape.ringCenterY - landscape.timeCenterY, .01f)
        assertEquals("time level with the unfolded column", 40.1f, landscape.timeCenterY, .1f)
    }

    @Test fun `recent apps overflow before the Dock shrinks past the iPad-like limit`() {
        val spec = SideDockSpec(centerX = 0f, width = 65f, icon = 44f, pitch = 59f, recents = 2, recentGap = 13f, centerY = 300f)
        // Room for everything: Apple's size, both recents.
        val roomy = spec.fit(0f, 600f)
        assertEquals(6, roomy.slots); assertEquals(44f, roomy.icon, 0f)
        // A little short: both recents, scaled.
        val tight = spec.fit(300f - 170f, 300f + 170f)
        assertEquals(6, tight.slots); assertTrue(tight.icon < 44f && tight.icon >= 44f * DOCK_MIN_FIT_SCALE)
        assertEquals(340f, tight.box.height, .01f)
        // Too short for the recents at ≥ 0.85×: they overflow one by one, then the four apps fit.
        val short = spec.fit(300f - 110f, 300f + 110f)
        assertEquals(4, short.slots)
        assertEquals(220f, short.box.height, .01f)
        assertEquals(300f, short.box.centerY, .01f)
        // A window too short for even 0.6× clamps between the neighbours (centred in that gap).
        val tiny = spec.fit(250f, 330f)
        assertEquals(4, tiny.slots); assertEquals(44f * .6f, tiny.icon, .001f)
        assertEquals(290f, tiny.box.centerY, .01f)
    }

    @Test fun `legacy width preference is fitted too`() {
        val cover = duoHomeFrame(DuoHomePose.COVER_LANDSCAPE, 622f, 425f)
        val wide = cover.withDockPreference(LayoutPreset(dockWidth = 84f))
        assertEquals(cover.dock, wide.dock)
        val portrait = duoHomeFrame(DuoHomePose.COVER_PORTRAIT, 425f, 622f)
        val manual = portrait.withDockPreference(LayoutPreset(dockAlignToGrid = false, dockPosition = .6f)).dock
        assertEquals(.6f * 622f, manual.box.centerY, .01f)
    }
}
