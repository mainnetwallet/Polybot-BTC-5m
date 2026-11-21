package com.mainnetwallet.polybot.ui

import com.mainnetwallet.polybot.ui.overview.tapAskTooLow
import com.mainnetwallet.polybot.ui.overview.tapCentsText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapAskTooLowTest {
    @Test fun aSideUnder40CentsIsOff() {
        assertTrue(tapAskTooLow(0.01, 0.40)) // the 1 cent DOWN of a market that already went UP
        assertTrue(tapAskTooLow(0.10, 0.40))
        assertTrue(tapAskTooLow(0.39, 0.40))
    }

    @Test fun fortyCentsAndAboveAreFine() {
        assertFalse(tapAskTooLow(0.40, 0.40))
        assertFalse(tapAskTooLow(0.41, 0.40))
        assertFalse(tapAskTooLow(0.99, 0.40))
    }

    @Test fun theOwnersMinimumMovesTheLine() {
        assertFalse(tapAskTooLow(0.30, 0.25))
        assertTrue(tapAskTooLow(0.20, 0.25))
        assertFalse(tapAskTooLow(0.01, 0.0)) // 0 = no minimum
        assertTrue(tapAskTooLow(0.45, 0.50))
    }

    @Test fun theMinimumIsEditedInCents() {
        assertEquals("40", tapCentsText(0.40))
        assertEquals("37.5", tapCentsText(0.375))
        assertEquals("0", tapCentsText(0.0))
    }

    @Test fun noAskIsNotTheCheapRule() {
        // a missing / invalid ask is handled by its own check (the button is off because there is nothing to buy)
        assertFalse(tapAskTooLow(null, 0.40))
        assertFalse(tapAskTooLow(Double.NaN, 0.40))
    }
}
