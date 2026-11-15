package com.mainnetwallet.polybot.data

import com.mainnetwallet.polybot.data.model.LogRow
import com.mainnetwallet.polybot.data.model.Modes
import com.mainnetwallet.polybot.data.model.PriceHistory
import com.mainnetwallet.polybot.data.model.Snapshot
import com.mainnetwallet.polybot.data.net.ApiException
import com.mainnetwallet.polybot.data.net.BotApi
import com.mainnetwallet.polybot.data.net.FeedEvent
import com.mainnetwallet.polybot.data.net.FeedState
import com.mainnetwallet.polybot.data.net.LiveFeed
import com.mainnetwallet.polybot.domain.mergeTick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/** What a user action produced: a short message for the snackbar, and whether it worked. */
data class ActionResult(val ok: Boolean, val message: String)

/**
 * The single source of truth for everything live: the last snapshot, the server clock offset, price
 * history for the sparklines and the log buffer. Fed by the WebSocket ([LiveFeed]) and by REST replies.
 */
class BotRepository(
    private val scope: CoroutineScope,
    private val api: BotApi,
    private val feed: LiveFeed,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val _snapshot = MutableStateFlow<Snapshot?>(null)
    val snapshot: StateFlow<Snapshot?> = _snapshot

    /** Server clock minus device clock in ms, so countdowns follow the real market expiry. */
    private val _clockOffsetMs = MutableStateFlow(0L)
    val clockOffsetMs: StateFlow<Long> = _clockOffsetMs

    private val _history = MutableStateFlow(PriceHistory())
    val history: StateFlow<PriceHistory> = _history

    private val _logs = MutableStateFlow<List<LogRow>>(emptyList())
    val logs: StateFlow<List<LogRow>> = _logs

    /** Bumps when orders, positions or the last order change, so lists can refetch right away. */
    private val _dataVersion = MutableStateFlow(0)
    val dataVersion: StateFlow<Int> = _dataVersion

    val feedState: StateFlow<FeedState> = feed.state

    private var historyMarket: String? = null
    private var lastUpStamp = 0.0
    private var lastDownStamp = 0.0
    private var signature = 0

    init {
        scope.launch {
            for (event in feed.events) {
                when (event) {
                    is FeedEvent.StateEvent -> applySnapshot(event.snapshot, recordBtc = true)
                    is FeedEvent.TickEvent -> applyTick(event)
                    is FeedEvent.LogEvent -> appendLog(event.row)
                }
            }
        }
    }

    // ---- feed handling ---------------------------------------------------------------------------------------

    private fun applyTick(event: FeedEvent.TickEvent) {
        val tick = event.tick
        if (tick.serverTimeMs > 0) _clockOffsetMs.value = tick.serverTimeMs.toLong() - nowMs()
        val previous = _snapshot.value ?: return
        val merged = previous.mergeTick(tick)
        _snapshot.value = merged
        recordBooks(merged)
    }

    private fun applySnapshot(next: Snapshot, recordBtc: Boolean) {
        _snapshot.value = next
        parseServerTime(next.serverTime)?.let { _clockOffsetMs.value = it - nowMs() }
        recordBooks(next)
        if (recordBtc) recordBtcPrice(next)
        val sig = listOf(
            next.bot.status,
            next.bot.mode,
            next.account.tradesToday,
            next.positions.map { listOf(it.id, it.size, it.avgPrice, it.costBasis, it.fees, it.realizedPnl, it.status, it.resolution) },
            next.openOrders.map { listOf(it.id, it.status, it.filledSize, it.avgFillPrice, it.fee) },
            next.lastOrder,
        ).hashCode()
        if (sig != signature) {
            signature = sig
            _dataVersion.value = _dataVersion.value + 1
        }
    }

    private fun appendLog(row: LogRow) {
        // The REST reload and the socket can both deliver the same row; ids are unique, so skip repeats.
        if (row.id != null && _logs.value.any { it.id == row.id }) return
        val next = ArrayList<LogRow>(minOf(_logs.value.size + 1, LOG_LIMIT))
        next.add(row)
        next.addAll(_logs.value)
        _logs.value = if (next.size > LOG_LIMIT) next.subList(0, LOG_LIMIT).toList() else next
        _dataVersion.value = _dataVersion.value + 1
    }

    /** Only real book updates are charted; the chart restarts for every new market. */
    private fun recordBooks(snap: Snapshot) {
        val marketId = snap.market?.marketId
        var reset = false
        if (marketId != historyMarket) {
            historyMarket = marketId
            lastUpStamp = 0.0
            lastDownStamp = 0.0
            reset = true
        }
        var upPoint: Float? = null
        var downPoint: Float? = null
        val up = snap.books.up
        val down = snap.books.down
        if (up != null && up.updatedAtMs != lastUpStamp && up.bestAsk != null) {
            lastUpStamp = up.updatedAtMs
            upPoint = up.bestAsk.toFloat()
        }
        if (down != null && down.updatedAtMs != lastDownStamp && down.bestAsk != null) {
            lastDownStamp = down.updatedAtMs
            downPoint = down.bestAsk.toFloat()
        }
        if (!reset && upPoint == null && downPoint == null) return
        val base = if (reset) PriceHistory() else _history.value
        _history.value = base.copy(
            up = push(base.up, upPoint, HISTORY_LIMIT),
            down = push(base.down, downPoint, HISTORY_LIMIT),
            btc = if (reset) emptyList() else base.btc,
        )
    }

    private fun recordBtcPrice(snap: Snapshot) {
        val price = snap.btc?.price ?: return
        val current = _history.value
        _history.value = current.copy(btc = push(current.btc, price.toFloat(), BTC_LIMIT))
    }

    private fun push(list: List<Float>, value: Float?, limit: Int): List<Float> {
        if (value == null) return list
        val next = ArrayList<Float>(minOf(list.size + 1, limit))
        if (list.size >= limit) next.addAll(list.subList(list.size - limit + 1, list.size)) else next.addAll(list)
        next.add(value)
        return next
    }

    private fun parseServerTime(text: String): Long? = try {
        Instant.parse(text).toEpochMilli()
    } catch (_: Exception) {
        null
    }

    // ---- loading ---------------------------------------------------------------------------------------------

    /** Re-reads the full state. Called on pull-to-refresh and after every (re)connect. */
    suspend fun refresh(): ActionResult = try {
        applySnapshot(api.state(), recordBtc = true)
        ActionResult(true, "Updated")
    } catch (e: ApiException) {
        ActionResult(false, e.message ?: "Could not load the state")
    }

    suspend fun loadLogs() {
        try {
            _logs.value = api.logs(200)
        } catch (_: ApiException) {
            // keep what is on screen
        }
    }

    /** Called when the socket (re)connects: the server already sent a full state, REST fills in the logs. */
    fun onFeedConnected() {
        scope.launch {
            refresh()
            loadLogs()
        }
    }

    fun clearAll() {
        _snapshot.value = null
        _history.value = PriceHistory()
        _logs.value = emptyList()
        historyMarket = null
        lastUpStamp = 0.0
        lastDownStamp = 0.0
        signature = 0
        _dataVersion.value = _dataVersion.value + 1
    }

    // ---- actions ---------------------------------------------------------------------------------------------

    private suspend fun act(okMessage: (Snapshot) -> String, block: suspend () -> Snapshot): ActionResult = try {
        val next = block()
        applySnapshot(next, recordBtc = false)
        ActionResult(true, okMessage(next))
    } catch (e: ApiException) {
        ActionResult(false, e.message ?: "Request failed")
    }

    suspend fun startBot(phrase: String): ActionResult {
        val live = _snapshot.value?.bot?.mode == Modes.LIVE
        return act(
            { if (live) "LIVE engine started. Real orders can be submitted." else "Paper engine started." },
        ) { api.startBot(live, phrase) }
    }

    suspend fun stopBot(): ActionResult =
        act({ "Engine stopped. No new orders will be submitted." }) { api.stopBot() }

    suspend fun switchMode(mode: String, phrase: String): ActionResult =
        act({ if (mode == Modes.LIVE) "Mode: LIVE. The engine sends real orders when started." else "Mode: PAPER." }) {
            api.switchMode(mode, mode == Modes.LIVE, phrase)
        }

    suspend fun killSwitch(active: Boolean): ActionResult =
        act({ if (active) "Kill switch engaged. Bot stopped." else "Kill switch cleared." }) { api.killSwitch(active) }

    suspend fun sellNow(marketId: String, side: String, stage: Int): ActionResult {
        val result = act({ "Stage $stage $side shares sold at the market." }) { api.sellNow(marketId, side, stage) }
        if (!result.ok) refresh()
        return result
    }

    suspend fun sellAll(marketId: String): ActionResult {
        val result = act(
            { next ->
                val failed = next.manualSellAll?.failed.orEmpty()
                val sold = next.manualSellAll?.sold.orEmpty()
                if (failed.isEmpty()) {
                    "Sell all sent: ${sold.size} stage${if (sold.size == 1) "" else "s"} sold."
                } else {
                    "Sold ${sold.size}, ${failed.size} not sold: " + failed.joinToString(" | ") { "${it.side}: ${it.message}" }
                }
            },
        ) { api.sellAll(marketId) }
        if (!result.ok) refresh()
        return result
    }

    suspend fun resetPaper(balance: Double): ActionResult =
        act({ "Paper account reset to $${"%.2f".format(balance)}." }) { api.resetPaper(balance) }

    suspend fun clearOrderHistory(): ActionResult =
        act({ "Order history cleared. Daily PnL and stats are unchanged." }) { api.clearOrders() }

    suspend fun resetDaily(): ActionResult =
        act({ "Daily stats reset." }) { api.resetDaily() }

    suspend fun resetServer(phrase: String): ActionResult {
        val result = act({ "Server reset. All data wiped." }) { api.resetServer(phrase) }
        if (result.ok) _logs.value = emptyList()
        return result
    }

    companion object {
        const val HISTORY_LIMIT = 180
        const val BTC_LIMIT = 240
        const val LOG_LIMIT = 400
    }
}
