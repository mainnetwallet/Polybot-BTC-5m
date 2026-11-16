package com.mainnetwallet.polybot.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Mirrors frontend/src/api/types.ts. Every field has a default so partial or older payloads still parse.

object Modes {
    const val PAPER = "paper"
    const val LIVE = "live"
}

@Serializable
data class BookSnapshot(
    @SerialName("token_id") val tokenId: String = "",
    @SerialName("market_id") val marketId: String = "",
    val side: String = "",
    @SerialName("best_bid") val bestBid: Double? = null,
    @SerialName("best_ask") val bestAsk: Double? = null,
    @SerialName("bid_size") val bidSize: Double? = null,
    @SerialName("ask_size") val askSize: Double? = null,
    @SerialName("updated_at_ms") val updatedAtMs: Double = 0.0,
    @SerialName("age_ms") val ageMs: Double? = null,
    val source: String = "",
)

@Serializable
data class Books(
    val up: BookSnapshot? = null,
    val down: BookSnapshot? = null,
)

@Serializable
data class MarketSnapshot(
    @SerialName("market_id") val marketId: String = "",
    val slug: String = "",
    @SerialName("event_slug") val eventSlug: String = "",
    @SerialName("window_start") val windowStart: String = "",
    @SerialName("window_end") val windowEnd: String = "",
    @SerialName("remaining_seconds") val remainingSeconds: Double = 0.0,
    @SerialName("duration_seconds") val durationSeconds: Double = 300.0,
    @SerialName("up_token_id") val upTokenId: String = "",
    @SerialName("down_token_id") val downTokenId: String = "",
    val status: String = "",
)

@Serializable
data class StrategyState(
    val name: String = "",
    @SerialName("in_window") val inWindow: Boolean = false,
    @SerialName("remaining_seconds") val remainingSeconds: Double? = null,
    @SerialName("up_ask") val upAsk: Double? = null,
    @SerialName("down_ask") val downAsk: Double? = null,
    @SerialName("up_armed") val upArmed: Boolean = false,
    @SerialName("down_armed") val downArmed: Boolean = false,
    val reason: String = "",
    @SerialName("stage1_threshold") val stage1Threshold: Double = 0.0,
    @SerialName("stage1_seconds") val stage1Seconds: Double = 0.0,
    @SerialName("stage1_enabled") val stage1Enabled: Boolean = false,
    @SerialName("stage2_enabled") val stage2Enabled: Boolean = false,
    @SerialName("stage3_enabled") val stage3Enabled: Boolean = false,
    @SerialName("stage2_seconds") val stage2Seconds: Double = 0.0,
    @SerialName("stage2_threshold") val stage2Threshold: Double = 0.0,
    @SerialName("stage3_seconds") val stage3Seconds: Double = 0.0,
    @SerialName("stage3_threshold") val stage3Threshold: Double = 0.0,
    @SerialName("stage0_enabled") val stage0Enabled: Boolean = false,
    @SerialName("stage0_start_seconds") val stage0StartSeconds: Double = 0.0,
    @SerialName("stage0_end_seconds") val stage0EndSeconds: Double = 0.0,
    @SerialName("stage0_min_ask") val stage0MinAsk: Double = 0.0,
    @SerialName("stage0_max_ask") val stage0MaxAsk: Double = 0.0,
    @SerialName("stage0_early_max_ask") val stage0EarlyMaxAsk: Double = 0.0,
    @SerialName("stage0_min_price_gap_usd") val stage0MinPriceGapUsd: Double = 0.0,
    @SerialName("stage0_mid_from_ask") val stage0MidFromAsk: Double = 0.0,
    @SerialName("stage0_high_from_ask") val stage0HighFromAsk: Double = 0.0,
    @SerialName("stage0_take_profit_pct_low") val stage0TakeProfitPctLow: Double = 0.0,
    @SerialName("stage0_take_profit_pct_mid") val stage0TakeProfitPctMid: Double = 0.0,
    @SerialName("stage0_take_profit_pct_high") val stage0TakeProfitPctHigh: Double = 0.0,
    @SerialName("stage1_sell_enabled") val stage1SellEnabled: Boolean = false,
    @SerialName("stage1_sell_max_buy") val stage1SellMaxBuy: Double = 0.0,
    @SerialName("stage1_take_profit_pct") val stage1TakeProfitPct: Double = 0.0,
    @SerialName("stage1_sell_price_high") val stage1SellPriceHigh: Double = 0.0,
    @SerialName("stage2_sell_enabled") val stage2SellEnabled: Boolean = false,
    @SerialName("stage2_sell_price") val stage2SellPrice: Double = 0.0,
    @SerialName("stage0_stop_loss_pct") val stage0StopLossPct: Double = 0.0,
    @SerialName("stage1_stop_loss_pct") val stage1StopLossPct: Double = 0.0,
    @SerialName("stage2_stop_loss_pct") val stage2StopLossPct: Double = 0.0,
    @SerialName("stage3_stop_loss_pct") val stage3StopLossPct: Double = 0.0,
    @SerialName("stage0_high_buy_price") val stage0HighBuyPrice: Double = 0.0,
    @SerialName("stage0_high_buy_stop_loss_pct") val stage0HighBuyStopLossPct: Double = 0.0,
    @SerialName("stage0_high_buy_after_pct") val stage0HighBuyAfterPct: Double = 0.0,
    @SerialName("conflict_policy") val conflictPolicy: String = "",
    @SerialName("duplicate_policy") val duplicatePolicy: String = "",
)

