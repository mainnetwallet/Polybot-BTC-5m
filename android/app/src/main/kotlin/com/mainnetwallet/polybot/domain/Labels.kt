package com.mainnetwallet.polybot.domain

/** Colour family of a status chip. The UI maps a tone to theme colours. */
enum class Tone { Up, Warn, Info, Down, Neutral }

/** Tone of an order, position or trigger status. Mirrors statusTone() of the web dashboard. */
fun statusTone(status: String): Tone = when (status) {
    "FILLED", "filled", "submitted" -> Tone.Up
    "PARTIALLY_FILLED", "partially_filled", "PENDING", "pending", "settling" -> Tone.Warn
    "SUBMITTED", "submitted_pending", "armed" -> Tone.Info
    "REJECTED", "CANCELED", "CANCELLED", "FAILED", "EXPIRED", "rejected", "blocked", "expired" -> Tone.Down
    else -> when {
        status.startsWith("blocked") || status.startsWith("failed") -> Tone.Down
        status.startsWith("skipped") -> Tone.Warn
        status.startsWith("filled") -> Tone.Up
        else -> Tone.Neutral
    }
}

/** What kind of order a row is: "BUY", "TAKE PROFIT", "STOP LOSS", "MANUAL SELL", plain "SELL" or null. */
fun orderKind(action: String?, reason: String?): String? = when (action) {
    "BUY" -> "BUY"
    "SELL" -> when {
        reason?.endsWith("_stop_loss") == true -> "STOP LOSS"
        reason?.endsWith("_manual") == true -> "MANUAL SELL"
        reason?.endsWith("_take_profit") == true -> "TAKE PROFIT"
        else -> "SELL"
    }
    else -> null
}

/**
 * The result chip of an order row: a filled BUY shows "BUY" (its WIN/LOSS lives on the SELL row),
 * everything else shows the server's result (WIN, LOSS, OPEN) or nothing.
 */
fun resultLabel(action: String?, filledSize: Double, result: String?): String? =
    if (action == "BUY" && filledSize > 0) "BUY" else result?.takeIf { it.isNotBlank() }

/** Log level filter of the Logs screen. The backend writes "WARNING"; ERROR also covers CRITICAL. */
enum class LevelFilter(val label: String) {
    All("All"),
    Info("INFO"),
    Warn("WARN"),
    Error("ERROR");

    fun matches(level: String): Boolean = when (this) {
        All -> true
        Info -> level.equals("INFO", ignoreCase = true)
        Warn -> level.equals("WARNING", ignoreCase = true) || level.equals("WARN", ignoreCase = true)
        Error -> level.equals("ERROR", ignoreCase = true) || level.equals("CRITICAL", ignoreCase = true)
    }
}
