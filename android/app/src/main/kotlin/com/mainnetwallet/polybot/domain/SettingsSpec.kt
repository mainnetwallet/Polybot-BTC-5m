package com.mainnetwallet.polybot.domain

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The editable bot settings. Keys, ranges and cross-field rules mirror `SettingsPatch` in
 * backend/app/api/schemas.py and frontend/src/pages/settings/{fields,validate}.ts. The server stays the
 * authority; this only catches typos before a request is sent.
 */
sealed interface SettingKind {
    data object Toggle : SettingKind
    data class Number(val min: Double? = null, val max: Double? = null, val integer: Boolean = false, val suffix: String? = null) : SettingKind
    data class Choice(val options: List<Pair<String, String>>) : SettingKind
}

data class SettingField(val key: String, val label: String, val hint: String, val kind: SettingKind)

data class SettingGroup(val title: String, val description: String? = null, val keys: List<String>)

data class SettingSection(val id: String, val title: String, val groups: List<SettingGroup>)

/** One tab of a section: a label and the groups shown on it. */
data class SettingPage(val label: String, val groups: List<SettingGroup>)

object SettingsSpec {
    private fun num(min: Double? = null, max: Double? = null, integer: Boolean = false, suffix: String? = null) =
        SettingKind.Number(min, max, integer, suffix)

    private val price = num(0.01, 0.999)