/** The partial strategy object that every `tick` carries (StrategyEvaluation.summary() on the backend). */
@Serializable
data class StrategyTick(
    @SerialName("in_window") val inWindow: Boolean = false,
    @SerialName("remaining_seconds") val remainingSeconds: Double? = null,
    @SerialName("up_ask") val upAsk: Double? = null,
    @SerialName("down_ask") val downAsk: Double? = null,
    @SerialName("up_armed") val upArmed: Boolean = false,
    @SerialName("down_armed") val downArmed: Boolean = false,
    val reason: String = "",
)

@Serializable
data class AccountState(
    val mode: String = Modes.PAPER,
    val balance: Double = 0.0,
    @SerialName("starting_balance") val startingBalance: Double = 0.0,
    @SerialName("realized_pnl") val realizedPnl: Double = 0.0,
    @SerialName("unrealized_pnl") val unrealizedPnl: Double = 0.0,
    @SerialName("daily_pnl") val dailyPnl: Double = 0.0,
    @SerialName("trades_today") val tradesToday: Int = 0,
    @SerialName("positions_awaiting_settlement") val positionsAwaitingSettlement: Int = 0,
    @SerialName("pending_redeems") val pendingRedeems: Int = 0,
)

@Serializable
data class PositionRow(
    val id: Long = 0,
    @SerialName("market_id") val marketId: String = "",
    val side: String = "",
    val size: Double = 0.0,
    @SerialName("avg_price") val avgPrice: Double = 0.0,
    @SerialName("cost_basis") val costBasis: Double = 0.0,
    val fees: Double = 0.0,
    @SerialName("unrealized_pnl") val unrealizedPnl: Double = 0.0,
    @SerialName("realized_pnl") val realizedPnl: Double = 0.0,
    val status: String = "",
    val resolution: String? = null,
    @SerialName("awaiting_settlement") val awaitingSettlement: Boolean = false,
    @SerialName("opened_at") val openedAt: String? = null,
    @SerialName("closed_at") val closedAt: String? = null,
)

@Serializable
data class SellableHolding(
    @SerialName("market_id") val marketId: String = "",
    val side: String = "",
    val stage: Int = 0,
    val size: Double = 0.0,
    /** Average price this stage paid per share (null when the server does not know it). */
    @SerialName("entry_price") val entryPrice: Double? = null,
)

