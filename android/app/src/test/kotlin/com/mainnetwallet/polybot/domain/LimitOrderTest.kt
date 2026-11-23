package com.mainnetwallet.polybot.domain

import com.mainnetwallet.polybot.data.model.BuyLimitRow
import com.mainnetwallet.polybot.data.model.SellableHolding
import com.mainnetwallet.polybot.data.model.TAP_STAGE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitOrderTest {
    @Test fun parseCentsAcceptsOneToNinetyNineAndDecimals() {
        assertEquals(45.0, LimitOrder.parseCents("45")!!, 0.0)
        assertEquals(85.5, LimitOrder.parseCents(" 85,5 ")!!, 0.0)
        assertEquals(1.0, LimitOrder.parseCents("1")!!, 0.0)
        assertEquals(99.0, LimitOrder.parseCents("99")!!, 0.0)
        assertNull(LimitOrder.parseCents(""))
        assertNull(LimitOrder.parseCents("abc"))
        assertNull(LimitOrder.parseCents("0"))
        assertNull(LimitOrder.parseCents("100"))
        assertNull(LimitOrder.parseCents("NaN"))
    }

    @Test fun parseAmountStaysInsideTheTapLimits() {
        assertEquals(5.0, LimitOrder.parseAmount("5", 1.0, 100.0)!!, 0.0)
        assertEquals(2.5, LimitOrder.parseAmount("2,5", 1.0, 100.0)!!, 0.0)
        assertNull(LimitOrder.parseAmount("0.5", 1.0, 100.0))
        assertNull(LimitOrder.parseAmount("101", 1.0, 100.0))
        assertNull(LimitOrder.parseAmount("", 1.0, 100.0))
    }

    @Test fun centsTextDropsTrailingZeros() {
        assertEquals("80", LimitOrder.centsText(0.8))
        assertEquals("85.5", LimitOrder.centsText(0.855))
        assertEquals("1", LimitOrder.centsText(0.01))
    }

    @Test fun clampKeepsWholeCentsInsideTheSliderRange() {
        assertEquals(1, LimitOrder.clampCents(-5.0))
        assertEquals(99, LimitOrder.clampCents(140.0))
        assertEquals(46, LimitOrder.clampCents(45.6))
    }

    @Test fun buyMathMatchesTheDashboard() {
        // $5 at 45 cents = 11.11 shares, 6.11 profit if the side wins
        assertEquals(11.111, LimitOrder.buyShares(5.0, 45.0), 0.001)
        assertEquals(6.111, LimitOrder.buyProfit(5.0, 45.0), 0.001)
    }

    @Test fun sellMathUsesTheCostOfTheShares() {
        assertEquals(8.325, LimitOrder.sellProceeds(11.1, 75.0), 0.001)
        assertEquals(3.33, LimitOrder.sellProfit(11.1, 0.45, 75.0)!!, 0.01)
        assertNull(LimitOrder.sellProfit(null, 0.45, 75.0)) // nothing held yet
        assertNull(LimitOrder.sellProfit(11.1, null, 75.0)) // cost unknown
    }

    @Test fun limitsFireWhenTheMarketIsAlreadyThere() {
        assertTrue(LimitOrder.buyFiresNow(0.44, 45.0))
        assertTrue(LimitOrder.buyFiresNow(0.45, 45.0))
        assertFalse(LimitOrder.buyFiresNow(0.48, 45.0))
        assertFalse(LimitOrder.buyFiresNow(null, 45.0))
        assertTrue(LimitOrder.sellFiresNow(0.80, 75.0))
        assertTrue(LimitOrder.sellFiresNow(0.75, 75.0))
        assertFalse(LimitOrder.sellFiresNow(0.52, 75.0))
        assertFalse(LimitOrder.sellFiresNow(null, 75.0))
    }

    @Test fun sellTargetsListHeldPositionsThenWaitingOneTapBuys() {
        val sellable = listOf(
            SellableHolding(marketId = "m1", side = "UP", stage = TAP_STAGE, size = 11.1, entryPrice = 0.45),
            SellableHolding(marketId = "m1", side = "UP", stage = 0, size = 8.0, entryPrice = 0.38),
            SellableHolding(marketId = "m1", side = "DOWN", stage = 2, size = 0.0, entryPrice = 0.9), // nothing held
            SellableHolding(marketId = "old", side = "DOWN", stage = 1, size = 3.0, entryPrice = 0.5), // another market
        )
        val buys = listOf(
            BuyLimitRow(marketId = "m1", side = "UP", price = 0.4, amount = 5.0), // its one-tap shares are held already
            BuyLimitRow(marketId = "m1", side = "DOWN", price = 0.4, amount = 5.0), // waiting: listed with no size
            BuyLimitRow(marketId = "old", side = "UP", price = 0.4, amount = 5.0),
        )
        val targets = LimitOrder.sellTargets("m1", sellable, buys)
        // strategy stages first, one-tap last (DOWN before UP inside the same stage); a waiting one-tap buy has no size yet
        assertEquals(listOf("UP:0", "DOWN:$TAP_STAGE", "UP:$TAP_STAGE"), targets.map { it.key })
        assertNull(targets.first { it.side == "DOWN" }.size)
        assertEquals(11.1, targets.first { it.side == "UP" && it.stage == TAP_STAGE }.size!!, 0.0)
        assertTrue(LimitOrder.sellTargets(null, sellable, buys).isEmpty())
    }
}