    val fields: List<SettingField> = listOf(
        SettingField("stage1_threshold", "Ask trigger", "Ask at or above this decimal fires a marketable BUY. Never used as a limit price.", price),
        SettingField("stage1_max_ask", "Max ask (no buy above)", "Stage 1 never buys when the best ask is above this decimal (default 0.97), and never pays more than it.", num(0.01, 1.0)),
        SettingField("stage1_seconds", "Starts at (seconds left)", "Stage 1 may start buying when this many seconds are left. It ends where the next switched-on stage starts.", num(1.0, 300.0, true, "s")),
        SettingField("duplicate_policy", "Duplicate policy", "Controls repeat entries for the same market or side.", SettingKind.Choice(listOf(
            "one_per_market" to "One order per market", "one_per_side" to "One per side", "allow" to "Allow duplicates"))),
        SettingField("conflict_policy", "Conflict policy", "Decision when UP and DOWN both qualify.", SettingKind.Choice(listOf(
            "up_first" to "UP first", "down_first" to "DOWN first", "highest_ask" to "Highest ask", "block_both" to "Block both"))),

        SettingField("stage0_enabled", "Stage 0: early buy + sell", "On: before the stage 1, buy when the ask is low enough and BTC is far enough beyond the price to beat.", SettingKind.Toggle),
        SettingField("stage0_start_seconds", "Starts at (seconds left)", "Stage 0 may start buying when this many seconds are left.", num(1.0, 300.0, true, "s")),
        SettingField("stage0_end_seconds", "Ends at (seconds left)", "Stage 0 stops buying when this many seconds are left.", num(0.0, 299.0, true, "s")),
        SettingField("stage1_block_ask", "Block market at ask", "If either side's best ask touches this decimal during the stage 1 window and stage 1 has not bought in that market yet, stage 1 buys nothing more in that market (even if the ask falls back). Only that market; stages 0, 2 and 3 and existing sells are not affected. Default 0.99; above that the block is off.", price),
        SettingField("stage0_min_ask", "Min ask", "Buys only while the best ask is at or above this decimal.", price),
        SettingField("stage0_early_max_ask", "Max ask (earlier buy)", "Cheaper buys (ask below the expensive-buy price) only while the best ask is at or below this decimal; also the highest price they pay. Asks between this and the expensive-buy price do not buy.", price),
        SettingField("stage0_max_ask", "Max ask (expensive buy)", "Expensive buys (ask at or above the expensive-buy price) only while the best ask is at or below this decimal; also the highest price they pay.", price),
        SettingField("stage0_block_ask", "Block market at ask", "If either side's best ask touches this decimal during the stage 0 window, stage 0 buys nothing more in that market (even if the ask falls back). Only that market; stages 1 to 3 and existing sells are not affected. Default 0.98; above that the block is off.", price),
        SettingField("stage0_high_buy_after_pct", "Expensive buys start after", "PERCENT of the stage 0 window (not seconds) that must pass before buys at the expensive-buy price or more. 0 = whole window, 100 = never.", num(0.0, 100.0, suffix = "%")),
        SettingField("stage0_min_price_gap_usd", "Min price gap", "BTC must already be this many USD past the price to beat. 0 = filter off.", num(0.0, 1_000_000.0, suffix = "USD")),
        SettingField("stage0_high_min_price_gap_usd", "Min price gap (expensive buys)", "Same, for the expensive buys (ask at or above the expensive-buy price). 0 = filter off.", num(0.0, 1_000_000.0, suffix = "USD")),
        SettingField("stage0_mid_from_ask", "Mid tier from (buy price)", "Shares bought below this price use the low take-profit %.", price),
        SettingField("stage0_high_from_ask", "High tier from (buy price)", "Shares bought at or above this price use the high take-profit %.", price),
        SettingField("stage0_take_profit_pct_low", "Take-profit % (low tier)", "Percent above the buy price. 0 = held to settlement.", num(0.0, 500.0, suffix = "%")),
        SettingField("stage0_take_profit_pct_mid", "Take-profit % (mid tier)", "Percent above the buy price. 0 = held to settlement.", num(0.0, 500.0, suffix = "%")),
        SettingField("stage0_take_profit_pct_high", "Take-profit % (high tier)", "Percent above the buy price, sell price capped at the max sell price. 0 = held to settlement.", num(0.0, 500.0, suffix = "%")),
        SettingField("stage0_sell_cap_price", "Max sell price (bid)", "A stage 0 position is sold at the latest once the best bid reaches this decimal (default 0.98). Keep it above the stage 0 max ask.", num(0.02, 0.999)),
        SettingField("stage0_stop_loss_pct", "Stop-loss %", "Market sell once the best bid is this many percent below the average buy. 0 = off.", num(0.0, 99.0, suffix = "%")),
        SettingField("stage0_high_buy_price", "Expensive buy from (min ask)", "Expensive buy range = this price up to the expensive max ask (default 0.89 to 0.96). Also sets later entry and the expensive-buy stop-loss.", price),
        SettingField("stage0_high_buy_stop_loss_pct", "Expensive buy stop-loss %", "Stop-loss for expensive stage 0 buys. 0 = off, the normal stage 0 stop-loss applies.", num(0.0, 99.0, suffix = "%")),

        SettingField("stage1_enabled", "Stage 1: on / off", "Off: no buys, no take-profit and no stop-loss for stage 1.", SettingKind.Toggle),
        SettingField("min_price_gap_usd", "Min price gap", "BTC must be this far beyond the price to beat in the bought direction. 0 = off.", num(0.0, 1_000_000.0, suffix = "USD")),
        SettingField("stage1_sell_enabled", "Take-profit sell", "On: stage 1 shares are sold as soon as the best bid reaches their sell price.", SettingKind.Toggle),
        SettingField("stage1_sell_max_buy", "Cheap buy up to", "Stage 1 shares costing up to this use the % take-profit, dearer ones the high sell price.", price),
        SettingField("stage1_take_profit_pct", "Take-profit % (cheap buy)", "Percent above the average buy price. 0 = no take-profit for these shares.", num(0.0, 500.0, suffix = "%")),
        SettingField("stage1_sell_price_high", "Sell at (dearer buy)", "Sell price for stage 1 shares bought above the cheap-buy limit.", num(0.02, 0.999)),
        SettingField("stage1_stop_loss_pct", "Stop-loss %", "Market sell once the best bid is this many percent below the average buy. 0 = off.", num(0.0, 99.0, suffix = "%")),

        SettingField("stage2_enabled", "Stage 2: on / off", "Off: no buys, no take-profit and no stop-loss for stage 2.", SettingKind.Toggle),
        SettingField("stage2_seconds", "Seconds left", "From this many seconds before expiry stage 2 may add one more buy.", num(1.0, 300.0, true, "s")),
        SettingField("stage2_threshold", "Ask trigger", "Stage 2 buys when a best ask is at or above this decimal.", price),
        SettingField("stage2_max_ask", "Max ask (no buy above)", "Stage 2 never buys when the best ask is above this decimal (default 0.97), and never pays more than it. The sell price must stay at least 0.01 above it.", num(0.01, 1.0)),
        SettingField("stage2_max_spread", "Max spread", "Stage 2 does not buy when best ask minus best bid is wider than this (default 0.05). 0 = off; a missing bid then also blocks.", num(0.0, 1.0)),
        SettingField("stage2_block_ask", "Block market at ask", "If either side's best ask touches this decimal during the stage 2 window, stage 2 buys nothing more in that market (even if the ask falls back). Only that market and only stage 2; existing sells are not affected. Default 0.99; above that the block is off.", price),
        SettingField("stage2_min_price_gap_usd", "Min price gap", "Same price-to-beat rule for stage 2 buys. 0 = off.", num(0.0, 1_000_000.0, suffix = "USD")),
        SettingField("stage2_sell_enabled", "Take-profit sell", "On: stage 2 shares are sold when the best bid reaches the sell price.", SettingKind.Toggle),
        SettingField("stage2_sell_price", "Sell at", "Must be higher than the stage 2 ask trigger.", num(0.02, 0.999)),
        SettingField("stage2_stop_loss_pct", "Stop-loss %", "Market sell once the best bid is this many percent below the average buy. 0 = off.", num(0.0, 99.0, suffix = "%")),

        SettingField("stage3_enabled", "Stage 3: on / off", "Off: no buys and no stop-loss for stage 3. Stage 3 has no take-profit.", SettingKind.Toggle),
        SettingField("stage3_seconds", "Seconds left", "From this many seconds before expiry stage 3 may buy. Stage 1 stops buying at this mark.", num(1.0, 300.0, true, "s")),
        SettingField("stage3_threshold", "Ask trigger", "Stage 3 buys when a best ask is at or above this decimal.", price),
        SettingField("stage3_max_ask", "Max ask (no buy above)", "Stage 3 never buys when the best ask is above this decimal (default 0.98, buys up to and including it), and never pays more than it.", num(0.01, 1.0)),
        SettingField("stage3_max_spread", "Max spread", "Stage 3 does not buy when best ask minus best bid is wider than this (default 0.05). 0 = off; a missing bid then also blocks.", num(0.0, 1.0)),
        SettingField("stage3_min_stop_headroom", "Min bid above stop", "Stage 3 buys only if the best bid is at least this far above the stop-loss level that applies right now (default 0.01). 0 = off.", num(0.0, 1.0)),
        SettingField("stage3_block_ask", "Block market at ask", "If either side's best ask touches this decimal during the stage 3 window, stage 3 buys nothing more in that market (even if the ask falls back). Only that market and only stage 3; existing sells are not affected. Default 0.99; above that the block is off.", price),
        SettingField("stage3_min_price_gap_usd", "Min price gap", "Same price-to-beat rule for stage 3 buys. 0 = off.", num(0.0, 1_000_000.0, suffix = "USD")),
        SettingField("stage3_stop_loss_pct", "Stop-loss %", "Market sell once the best bid is this many percent below the average buy. 0 = off.", num(0.0, 99.0, suffix = "%")),
        SettingField("stage3_mid_seconds", "Mid tier from (seconds left)", "With this many seconds or fewer left (and more than the last-seconds mark), stage 3 shares use the mid stop-loss %. 0 = off.", num(0.0, 300.0, true, "s")),
        SettingField("stage3_mid_stop_loss_pct", "Mid tier stop-loss %", "Replaces the normal stop-loss during the mid tier. 0 = this tier is skipped.", num(0.0, 99.0, suffix = "%")),
        SettingField("stage3_last_seconds", "Last tier from (seconds left)", "With this many seconds or fewer left, stage 3 shares use the last-seconds stop-loss %. 0 = off.", num(0.0, 300.0, true, "s")),
        SettingField("stage3_last_seconds_stop_loss_pct", "Last tier stop-loss %", "Tightest stop-loss, used in the last seconds before expiry. 0 = this tier is skipped.", num(0.0, 99.0, suffix = "%")),

        SettingField("trade_amount_usdc", "Order notional", "USDC spent per marketable buy.", num(0.01, 100_000.0, suffix = "USDC")),
        SettingField("max_execution_price", "Max execution price", "Hard ceiling for the live ask. At least the trigger and the stage 2/3 triggers.", num(0.01, 1.0)),
        SettingField("max_daily_loss", "Daily loss stop", "Risk halt once the day's PnL falls to this loss.", num(0.0, null, suffix = "USDC")),
        SettingField("daily_profit_target", "Daily profit target", "Risk halt once the day's PnL reaches this profit.", num(0.0, null, suffix = "USDC")),
        SettingField("max_trades_per_day", "Max trades / day", "Hard cap on submitted entries per day.", num(1.0, 10_000.0, true)),
        SettingField("max_open_positions", "Max open positions", "Maximum concurrent open positions in the active mode.", num(0.0, 100.0, true)),
        SettingField("stale_data_max_age_ms", "Stale data cap", "Quotes older than this are rejected before an order is built.", num(100.0, 60_000.0, true, "ms")),

        SettingField("paper_slippage_bps", "Paper slippage", "Simulated adverse slippage applied to walked ask levels.", num(0.0, 5_000.0, suffix = "bps")),
        SettingField("paper_fee_rate", "Paper fee rate", "Fee rate used in paper mode.", num(0.0, 1.0)),
    )