@Serializable
data class OrderRow(
    val id: Long = 0,
    @SerialName("market_id") val marketId: String = "",
    val side: String = "",
    val stage: Int? = null,
    val action: String? = null,
    val status: String = "",
    @SerialName("requested_notional") val requestedNotional: Double = 0.0,
    @SerialName("requested_price") val requestedPrice: Double = 0.0,
    @SerialName("submitted_price") val submittedPrice: Double? = null,
    @SerialName("filled_size") val filledSize: Double = 0.0,
    @SerialName("avg_fill_price") val avgFillPrice: Double? = null,
    val fee: Double = 0.0,
    @SerialName("latency_ms") val latencyMs: Double? = null,
    @SerialName("exchange_order_id") val exchangeOrderId: String? = null,
    val error: String? = null,
    val reason: String? = null,
    val result: String? = null,
    @SerialName("result_pnl") val resultPnl: Double? = null,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class LastOrder(
    @SerialName("order_id") val orderId: Long? = null,
    val side: String = "",
    val status: String = "",
    val reason: String = "",
    @SerialName("filled_size") val filledSize: Double = 0.0,
    @SerialName("avg_price") val avgPrice: Double? = null,
    @SerialName("exchange_order_id") val exchangeOrderId: String? = null,
    val error: String? = null,
    val ts: String? = null,
)

@Serializable
data class LiveReadiness(
    @SerialName("live_enabled") val liveEnabled: Boolean = false,
    @SerialName("geo_verified") val geoVerified: Boolean = false,
    @SerialName("credentials_present") val credentialsPresent: Boolean = false,
    @SerialName("funder_configured") val funderConfigured: Boolean = false,
    val ready: Boolean = false,
    val reasons: List<String> = emptyList(),
    val balance: Double? = null,
)

@Serializable
data class BtcState(
    val price: Double? = null,
    @SerialName("price_to_beat") val priceToBeat: Double? = null,
    @SerialName("price_to_beat_source") val priceToBeatSource: String? = null,
    @SerialName("price_source") val priceSource: String? = null,
    val diff: Double? = null,
    @SerialName("age_ms") val ageMs: Double? = null,
    val stale: Boolean = true,
    val connected: Boolean = false,
    val enabled: Boolean = false,
    @SerialName("last_error") val lastError: String? = null,
)

@Serializable
data class MarketDataState(
    val connected: Boolean = false,
    val reconnects: Int = 0,
    @SerialName("last_message_ms") val lastMessageMs: Double? = null,
    @SerialName("last_error") val lastError: String? = null,
)

@Serializable
data class BotState(
    val status: String = "STOPPED",
    val mode: String = Modes.PAPER,
    @SerialName("kill_switch") val killSwitch: Boolean = false,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("risk_halt") val riskHalt: String? = null,
) {
    val running: Boolean get() = status == "RUNNING"
    val isLive: Boolean get() = mode == Modes.LIVE
}

@Serializable
data class SoldRow(
    val status: String = "",
    val side: String = "",
    @SerialName("filled_size") val filledSize: Double = 0.0,
)

@Serializable
data class FailedRow(
    val status: String = "",
    val side: String = "",
    val message: String = "",
)

@Serializable
data class ManualSellAll(
    val sold: List<SoldRow> = emptyList(),
    val failed: List<FailedRow> = emptyList(),
)

@Serializable
data class Snapshot(
    @SerialName("server_time") val serverTime: String = "",
    val bot: BotState = BotState(),
    val market: MarketSnapshot? = null,
    val books: Books = Books(),
    val strategy: StrategyState = StrategyState(),
    val account: AccountState = AccountState(),
    val positions: List<PositionRow> = emptyList(),
    @SerialName("open_orders") val openOrders: List<OrderRow> = emptyList(),
    val sellable: List<SellableHolding> = emptyList(),
    @SerialName("manual_sell_all") val manualSellAll: ManualSellAll? = null,
    @SerialName("last_order") val lastOrder: LastOrder? = null,
    @SerialName("market_data") val marketData: MarketDataState = MarketDataState(),
    val btc: BtcState? = null,
    val live: LiveReadiness = LiveReadiness(),
    val config: Map<String, JsonElement> = emptyMap(),
)

/** `tick` event: pushed on every order-book event, merged into the last snapshot. */
@Serializable
data class Tick(
    @SerialName("server_time_ms") val serverTimeMs: Double = 0.0,
    @SerialName("remaining_seconds") val remainingSeconds: Double? = null,
    val books: Books = Books(),
    val strategy: StrategyTick? = null,
)

@Serializable
data class Analytics(
    val mode: String = Modes.PAPER,
    @SerialName("total_trades") val totalTrades: Int = 0,
    @SerialName("closed_trades") val closedTrades: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    @SerialName("win_rate") val winRate: Double? = null,
    @SerialName("total_pnl") val totalPnl: Double = 0.0,
    @SerialName("realized_pnl") val realizedPnl: Double = 0.0,
    @SerialName("unrealized_pnl") val unrealizedPnl: Double = 0.0,
    @SerialName("avg_entry_price") val avgEntryPrice: Double? = null,
    @SerialName("fees_paid") val feesPaid: Double = 0.0,
    @SerialName("volume_usdc") val volumeUsdc: Double = 0.0,
)

@Serializable
data class TriggerRow(
    val id: Long = 0,
    @SerialName("market_id") val marketId: String = "",
    val side: String = "",
    @SerialName("best_ask") val bestAsk: Double = 0.0,
    @SerialName("ask_size") val askSize: Double? = null,
    @SerialName("remaining_seconds") val remainingSeconds: Double = 0.0,
    val threshold: Double = 0.0,
    val triggered: Boolean = false,
    val decision: String = "",
    val reason: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class LogRow(
    val id: Long? = null,
    val level: String = "INFO",
    val category: String = "",
    val message: String = "",
    val payload: JsonObject? = null,
    val mode: String? = null,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class HealthInfo(
    val status: String = "",
    @SerialName("bot_status") val botStatus: String = "",
    val mode: String = "",
)

/** One step of the order-book history used by the sparklines (best ask per side, BTC price). */
data class PriceHistory(
    val up: List<Float> = emptyList(),
    val down: List<Float> = emptyList(),
    val btc: List<Float> = emptyList(),
)
