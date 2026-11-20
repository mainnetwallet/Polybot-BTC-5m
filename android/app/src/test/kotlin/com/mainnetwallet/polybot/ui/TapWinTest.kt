package com.mainnetwallet.polybot.ui

import com.mainnetwallet.polybot.ui.overview.tapWin
import org.junit.Assert.assertEquals
import org.junit.Test

class TapWinTest {
    @Test fun winIsTheAmountOverTheAsk() {
        assertEquals("win $100", tapWin(1.0, 0.01))
        assertEquals("win $200", tapWin(2.0, 0.01))
        assertEquals("win $10.00", tapWin(5.0, 0.5))
    }

    @Test fun noAskMeansNoWin() {
        assertEquals("win \u2014", tapWin(1.0, null))
        assertEquals("win \u2014", tapWin(1.0, 0.0))
    }
}