    private val byKey: Map<String, SettingField> = fields.associateBy { it.key }

    fun field(key: String): SettingField? = byKey[key]

    /** Keys of every field shown in [section], in display order. */
    fun keysOf(section: SettingSection): List<String> = section.groups.flatMap { it.keys }

    /** The section a field lives in, or null for an unknown key. */
    fun sectionOfKey(key: String): SettingSection? = sections.firstOrNull { key in keysOf(it) }

    /** How many of the [dirty] keys belong to [section]. */
    fun dirtyIn(section: SettingSection, dirty: Collection<String>): Int {
        val keys = keysOf(section).toSet()
        return dirty.count { it in keys }
    }

    /** The on/off switch of a stage section (`stage0_enabled` ...), or null for sections that have none. */
    fun enabledKey(section: SettingSection): String? = "${section.id}_enabled".takeIf { it in byKey }

    /**
     * Every setting lives on the page of what it belongs to: a stage page holds that stage's timing, entry rules,
     * take-profit and stop-loss; Risk holds the account-wide limits and order policies; Paper the simulation.
     */
    val sections: List<SettingSection> = listOf(
        SettingSection("stage0", "Stage 0", listOf(
            SettingGroup("Timing", "Stage 0 buys between these two marks, counted in seconds left on the market.",
                listOf("stage0_enabled", "stage0_start_seconds", "stage0_end_seconds")),
            SettingGroup("Entry band", "Ask range and BTC gap in which stage 0 may buy.",
                listOf("stage0_min_ask", "stage0_early_max_ask", "stage0_high_buy_price", "stage0_max_ask", "stage0_block_ask", "stage0_high_buy_after_pct", "stage0_min_price_gap_usd", "stage0_high_min_price_gap_usd")),
            SettingGroup("Take-profit tiers", "The buy price decides the tier. 0 % means hold to settlement.",
                listOf("stage0_mid_from_ask", "stage0_high_from_ask", "stage0_take_profit_pct_low", "stage0_take_profit_pct_mid", "stage0_take_profit_pct_high", "stage0_sell_cap_price")),
            SettingGroup("Stop-loss", "Market sell once the best bid is this many percent below the average buy. 0 = off.",
                listOf("stage0_stop_loss_pct", "stage0_high_buy_stop_loss_pct")),
        )),
        SettingSection("stage1", "Stage 1", listOf(
            SettingGroup("Timing", "Stage 1 starts here and ends where the next switched-on stage starts.",
                listOf("stage1_enabled", "stage1_seconds")),
            SettingGroup("Entry", "Best ask at or above the trigger fires a marketable BUY. BTC gap 0 turns the filter off.",
                listOf("stage1_threshold", "stage1_max_ask", "stage1_block_ask", "min_price_gap_usd")),
            SettingGroup("Take-profit", null,
                listOf("stage1_sell_enabled", "stage1_sell_max_buy", "stage1_take_profit_pct", "stage1_sell_price_high")),
            SettingGroup("Stop-loss", "Market sell once the best bid is this many percent below the average buy. 0 = off.",
                listOf("stage1_stop_loss_pct")),
        )),
        SettingSection("stage2", "Stage 2", listOf(
            SettingGroup("Timing", "Stage 2 starts here and ends where stage 3 starts.",
                listOf("stage2_enabled", "stage2_seconds")),
            SettingGroup("Entry", "Best ask at or above the trigger fires a marketable BUY. BTC gap 0 turns the filter off.",
                listOf("stage2_threshold", "stage2_max_ask", "stage2_max_spread", "stage2_block_ask", "stage2_min_price_gap_usd")),
            SettingGroup("Take-profit", null,
                listOf("stage2_sell_enabled", "stage2_sell_price")),
            SettingGroup("Stop-loss", "Market sell once the best bid is this many percent below the average buy. 0 = off.",
                listOf("stage2_stop_loss_pct")),
        )),
        SettingSection("stage3", "Stage 3", listOf(
            SettingGroup("Timing", "Stage 3 starts here and runs to the end of the market.",
                listOf("stage3_enabled", "stage3_seconds")),
            SettingGroup("Entry", "Best ask at or above the trigger fires a marketable BUY. BTC gap 0 turns the filter off.",
                listOf("stage3_threshold", "stage3_max_ask", "stage3_max_spread", "stage3_min_stop_headroom", "stage3_block_ask", "stage3_min_price_gap_usd")),
            SettingGroup("Stop-loss", "Stage 3 has no take-profit. The stop-loss tightens as the market ends: normal, then mid tier, then last tier. A tier set to 0 is skipped.",
                listOf("stage3_stop_loss_pct", "stage3_mid_seconds", "stage3_mid_stop_loss_pct", "stage3_last_seconds", "stage3_last_seconds_stop_loss_pct")),
        )),
        SettingSection("risk", "Risk and sizing", listOf(
            SettingGroup("Sizing", null, listOf("trade_amount_usdc", "max_execution_price")),
            SettingGroup("Daily limits", null, listOf("max_daily_loss", "daily_profit_target", "max_trades_per_day", "max_open_positions")),
            SettingGroup("Order handling", "Applies to every stage.", listOf("duplicate_policy", "conflict_policy")),
            SettingGroup("Data quality", null, listOf("stale_data_max_age_ms")),
        )),
        SettingSection("paper", "Paper", listOf(
            SettingGroup("Paper fill model", null, listOf("paper_slippage_bps", "paper_fee_rate")),
        )),
    )

