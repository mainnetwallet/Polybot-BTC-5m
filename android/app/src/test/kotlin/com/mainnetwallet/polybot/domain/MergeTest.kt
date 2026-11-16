package com.mainnetwallet.polybot.domain

import com.mainnetwallet.polybot.Fixtures
import com.mainnetwallet.polybot.core.AppJson
import com.mainnetwallet.polybot.data.model.Books
import com.mainnetwallet.polybot.data.model.Snapshot
import com.mainnetwallet.polybot.data.model.StrategyTick
import com.mainnetwallet.polybot.data.model.Tick
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MergeTest {
    private val snapshot = AppJson.decodeFromString(Snapshot.serializer(), Fixtures.text("state_market.json"))
    private val tick = AppJson.decodeFromJsonElement(
        Tick.serializer(),
        AppJson.parseToJsonElement(Fixtures.text("ws_tick.json")).jsonObject.getValue("data"),
    )

    @Test fun booksAreReplacedByTheTick() {
        val merged = snapshot.mergeTick(tick)
        assertEquals(tick.books, merged.books)
    }

    @Test fun countdownFollowsTheTick() {
        val merged = snapshot.mergeTick(tick)
        assertEquals(71.5, merged.market!!.remainingSeconds, 1e-9)
        // everything else about the market stays
        assertEquals(snapshot.market!!.marketId, merged.market!!.marketId)
        assertEquals(snapshot.market!!.windowEnd, merged.market!!.windowEnd)
    }

    @Test fun evaluationFieldsAreOverlaidButSettingsStay() {
        val live = tick.copy(
            strategy = StrategyTick(
                inWindow = false, remainingSeconds = 12.5, upAsk = 0.5, downAsk = 0.6,
                upArmed = false, downArmed = true, reason = "down_armed",
            ),
        )
        val merged = snapshot.mergeTick(live)
        assertEquals(false, merged.strategy.inWindow)
        assertEquals(12.5, merged.strategy.remainingSeconds!!, 1e-9)
        assertEquals(0.6, merged.strategy.downAsk!!, 1e-9)
        assertEquals(true, merged.strategy.downArmed)
        assertEquals("down_armed", merged.strategy.reason)
        // not part of the tick: untouched
        assertEquals(snapshot.strategy.name, merged.strategy.name)
        assertEquals(snapshot.strategy.stage1Threshold, merged.strategy.stage1Threshold, 0.0)
        assertEquals(snapshot.strategy.stage0MinAsk, merged.strategy.stage0MinAsk, 0.0)
    }

    @Test fun accountAndPositionsAreUntouched() {
        val merged = snapshot.mergeTick(tick)
        assertEquals(snapshot.account, merged.account)
        assertEquals(snapshot.positions, merged.positions)
        assertEquals(snapshot.bot, merged.bot)
    }

    @Test fun tickWithoutStrategyKeepsTheStrategy() {
        val merged = snapshot.mergeTick(tick.copy(strategy = null))
        assertSame(snapshot.strategy, merged.strategy)
    }

    @Test fun tickWithoutRemainingSecondsKeepsTheMarketClock() {
        val merged = snapshot.mergeTick(tick.copy(remainingSeconds = null))
        assertEquals(snapshot.market!!.remainingSeconds, merged.market!!.remainingSeconds, 0.0)
    }

    @Test fun tickWithoutAMarketDoesNotInventOne() {
        val idle = AppJson.decodeFromString(Snapshot.serializer(), Fixtures.text("state_idle.json"))
        val merged = idle.mergeTick(tick.copy(books = Books()))
        assertNull(merged.market)
    }
}
