package com.mainnetwallet.polybot.domain

import com.mainnetwallet.polybot.data.model.LogRow

/** How many entries of each level a list has. [all] also counts levels that have no filter of their own (DEBUG...). */
data class LevelCounts(val all: Int, val info: Int, val warn: Int, val error: Int) {
    fun of(filter: LevelFilter): Int = when (filter) {
        LevelFilter.All -> all
        LevelFilter.Info -> info
        LevelFilter.Warn -> warn
        LevelFilter.Error -> error
    }
}

/** Filtering, searching and counting of log entries. Free of Compose and Android, so it is unit tested. */
object LogsLogic {
    /** True when [query] (trimmed, any case) is part of the message, the category or the payload. Blank matches all. */
    fun matchesQuery(row: LogRow, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return row.message.contains(q, ignoreCase = true) ||
            row.category.contains(q, ignoreCase = true) ||
            row.payload?.toString()?.contains(q, ignoreCase = true) == true
    }

    /** Applies level, category (null = every category) and search text to [rows], keeping their order. */
    fun filter(rows: List<LogRow>, level: LevelFilter, category: String?, query: String): List<LogRow> =
        rows.filter { row ->
            level.matches(row.level) &&
                (category == null || row.category == category) &&
                matchesQuery(row, query)
        }

    fun counts(rows: List<LogRow>): LevelCounts = LevelCounts(
        all = rows.size,
        info = rows.count { LevelFilter.Info.matches(it.level) },
        warn = rows.count { LevelFilter.Warn.matches(it.level) },
        error = rows.count { LevelFilter.Error.matches(it.level) },
    )

    /** Distinct non-blank categories, most frequent first, ties in alphabetical order. */
    fun categories(rows: List<LogRow>): List<String> =
        rows.map { it.category }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }

    /** Plain text of one entry for the clipboard. [payloadText] is the already pretty-printed payload, if any. */
    fun entryText(row: LogRow, payloadText: String?): String = buildString {
        append('[').append(row.level.uppercase().ifBlank { "INFO" }).append(']')
        if (row.category.isNotBlank()) append(' ').append(row.category)
        if (row.createdAt.isNotBlank()) append("  ").append(row.createdAt)
        append('\n').append(row.message)
        if (!payloadText.isNullOrBlank()) append("\n\n").append(payloadText)
    }
}