    /**
     * How a long page is split into tabs, so one screen shows a handful of fields instead of a long scroll.
     * Pairs of tab label and the titles of the groups on that tab. Sections not listed here are one page.
     */
    private val pageLayout: Map<String, List<Pair<String, List<String>>>> = mapOf(
        "stage0" to listOf(
            "Timing" to listOf("Timing"),
            "Entry" to listOf("Entry band"),
            "Exits" to listOf("Take-profit tiers", "Stop-loss"),
        ),
        "stage1" to listOf("Entry" to listOf("Timing", "Entry"), "Exits" to listOf("Take-profit", "Stop-loss")),
        "stage2" to listOf("Entry" to listOf("Timing", "Entry"), "Exits" to listOf("Take-profit", "Stop-loss")),
        "stage3" to listOf("Entry" to listOf("Timing", "Entry"), "Exits" to listOf("Take-profit", "Stop-loss")),
    )

    /** The tabs of [section]: one page for a short section, several for a stage. Every group is on exactly one page. */
    fun pagesOf(section: SettingSection): List<SettingPage> {
        val layout = pageLayout[section.id] ?: return listOf(SettingPage(section.title, section.groups))
        return layout.map { (label, titles) -> SettingPage(label, section.groups.filter { it.title in titles }) }
            .filter { it.groups.isNotEmpty() }
    }

