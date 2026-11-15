package com.mainnetwallet.polybot.domain

import com.mainnetwallet.polybot.data.model.Snapshot
import com.mainnetwallet.polybot.data.model.Tick

/**
 * Merges a lightweight `tick` into the last full snapshot: the books are replaced, the evaluation fields
 * of the strategy are overlaid and the market countdown is refreshed. Everything else stays untouched.
 */
fun Snapshot.mergeTick(tick: Tick): Snapshot {
    val st = tick.strategy
    return copy(
        books = tick.books,
        strategy = if (st == null) {
            strategy
        } else {
            strategy.copy(
                inWindow = st.inWindow,
                remainingSeconds = st.remainingSeconds,
                upAsk = st.upAsk,
                downAsk = st.downAsk,
                upArmed = st.upArmed,
                downArmed = st.downArmed,
                reason = st.reason,
            )
        },
        market = if (market != null && tick.remainingSeconds != null) {
            market.copy(remainingSeconds = tick.remainingSeconds)
        } else {
            market
        },
    )
}
