package com.mainnetwallet.polybot.ui

import com.mainnetwallet.polybot.ui.overview.tapSideEnabled
import com.mainnetwallet.polybot.ui.overview.tapSideSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TapSideSelectionTest {
    @Test fun selectingUpThenDownSwitchesTheSide() {
        var selected: String? = null
        selected = tapSideSelection(selected, "UP")
        assertEquals("UP", selected)
        selected = tapSideSelection(selected, "DOWN")
        assertEquals("DOWN", selected)
    }

    @Test fun tappingTheSelectedSideDeselectsIt() {
        assertNull(tapSideSelection("UP", "UP"))
        assertNull(tapSideSelection("DOWN", "DOWN"))
    }

    @Test fun onlyTheSelectedSideCanBuy() {
        assertTrue(tapSideEnabled("UP", "UP"))
        assertFalse(tapSideEnabled("UP", "DOWN"))
        assertTrue(tapSideEnabled("DOWN", "DOWN"))
        assertFalse(tapSideEnabled("DOWN", "UP"))
    }

    @Test fun nothingSelectedLocksEveryBuyButton() {
        assertFalse(tapSideEnabled(null, "UP"))
        assertFalse(tapSideEnabled(null, "DOWN"))
    }
}