    /** Keys of every field on [page], in display order. */
    fun keysOf(page: SettingPage): List<String> = page.groups.flatMap { it.keys }

    /** "Stage 2: Ask trigger": the field label with its section, so a list of edits stays unambiguous. */
    fun displayName(key: String): String {
        val field = byKey[key] ?: return key
        val section = sectionOfKey(key) ?: return field.label
        return "${section.title}: ${field.label}"
    }
}

/** Text of a settings value as shown in an input: whole numbers without ".0", booleans as true/false. */
fun settingText(value: JsonElement?): String {
    val p = value as? JsonPrimitive ?: return ""
    if (p.isString) return p.content
    p.booleanOrNull?.let { return it.toString() }
    p.longOrNull?.let { return it.toString() }
    p.doubleOrNull?.let { d -> return if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString() }
    return p.content
}

typealias SettingsDraft = Map<String, String>

object SettingsLogic {
    /** The draft the form starts from: one text per known field, taken from the server's settings. */
    fun draftFrom(saved: Map<String, JsonElement>): SettingsDraft =
        SettingsSpec.fields.associate { it.key to settingText(saved[it.key]) }

    private fun sameNumber(a: String, b: String): Boolean {
        val x = a.trim().toDoubleOrNull()
        val y = b.trim().toDoubleOrNull()
        return x != null && y != null && x == y
    }

