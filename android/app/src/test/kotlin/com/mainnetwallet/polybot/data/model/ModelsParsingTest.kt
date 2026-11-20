package com.mainnetwallet.polybot.data.model

import com.mainnetwallet.polybot.Fixtures
import com.mainnetwallet.polybot.core.AppJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fixtures in src/test/resources/fixtures are real output of the backend (FastAPI TestClient). */
class ModelsParsingTest {
    private fun snapshot(name: String) = AppJson.decodeFromString(Snapshot.serializer(), Fixtures.text(name))

    @Test fun idleStateParses() {
        val s = snapshot("state_idle.json")
        assertEquals("STOPPED", s.bot.status)
        assertEquals(Modes.PAPER, s.bot.mode)
        assertFalse(s.bot.running)
        assertFalse(s.bot.isLive)
        assertNull(s.market)
        assertNull(s.books.up)
        assertNull(s.books.down)
        assertEquals("FINAL_60_SECONDS_MARKET_BUY", s.strategy.name)
        assertEquals("no_market", s.strategy.reason)
        assertEquals(10.0, s.account.balance, 0.0)
        assertTrue(s.positions.isEmpty())
        assertNull(s.lastOrder)
        assertNotNull(s.btc)
        assertTrue(s.btc!!.stale)
        assertNull(s.btc!!.price)
        assertFalse(s.live.ready)
    }

    @Test fun buyGuardParsesAndDefaultsToBuyingOn() {
        val idle = snapshot("state_idle.json")
        assertNull(idle.buyGuard) // an older backend sends no buy_guard: the app treats buying as on

        val guard = snapshot("state_market.json").buyGuard!!
        assertFalse(guard.buysEnabled)
        assertEquals("gap_guard", guard.offBy)
        assertEquals("BTC gap too large", guard.offLabel)
        assertEquals(500.0, guard.gapLimitUsd, 0.0)
        assertEquals(732.5, guard.gapNowUsd!!, 1e-9)
        assertEquals(1000.0, guard.trendLimitUsd, 0.0)
        assertEquals(3, guard.trendMarkets)
        assertNull(guard.trendNowUsd)
        assertTrue(guard.telegramEnabled)
        assertEquals(60, guard.resumeMinutes)
        assertEquals("2026-11-15T04:09:30.000000Z", guard.resumeAt)
        assertFalse(guard.isManualOff)
        // from 03:30:00Z the resume time 04:09:30Z is 39.5 min away, shown rounded up
        val now = java.time.Instant.parse("2026-11-15T03:30:00Z").toEpochMilli()
        assertEquals(40L, guard.minutesUntilResume(now))
        assertEquals(0L, guard.minutesUntilResume(java.time.Instant.parse("2026-11-15T05:00:00Z").toEpochMilli()))
        assertNull(BuyGuardState(buysEnabled = false, offBy = "manual").minutesUntilResume(now))
        assertTrue(BuyGuardState(buysEnabled = false, offBy = "manual").isManualOff)
        assertEquals("BTC trend too large", BuyGuardState(buysEnabled = false, offBy = "trend_guard").offLabel)
        assertEquals("Buying turned off", BuyGuardState(buysEnabled = false, offBy = "manual").offLabel)
    }

    @Test fun stateWithMarketAndBooksParses() {
        val s = snapshot("state_market.json")
        val market = s.market!!
        assertEquals("0xmarket", market.marketId)
        assertEquals(300.0, market.durationSeconds, 0.0)
        assertTrue(market.remainingSeconds in 60.0..80.0)
        assertEquals(0.86, s.books.up!!.bestAsk!!, 1e-9)
        assertEquals(0.84, s.books.up!!.bestBid!!, 1e-9)
        assertEquals(120.0, s.books.up!!.askSize!!, 1e-9)
        assertEquals("DOWN", s.books.down!!.side)
        assertTrue(s.strategy.inWindow)
        assertTrue(s.strategy.upArmed)
        assertFalse(s.strategy.downArmed)
        assertEquals(0.85, s.strategy.stage1Threshold, 1e-9)
        assertEquals(90.0, s.strategy.stage1Seconds, 1e-9)
        assertEquals(0.69, s.strategy.stage0MinAsk, 1e-9)
        assertEquals("one_per_market", s.strategy.duplicatePolicy)
    }

    @Test fun unknownFieldsAreIgnored() {
        // the real payload carries latency, ticks, ws_mode ... that the app does not model
        val s = snapshot("state_idle.json")
        assertEquals("paper", s.account.mode)
    }

    @Test fun nullsAndMissingFieldsFallBackToDefaults() {
        val s = AppJson.decodeFromString(
            Snapshot.serializer(),
            """{"bot":{"status":null,"mode":null},"account":{"balance":null},"strategy":{"reason":null},"extra":1}""",
        )
        assertEquals("STOPPED", s.bot.status)
        assertEquals(Modes.PAPER, s.bot.mode)
        assertEquals(0.0, s.account.balance, 0.0)
        assertEquals("", s.strategy.reason)
        assertNull(s.market)
        assertEquals(emptyList<PositionRow>(), s.positions)
    }

