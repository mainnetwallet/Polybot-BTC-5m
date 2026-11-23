package com.mainnetwallet.polybot.domain

import com.mainnetwallet.polybot.data.model.BuyLimitRow
import com.mainnetwallet.polybot.data.model.SellableHolding
import com.mainnetwallet.polybot.data.model.TAP_STAGE

/**
 * Pure logic of the "Limit buy/sell" dialog (the same rules as the web dashboard's LimitOrderButton). Prices are typed
 * and shown in cents (1 to 99); the API takes 0.01 to 0.99. Limits are kept by the bot, never placed on the exchange.
 */

/** One row of the sell list: a held position (a strategy stage or the one-tap shares) or a one-tap buy that is still waiting. */
data class SellTarget(
    val side: String,
    val stage: Int,
    /** shares held, null while the position does not exist yet (it waits for a buy limit to fill) */
    val size: Double?,
    val entry: Double?,
) {
    val key: String get() = "${side.uppercase()}:$stage"
}

object LimitOrder {
    const val MIN_CENTS = 1.0
    const val MAX_CENTS = 99.0

    val BUY_PICKS = listOf(40, 50, 60, 70, 80, 90, 95)
    val SELL_PICKS = listOf(50, 75, 90, 95, 99)
    val AMOUNT_PICKS = listOf(1, 2, 3, 5)

    /** The typed text as a price in cents (1 to 99, decimals allowed), null when empty or invalid. */
    fun parseCents(text: String): Double? {
        val v = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        return if (v.isFinite() && v >= MIN_CENTS && v <= MAX_CENTS) v else null
    }

    /** The typed amount in USDC, null when empty, invalid or outside [min, max]. */
    fun parseAmount(text: String, min: Double, max: Double): Double? {
        val v = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        return if (v.isFinite() && v >= min && v <= max) v else null
    }

    /** 0.8 -> "80", 0.855 -> "85.5" (a limit is shown and typed in cents). */
    fun centsText(price: Double): String {
        val cents = Math.round(price * 1000.0) / 10.0
        return if (cents == Math.floor(cents)) cents.toLong().toString() else cents.toString()
    }

    /** Keeps a slider / stepper value inside 1..99 whole cents. */
    fun clampCents(cents: Double): Int = Math.round(cents).toInt().coerceIn(MIN_CENTS.toInt(), MAX_CENTS.toInt())

    /** Shares a buy of [amount] USDC gets at [cents] (a lower ask fills more, so this is the least). */
    fun buyShares(amount: Double, cents: Double): Double = amount / (cents / 100.0)

    /** Profit of the buy if the side wins, at the limit price. */
    fun buyProfit(amount: Double, cents: Double): Double = buyShares(amount, cents) - amount

    /** What [size] shares bring in at [cents]. */
    fun sellProceeds(size: Double, cents: Double): Double = size * cents / 100.0

    /** Profit of selling [size] shares at [cents] against what they cost ([entry] per share); null when the cost is unknown. */
    fun sellProfit(size: Double?, entry: Double?, cents: Double): Double? =
        if (size == null || entry == null) null else sellProceeds(size, cents) - entry * size

    /** A buy limit at [cents] fires at once when the ask is already at or below it. */
    fun buyFiresNow(ask: Double?, cents: Double): Boolean = ask != null && ask * 100.0 <= cents + 1e-9

    /** A sell limit at [cents] fires at once when the bid is already at or above it. */
    fun sellFiresNow(bid: Double?, cents: Double): Boolean = bid != null && bid * 100.0 + 1e-9 >= cents

    /**
     * The positions a sell limit can be set on: what is held now in the live market, plus the one-tap shares a buy limit
     * is waiting to buy (so their sell limit can be set before the buy fills). Strategy stages first, one-tap last.
     */
    fun sellTargets(marketId: String?, sellable: List<SellableHolding>, buyLimits: List<BuyLimitRow>): List<SellTarget> {
        if (marketId == null) return emptyList()
        val targets = sellable
            .filter { it.marketId == marketId && it.size > 0 }
            .map { SellTarget(it.side.uppercase(), it.stage, it.size, it.entryPrice) }
            .toMutableList()
        for (l in buyLimits) {
            if (l.marketId != marketId) continue
            if (targets.none { it.side.equals(l.side, ignoreCase = true) && it.stage == TAP_STAGE }) {
                targets.add(SellTarget(l.side.uppercase(), TAP_STAGE, null, null))
            }
        }
        return targets.sortedWith(
            compareBy<SellTarget>({ it.stage == TAP_STAGE }, { it.stage }, { it.side }),
        )
    }

    fun stageLabel(stage: Int): String = if (stage == TAP_STAGE) "One-tap" else "Stage $stage"
}