    fun isDirty(field: SettingField, draft: SettingsDraft, saved: Map<String, JsonElement>): Boolean {
        val raw = draft[field.key].orEmpty()
        val old = settingText(saved[field.key])
        return when (field.kind) {
            is SettingKind.Number -> raw.isBlank() || !sameNumber(raw, old)
            else -> raw != old
        }
    }

    fun dirtyKeys(draft: SettingsDraft, saved: Map<String, JsonElement>): List<String> =
        SettingsSpec.fields.filter { isDirty(it, draft, saved) }.map { it.key }

    private val crossRules: List<Triple<List<String>, (Map<String, Double>) -> Boolean, String>> = listOf(
        Triple(listOf("stage0_end_seconds", "stage0_start_seconds"),
            { d -> d.getValue("stage0_end_seconds") >= d.getValue("stage0_start_seconds") },
            "Stage 0 end time must be lower than its start time."),
        Triple(listOf("stage0_min_ask", "stage0_max_ask"),
            { d -> d.getValue("stage0_min_ask") > d.getValue("stage0_max_ask") },
            "Min ask must not be higher than the stage 0 max ask."),
        Triple(listOf("stage0_min_ask", "stage0_early_max_ask"),
            { d -> d.getValue("stage0_min_ask") > d.getValue("stage0_early_max_ask") },
            "Min ask must not be higher than the stage 0 earlier-buy max ask."),
        Triple(listOf("stage0_early_max_ask", "stage0_max_ask"),
            { d -> d.getValue("stage0_early_max_ask") > d.getValue("stage0_max_ask") },
            "The earlier-buy max ask must not be higher than the expensive-buy max ask."),
        Triple(listOf("stage0_mid_from_ask", "stage0_high_from_ask"),
            { d -> d.getValue("stage0_mid_from_ask") > d.getValue("stage0_high_from_ask") },
            "Mid tier border must not be higher than the high tier border."),
        Triple(listOf("stage1_sell_price_high", "stage1_sell_max_buy"),
            { d -> d.getValue("stage1_sell_price_high") <= d.getValue("stage1_sell_max_buy") },
            "High sell price must be higher than the stage 1 cheap-buy limit."),
        Triple(listOf("stage2_sell_price", "stage2_threshold"),
            { d -> d.getValue("stage2_sell_price") <= d.getValue("stage2_threshold") },
            "Sell price must be higher than the stage 2 ask trigger."),
        Triple(listOf("stage2_threshold", "stage2_block_ask"),
            { d -> d.getValue("stage2_block_ask") < d.getValue("stage2_threshold") },
            "The stage 2 block ask must not be lower than the stage 2 ask trigger, or stage 2 could never buy."),
        Triple(listOf("stage3_threshold", "stage3_block_ask"),
            { d -> d.getValue("stage3_block_ask") < d.getValue("stage3_threshold") },
            "The stage 3 block ask must not be lower than the stage 3 ask trigger, or stage 3 could never buy."),
        Triple(listOf("stage1_threshold", "stage1_block_ask"),
            { d -> d.getValue("stage1_block_ask") < d.getValue("stage1_threshold") },
            "The stage 1 block ask must not be lower than the stage 1 ask trigger, or stage 1 could never buy."),
        Triple(listOf("stage1_max_ask", "stage1_threshold"),
            { d -> d.getValue("stage1_max_ask") < d.getValue("stage1_threshold") },
            "The stage 1 max ask must not be lower than the stage 1 ask trigger, or stage 1 could never buy."),
        Triple(listOf("stage2_max_ask", "stage2_threshold"),
            { d -> d.getValue("stage2_max_ask") < d.getValue("stage2_threshold") },
            "The stage 2 max ask must not be lower than the stage 2 ask trigger, or stage 2 could never buy."),
        Triple(listOf("stage3_max_ask", "stage3_threshold"),
            { d -> d.getValue("stage3_max_ask") < d.getValue("stage3_threshold") },
            "The stage 3 max ask must not be lower than the stage 3 ask trigger, or stage 3 could never buy."),
        Triple(listOf("max_execution_price", "stage1_threshold", "stage2_threshold", "stage3_threshold"),
            { d -> d.getValue("max_execution_price") < maxOf(d.getValue("stage1_threshold"), d.getValue("stage2_threshold"), d.getValue("stage3_threshold")) },
            "Must be at least the stage 1, 2 and 3 ask triggers."),
    )

