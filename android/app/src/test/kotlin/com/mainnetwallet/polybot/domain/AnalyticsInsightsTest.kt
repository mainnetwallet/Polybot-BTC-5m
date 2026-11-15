package com.mainnetwallet.polybot.domain

import com.mainnetwallet.polybot.data.model.Analytics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnalyticsInsightsTest {
    @Test
    fun derivesTheNumbersTheScreenShows() {
        val a = Analytics(
            totalTrades = 10,
            closedTrades = 8,
            wins = 5,
            losses = 3,
            winRate = 62.5,
            realizedPnl = 4.0,
            feesPaid = 0.5,
            volumeUsdc = 100.0,
            avgEntryPrice = 0.58,
        )
        val i = AnalyticsInsights.of(a)
        assertEquals(8, i.decided)
        assertEquals(2, i.openTrades)
        assertEquals(0.5, i.avgPnlPerSettled!!, 1e-9)
        assertEquals(10.0, i.avgTradeSize!!, 1e-9)
        assertEquals(0.5, i.feePercentOfVolume!!, 1e-9)
        assertEquals(58.0, i.breakEvenWinRate!!, 1e-9)
        assertEquals(4.5, i.edgePoints!!, 1e-9)
    }

    @Test
    fun anEmptyLedgerHasNoRatios() {
        val i = AnalyticsInsights.of(Analytics())
        assertEquals(0, i.decided)
        assertEquals(0, i.openTrades)
        assertNull(i.avgPnlPerSettled)
        assertNull(i.avgTradeSize)
        assertNull(i.feePercentOfVolume)
        assertNull(i.breakEvenWinRate)
        assertNull(i.edgePoints)
    }

    @Test
    fun openTradesNeverGoNegative() {
        val i = AnalyticsInsights.of(Analytics(totalTrades = 1, closedTrades = 3))
        assertEquals(0, i.openTrades)
    }

    @Test
    fun anEntryPriceOutsideZeroToOneGivesNoBreakEven() {
        listOf(0.0, -0.2, 1.5, Double.NaN).forEach { price ->
            val i = AnalyticsInsights.of(Analytics(winRate = 60.0, avgEntryPrice = price))
            assertNull("price $price", i.breakEvenWinRate)
            assertNull("price $price", i.edgePoints)
        }
    }

    @Test
    fun edgeNeedsAWinRate() {
        val i = AnalyticsInsights.of(Analytics(winRate = null, avgEntryPrice = 0.6))
        assertEquals(60.0, i.breakEvenWinRate!!, 1e-9)
        assertNull(i.edgePoints)
    }

    @Test
    fun aLosingEdgeIsNegative() {
        val i = AnalyticsInsights.of(Analytics(winRate = 50.0, avgEntryPrice = 0.55))
        assertEquals(-5.0, i.edgePoints!!, 1e-9)
    }
}