    @Test fun emptyObjectIsAValidSnapshot() {
        val s = AppJson.decodeFromString(Snapshot.serializer(), "{}")
        assertEquals("STOPPED", s.bot.status)
    }

    @Test fun webSocketStateFrameParses() {
        val frame = AppJson.parseToJsonElement(Fixtures.text("ws_state.json")).jsonObject
        assertEquals("state", frame["type"]!!.jsonPrimitive.content)
        val snap = AppJson.decodeFromJsonElement(Snapshot.serializer(), frame["data"]!!)
        assertEquals("STOPPED", snap.bot.status)
    }

    @Test fun tickFrameParses() {
        val frame = AppJson.parseToJsonElement(Fixtures.text("ws_tick.json")).jsonObject
        assertEquals("tick", frame["type"]!!.jsonPrimitive.content)
        val tick = AppJson.decodeFromJsonElement(Tick.serializer(), frame["data"]!!)
        assertEquals(1763175034252.0, tick.serverTimeMs, 0.0)
        assertEquals(71.5, tick.remainingSeconds!!, 1e-9)
        assertEquals(0.86, tick.books.up!!.bestAsk!!, 1e-9)
        assertEquals(0.13, tick.books.down!!.bestBid!!, 1e-9)
        assertTrue(tick.strategy!!.upArmed)
        assertEquals("trigger", tick.strategy!!.reason)
    }

    @Test fun tickWithoutStrategyOrMarketParses() {
        val tick = AppJson.decodeFromString(Tick.serializer(), """{"server_time_ms":5,"remaining_seconds":null,"books":{"up":null,"down":null},"strategy":null}""")
        assertNull(tick.strategy)
        assertNull(tick.remainingSeconds)
    }

    @Test fun logFrameParses() {
        val frame = AppJson.parseToJsonElement(Fixtures.text("ws_log.json")).jsonObject
        assertEquals("log", frame["type"]!!.jsonPrimitive.content)
        val row = AppJson.decodeFromJsonElement(LogRow.serializer(), frame["data"]!!)
        assertEquals(3L, row.id)
        assertEquals("WARNING", row.level)
        assertEquals("system", row.category)
        assertEquals("bot stopped (operator)", row.message)
        assertEquals("paper", row.mode)
        assertEquals("paper", (row.payload as JsonObject)["mode"]!!.jsonPrimitive.content)
    }

    @Test fun logListParsesAndKeepsOrder() {
        val rows = AppJson.decodeFromString(ListSerializer(LogRow.serializer()), Fixtures.text("logs.json"))
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.message.isNotBlank() && it.createdAt.isNotBlank() })
    }

    @Test fun logWithNullPayloadAndLevelUsesDefaults() {
        val row = AppJson.decodeFromString(LogRow.serializer(), """{"id":9,"level":null,"message":"x","payload":null}""")
        assertEquals("INFO", row.level)
        assertNull(row.payload)
    }

    @Test fun analyticsWithoutTradesHasNoWinRate() {
        val a = AppJson.decodeFromString(Analytics.serializer(), Fixtures.text("analytics.json"))
        assertEquals(0, a.totalTrades)
        assertNull(a.winRate)
        assertNull(a.avgEntryPrice)
        assertEquals(0.0, a.realizedPnl, 0.0) // the backend sends the integer 0 here
    }

    @Test fun healthParses() {
        val h = AppJson.decodeFromString(HealthInfo.serializer(), Fixtures.text("health.json"))
        assertEquals("ok", h.status)
        assertEquals("STOPPED", h.botStatus)
        assertEquals("paper", h.mode)
    }

    @Test fun orderRowParsesWithNullableFields() {
        val order = AppJson.decodeFromString(
            OrderRow.serializer(),
            """{"id":4,"market_id":"0xm","side":"UP","stage":1,"action":"BUY","status":"FILLED","requested_notional":5,"requested_price":0.86,"submitted_price":null,"filled_size":5.8,"avg_fill_price":0.862,"fee":0.01,"latency_ms":null,"exchange_order_id":null,"error":null,"reason":"stage1","result":"OPEN","result_pnl":null,"created_at":"2026-11-15T03:10:34.248019Z"}""",
        )
        assertEquals(1, order.stage)
        assertEquals(5.0, order.requestedNotional, 0.0)
        assertNull(order.submittedPrice)
        assertEquals("OPEN", order.result)
        assertNull(order.route) // paper / older orders carry no route
    }

    @Test fun orderRowParsesRoute() {
        val order = AppJson.decodeFromString(
            OrderRow.serializer(),
            """{"id":5,"market_id":"0xm","side":"UP","status":"FILLED","route":"proxy"}""",
        )
        assertEquals("proxy", order.route)
    }
}
