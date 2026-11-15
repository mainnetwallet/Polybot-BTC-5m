package com.mainnetwallet.polybot.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun signedPercentCarriesAnExplicitSign() {
        assertEquals("+2.4%", Fmt.signedPercent(2.4))
        assertEquals("-0.8%", Fmt.signedPercent(-0.8))
        assertEquals("0.0%", Fmt.signedPercent(0.0))
    }

    @Test
    fun signedPercentHandlesMissingAndNonFiniteValues() {
        assertEquals("—", Fmt.signedPercent(null))
        assertEquals("—", Fmt.signedPercent(Double.NaN))
        assertEquals("—", Fmt.signedPercent(Double.POSITIVE_INFINITY))
    }

    @Test
    fun signedPercentRespectsDigits() {
        assertEquals("+12.35%", Fmt.signedPercent(12.346, 2))
    }

    @Test
    fun centsShortDropsTrailingZerosAndKeepsHalfCents() {
        assertEquals("60¢", Fmt.centsShort(0.6))
        assertEquals("7¢", Fmt.centsShort(0.07))
        assertEquals("87.5¢", Fmt.centsShort(0.875))
        assertEquals("99¢", Fmt.centsShort(0.99))
        assertEquals("0¢", Fmt.centsShort(0.0))
    }

    @Test
    fun centsShortHandlesMissingAndNonFiniteValues() {
        assertEquals("—", Fmt.centsShort(null))
        assertEquals("—", Fmt.centsShort(Double.NaN))
    }
}
