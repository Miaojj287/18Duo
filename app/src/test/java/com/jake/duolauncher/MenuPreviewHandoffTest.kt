package com.jake.duolauncher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuPreviewHandoffTest {
    @Test fun aLiveMenuDrawsItsLiftedItem() {
        val handoff = MenuPreviewHandoff()
        assertFalse(handoff.hidden("item-a", live = true, dragging = false))
    }

    @Test fun dragAwayHidesThePreviewForTheWholeExitEvenAfterTheDropClearsTheDrag() {
        val handoff = MenuPreviewHandoff()
        handoff.hidden("item-a", live = true, dragging = false)
        // The finger drags the item out of the menu, which is dismissed and springs out.
        assertTrue(handoff.hidden("item-a", live = true, dragging = true))
        assertTrue(handoff.hidden("item-a", live = false, dragging = true))
        // A quick drop: the drag is over while the retained menu is still leaving.
        assertTrue(handoff.hidden("item-a", live = false, dragging = false))
        assertTrue(handoff.hidden("item-a", live = false, dragging = false))
    }

    @Test fun aMenuDismissedWithoutDraggingSinksItsItemBackIntoTheSlot() {
        val handoff = MenuPreviewHandoff()
        handoff.hidden("item-a", live = true, dragging = false)
        assertFalse(handoff.hidden("item-a", live = false, dragging = false))
    }

    @Test fun liftingTheSameItemAgainGivesThePreviewBack() {
        val handoff = MenuPreviewHandoff()
        handoff.hidden("item-a", live = true, dragging = true)
        handoff.hidden("item-a", live = false, dragging = false)
        assertFalse(handoff.hidden("item-a", live = true, dragging = false))
    }

    @Test fun anotherMenuIsNotHiddenByAnEarlierHandOff() {
        val handoff = MenuPreviewHandoff()
        handoff.hidden("item-a", live = false, dragging = true)
        assertFalse(handoff.hidden("item-b", live = false, dragging = false))
    }
}
