package com.mainnetwallet.polybot.ui.logs

import com.mainnetwallet.polybot.data.model.LogRow
import com.mainnetwallet.polybot.ui.Fmt
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One line of the log list: a day heading or an entry. */
sealed interface LogListItem {
    data class DayHeader(val label: String, val count: Int) : LogListItem
    data class Entry(val row: LogRow) : LogListItem
}

/** Puts a heading ("Today", "Yesterday", "Nov 10") in front of each run of entries from the same local day. */
object LogGrouping {
    private val monthDay = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val monthDayYear = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

    /**
     * [rows] are newest first and keep their order. Only neighbours with the same day share a heading, so an entry
     * with an unreadable time never reorders anything. [nowMs] and [zone] are parameters so tests can pin them.
     */
    fun group(rows: List<LogRow>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<LogListItem> {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val days = rows.map { dayOf(it, zone) }
        val out = ArrayList<LogListItem>(rows.size + 4)
        var start = 0
        while (start < rows.size) {
            var end = start + 1
            while (end < rows.size && days[end] == days[start]) end++
            out.add(LogListItem.DayHeader(label(days[start], today), end - start))
            for (i in start until end) out.add(LogListItem.Entry(rows[i]))
            start = end
        }
        return out
    }

    private fun dayOf(row: LogRow, zone: ZoneId): LocalDate? =
        Fmt.parse(row.createdAt)?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

    fun label(day: LocalDate?, today: LocalDate): String = when {
        day == null -> "Unknown date"
        day == today -> "Today"
        day == today.minusDays(1) -> "Yesterday"
        day.year == today.year -> monthDay.format(day)
        else -> monthDayYear.format(day)
    }
}