    /**
     * Problems per field key. Range errors are only reported for fields that were edited, so a stored value
     * outside its range never blocks an unrelated save. A cross-field rule is reported on its first key when
     * any field it involves was edited.
     */
    fun validate(draft: SettingsDraft, saved: Map<String, JsonElement>): Map<String, String> {
        val errors = LinkedHashMap<String, String>()
        val dirty = dirtyKeys(draft, saved).toSet()
        for (field in SettingsSpec.fields) {
            val kind = field.kind as? SettingKind.Number ?: continue
            if (field.key !in dirty) continue
            val raw = draft[field.key].orEmpty().trim()
            val value = raw.toDoubleOrNull()
            if (value == null || value.isNaN() || value.isInfinite()) {
                errors[field.key] = "Enter a valid number."
                continue
            }
            val min = kind.min
            val max = kind.max
            when {
                kind.integer && value != Math.floor(value) -> errors[field.key] = "Whole numbers only."
                min != null && value < min -> errors[field.key] = "Must be at least ${trim(min)}."
                max != null && value > max -> errors[field.key] = "Must be at most ${trim(max)}."
            }
        }
        for ((keys, broken, message) in crossRules) {
            if (keys.none { it in dirty }) continue
            val numbers = keys.associateWith { draft[it]?.trim()?.toDoubleOrNull() }
            if (numbers.values.any { it == null }) continue
            if (broken(numbers.mapValues { it.value!! }) && keys.first() !in errors) errors[keys.first()] = message
        }
        for (field in SettingsSpec.fields) {
            val kind = field.kind as? SettingKind.Choice ?: continue
            if (field.key in dirty && kind.options.none { it.first == draft[field.key] }) errors[field.key] = "Pick one of the options."
        }
        return errors
    }

    /** Only the changed keys, typed for the PATCH body: toggles as booleans, whole-number fields as integers. */
    fun diff(draft: SettingsDraft, saved: Map<String, JsonElement>): Map<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        for (field in SettingsSpec.fields) {
            if (!isDirty(field, draft, saved)) continue
            val raw = draft[field.key].orEmpty().trim()
            out[field.key] = when (val kind = field.kind) {
                SettingKind.Toggle -> JsonPrimitive(raw == "true")
                is SettingKind.Choice -> JsonPrimitive(raw)
                is SettingKind.Number -> {
                    val d = raw.toDouble()
                    if (kind.integer) JsonPrimitive(d.toLong()) else JsonPrimitive(d)
                }
            }
        }
        return out
    }

    private fun trim(v: Double): String = if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
}

