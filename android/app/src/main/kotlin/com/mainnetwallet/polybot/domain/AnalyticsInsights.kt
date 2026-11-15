package com.mainnetwallet.polybot.domain

import com.mainnetwallet.polybot.data.model.Analytics

/**
 * Numbers the Analytics screen derives from the plain [Analytics] totals. It has no Compose in it, so it is unit
 * tested. Percent values run 0..100, like [Analytics.winRate].
 */
data class AnalyticsInsights(
    /** Trades that ended as a win or a loss. */
    val decided: Int,
    /** Trades that are still open. Never negative. */
    val openTrades: Int,
    /** Realized PnL per settled trade, in USDC. */
    val avgPnlPerSettled: Double?,
    /** Volume per trade, in USDC. */
    val avgTradeSize: Double?,
    /** Fees as a share of the volume, in percent. */
    val feePercentOfVolume: Double?,
    /** Win rate at which the average entry price breaks even, in percent. Fees are not counted. */
    val breakEvenWinRate: Double?,
    /** Win rate minus [breakEvenWinRate], in percentage points. */
    val edgePoints: Double?,
) {
    companion object {
        fun of(a: Analytics): AnalyticsInsights {
            val winRate = finite(a.winRate)
            val entry = finite(a.avgEntryPrice)?.takeIf { it > 0.0 && it <= 1.0 }
            val breakEven = entry?.let { it * 100.0 }
            return AnalyticsInsights(
                decided = a.wins + a.losses,
                openTrades = maxOf(0, a.totalTrades - a.closedTrades),
                avgPnlPerSettled = if (a.closedTrades > 0) finite(a.realizedPnl / a.closedTrades) else null,
                avgTradeSize = if (a.totalTrades > 0) finite(a.volumeUsdc / a.totalTrades) else null,
                feePercentOfVolume = if (a.volumeUsdc > 0.0) finite(a.feesPaid / a.volumeUsdc * 100.0) else null,
                breakEvenWinRate = breakEven,
                edgePoints = if (winRate != null && breakEven != null) winRate - breakEven else null,
            )
        }

        private fun finite(v: Double?): Double? = if (v == null || v.isNaN() || v.isInfinite()) null else v
    }
}
