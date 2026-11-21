package com.mainnetwallet.polybot.data.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TapMinAskTest {
    @Test fun readsAPriceFromZeroTo099() {
        assertEquals(0.4, parseTapMinAsk("0.4")!!, 0.0)
        assertEquals(0.0, parseTapMinAsk("0.0")!!, 0.0) // 0 is a real choice: no minimum
        assertEquals(0.99, parseTapMinAsk(" 0.99 ")!!, 0.0)
    }

    @Test fun nothingSavedOrNonsenseMeansTheServerDefault() {
        assertNull(parseTapMinAsk(null))
        assertNull(parseTapMinAsk(""))
        assertNull(parseTapMinAsk("abc"))
        assertNull(parseTapMinAsk("-0.1"))
        assertNull(parseTapMinAsk("1.5"))
        assertNull(parseTapMinAsk("NaN"))
    }
}