/** One short fact on a stage card, e.g. "Window" to "90s → 50s left". */
data class StageFact(val label: String, val value: String)

/**
 * The key numbers of a stage, read from the draft so they follow the person's edits while they type. Used for
 * the stage rows on the Settings home and the header of a stage page. Mirrors the backend window rules
 * (RuntimeSettings.stage_end_seconds): stages 1 and 2 end where the next switched-on stage starts, stage 3 runs
 * to the end of the market.
 */
object StageFacts {
    /** 0 to 3 for the sections `stage0` to `stage3`, null for every other section. */
    fun stageOf(section: SettingSection): Int? =
        section.id.removePrefix("stage").takeIf { it != section.id }?.toIntOrNull()?.takeIf { it in 0..3 }

    private fun num(draft: SettingsDraft, key: String): Double? = draft[key]?.trim()?.toDoubleOrNull()

    private fun fmt(v: Double?): String = when {
        v == null -> "?"
        v == Math.floor(v) && Math.abs(v) < 1e15 -> v.toLong().toString()
        else -> v.toString()
    }

    private fun pct(draft: SettingsDraft, key: String): String {
        val v = num(draft, key) ?: return "?"
        return if (v <= 0.0) "Off" else "${fmt(v)}%"
    }

    fun enabled(stage: Int, draft: SettingsDraft): Boolean = draft["stage${stage}_enabled"] == "true"

    private fun startSeconds(stage: Int, draft: SettingsDraft): Double? =
        num(draft, if (stage == 0) "stage0_start_seconds" else "stage${stage}_seconds")

    private fun endSeconds(stage: Int, draft: SettingsDraft): Double? {
        if (stage == 0) return num(draft, "stage0_end_seconds")
        for (next in stage + 1..3) if (enabled(next, draft)) return startSeconds(next, draft)
        return 0.0
    }

    /** "90s → 50s left", or "90s → expiry" when the stage runs to the end of the market. */
    fun window(stage: Int, draft: SettingsDraft): String {
        val end = endSeconds(stage, draft)
        val to = if (end == 0.0) "expiry" else "${fmt(end)}s left"
        return "${fmt(startSeconds(stage, draft))}s → $to"
    }

    fun facts(stage: Int, draft: SettingsDraft): List<StageFact> {
        val window = StageFact("Window", window(stage, draft))
        return when (stage) {
            0 -> listOf(
                window,
                StageFact("Ask", "${fmt(num(draft, "stage0_min_ask"))} – ${fmt(num(draft, "stage0_max_ask"))}"),
                StageFact("Take-profit", listOf("low", "mid", "high").joinToString(" / ") { pct(draft, "stage0_take_profit_pct_$it").removeSuffix("%") } + "%"),
                StageFact("Stop-loss", pct(draft, "stage0_stop_loss_pct")),
            )
            1 -> listOf(
                window,
                StageFact("Ask", "≥ ${fmt(num(draft, "stage1_threshold"))}"),
                StageFact("Take-profit", if (draft["stage1_sell_enabled"] == "true") "+${pct(draft, "stage1_take_profit_pct")}" else "Off"),
                StageFact("Stop-loss", pct(draft, "stage1_stop_loss_pct")),
            )
            2 -> listOf(
                window,
                StageFact("Ask", "≥ ${fmt(num(draft, "stage2_threshold"))}"),
                StageFact("Take-profit", if (draft["stage2_sell_enabled"] == "true") "Bid ${fmt(num(draft, "stage2_sell_price"))}" else "Off"),
                StageFact("Stop-loss", pct(draft, "stage2_stop_loss_pct")),
            )
            else -> listOf(
                window,
                StageFact("Ask", "≥ ${fmt(num(draft, "stage3_threshold"))}"),
                StageFact("Take-profit", "None"),
                StageFact("Stop-loss", pct(draft, "stage3_stop_loss_pct")),
            )
        }
    }
}
