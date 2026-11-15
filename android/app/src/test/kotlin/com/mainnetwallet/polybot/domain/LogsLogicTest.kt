package com.mainnetwallet.polybot.domain

import com.mainnetwallet.polybot.data.model.LogRow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogsLogicTest {
    private fun row(
        level: String,
        category: String = "",
        message: String = "",
        orderId: Int? = null,
    ) = LogRow(
        level = level,
        category = category,
        message = message,
        payload = orderId?.let { buildJsonObject { put("order_id", it) } },
    )

    private val rows = listOf(
        row("INFO", "engine", "Engine started"),
        row("WARNING", "trading", "Order rejected", orderId = 42),
        row("ERROR", "trading", "Sell failed: timeout"),
        row("CRITICAL", "engine", "Kill switch tripped"),
        row("DEBUG", "feed", "tick"),
    )

    @Test
    fun filtersByLevelAndCategoryTogether() {
        val out = LogsLogic.filter(rows, LevelFilter.Error, "trading", "")
        assertEquals(listOf("Sell failed: timeout"), out.map { it.message })
    }

    @Test
    fun levelAllKeepsEveryLevelAndTheOriginalOrder() {
        val out = LogsLogic.filter(rows, LevelFilter.All, null, "")
        assertEquals(rows, out)
    }

    @Test
    fun searchIgnoresCaseAndSurroundingSpaces() {
        val out = LogsLogic.filter(rows, LevelFilter.All, null, "  KILL switch ")
        assertEquals(listOf("Kill switch tripped"), out.map { it.message })
    }

    @Test
    fun searchLooksAtTheCategoryAndThePayload() {
        assertEquals(2, LogsLogic.filter(rows, LevelFilter.All, null, "trading").size)
        assertEquals(listOf("Order rejected"), LogsLogic.filter(rows, LevelFilter.All, null, "order_id").map { it.message })
    }

    @Test
    fun blankSearchMatchesEverything() {
        assertTrue(LogsLogic.matchesQuery(rows[0], ""))
        assertTrue(LogsLogic.matchesQuery(rows[0], "   "))
    }

    @Test
    fun noMatchGivesAnEmptyList() {
        assertTrue(LogsLogic.filter(rows, LevelFilter.All, null, "zzz").isEmpty())
    }

    @Test
    fun countsGroupWarningAndCriticalWithTheirFilters() {
        val c = LogsLogic.counts(rows)
        assertEquals(5, c.all)
        assertEquals(1, c.info)
        assertEquals(1, c.warn)
        assertEquals(2, c.error)
        assertEquals(c.error, c.of(LevelFilter.Error))
        assertEquals(c.all, c.of(LevelFilter.All))
    }

    @Test
    fun countsOfNothingAreZero() {
        val c = LogsLogic.counts(emptyList())
        assertEquals(0, c.all + c.info + c.warn + c.error)
    }

    @Test
    fun categoriesAreMostFrequentFirstThenAlphabetical() {
        assertEquals(listOf("engine", "trading", "feed"), LogsLogic.categories(rows))
        assertTrue(LogsLogic.categories(listOf(row("INFO", ""))).isEmpty())
    }

    @Test
    fun entryTextHasLevelCategoryTimeMessageAndPayload() {
        val r = LogRow(level = "WARNING", category = "trading", message = "Order rejected", createdAt = "2026-11-15T10:00:00Z")
        assertEquals(
            "[WARNING] trading  2026-11-15T10:00:00Z\nOrder rejected\n\n{\n  \"order_id\": 42\n}",
            LogsLogic.entryText(r, "{\n  \"order_id\": 42\n}"),
        )
        assertEquals("[INFO]\nhello", LogsLogic.entryText(LogRow(level = "", message = "hello"), null))
    }

    @Test fun onlyListChangingLinesMakeScreensRefetch() {
        assertTrue(row("INFO", "trigger", "UP ask 0.350 -> submitted").changesLists())
        assertTrue(row("INFO", "fill", "Filled 5 UP").changesLists())
        assertTrue(row("INFO", "order_submit", "BUY UP").changesLists())
        assertTrue(row("INFO", "engine", "Position sold at 0.62").changesLists())
        assertTrue(row("WARNING", "engine", "STOP-LOSS hit").changesLists())
        assertTrue(!row("INFO", "engine", "Engine started").changesLists())
        assertTrue(!row("INFO", "market", "New market tracked").changesLists())
        assertTrue(!row("ERROR", "error", "exit worker failed: boom").changesLists())
    }
}
