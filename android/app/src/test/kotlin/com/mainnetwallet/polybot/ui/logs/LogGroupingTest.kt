package com.mainnetwallet.polybot.ui.logs

import com.mainnetwallet.polybot.data.model.LogRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class LogGroupingTest {
    private val utc = ZoneId.of("UTC")
    private val now = Instant.parse("2026-11-15T12:00:00Z").toEpochMilli()

    private fun row(at: String, message: String) = LogRow(message = message, createdAt = at)

    private fun shape(items: List<LogListItem>): List<String> = items.map {
        when (it) {
            is LogListItem.DayHeader -> "# ${it.label} (${it.count})"
            is LogListItem.Entry -> it.row.message
        }
    }

    @Test
    fun putsOneHeadingInFrontOfEachDay() {
        val rows = listOf(
            row("2026-11-15T10:00:00Z", "a"),
            row("2026-11-15T01:00:00Z", "b"),
            row("2026-11-14T23:00:00Z", "c"),
            row("2026-11-10T08:00:00Z", "d"),
            row("2025-12-31T08:00:00Z", "e"),
        )
        assertEquals(
            listOf(
                "# Today (2)", "a", "b",
                "# Yesterday (1)", "c",
                "# Nov 10 (1)", "d",
                "# Dec 31, 2025 (1)", "e",
            ),
            shape(LogGrouping.group(rows, now, utc)),
        )
    }

    @Test
    fun anUnreadableTimeGetsItsOwnHeadingAndKeepsItsPlace() {
        val rows = listOf(
            row("2026-11-15T10:00:00Z", "a"),
            row("not a time", "b"),
            row("2026-11-15T09:00:00Z", "c"),
        )
        assertEquals(
            listOf("# Today (1)", "a", "# Unknown date (1)", "b", "# Today (1)", "c"),
            shape(LogGrouping.group(rows, now, utc)),
        )
    }

    @Test
    fun usesTheGivenTimeZoneForTheDayBoundary() {
        val rows = listOf(row("2026-11-14T20:00:00Z", "a"))
        val dhaka = ZoneId.of("Asia/Dhaka") // UTC+6, so 20:00Z is already the 15th there
        assertEquals(listOf("# Today (1)", "a"), shape(LogGrouping.group(rows, now, dhaka)))
        assertEquals(listOf("# Yesterday (1)", "a"), shape(LogGrouping.group(rows, now, utc)))
    }

    @Test
    fun noRowsGiveNoItems() {
        assertEquals(emptyList<LogListItem>(), LogGrouping.group(emptyList(), now, utc))
    }

    @Test
    fun labelNamesTodayYesterdayAndOlderDays() {
        val today = LocalDate.of(2026, 11, 15)
        assertEquals("Today", LogGrouping.label(today, today))
        assertEquals("Yesterday", LogGrouping.label(today.minusDays(1), today))
        assertEquals("Mar 3", LogGrouping.label(LocalDate.of(2026, 3, 3), today))
        assertEquals("Mar 3, 2025", LogGrouping.label(LocalDate.of(2025, 3, 3), today))
        assertEquals("Unknown date", LogGrouping.label(null, today))
    }
}
