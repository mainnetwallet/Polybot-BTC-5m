package com.mainnetwallet.polybot.data.store

import org.junit.Assert.assertEquals
import org.junit.Test

class TapAmountsTest {
    @Test fun threeAmountsAreRead() {
        assertEquals(listOf(1.0, 2.5, 10.0), parseTapAmounts("1,2.5, 10"))
    }

    @Test fun anythingElseGivesTheDefaults() {
        assertEquals(DEFAULT_TAP_AMOUNTS, parseTapAmounts(null))
        assertEquals(DEFAULT_TAP_AMOUNTS, parseTapAmounts(""))
        assertEquals(DEFAULT_TAP_AMOUNTS, parseTapAmounts("1,2"))
        assertEquals(DEFAULT_TAP_AMOUNTS, parseTapAmounts("1,2,0.5")) // below the $1 minimum
        assertEquals(DEFAULT_TAP_AMOUNTS, parseTapAmounts("a,b,c"))
    }
}
