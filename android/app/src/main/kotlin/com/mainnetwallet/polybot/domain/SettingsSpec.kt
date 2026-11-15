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

object SettingsSpec {
    private fun num(min: Double? = null, max: Double? = null, integer: Boolean = false, suffix: String? = null) =
        SettingKind.Number(min, max, integer, suffix)

    private val price = num(0.01, 0.999)

    val fields: List<SettingField> = listOf(
        SettingField("trigger_threshold", "Trigger threshold", "Ask at or above this decimal fires a marketable BUY. Never used as a limit price.", price),
        SettingField("final_window_seconds", "Final window", "Seconds before expiry in which trading is enabled.", num(1.0, 300.0, true, "s")),
        SettingField("duplicate_policy", "Duplicate policy", "Controls repeat entries for the same market or side.", SettingKind.Choice(listOf(
            "one_per_market" to "One order per market", "one_per_side" to "One per side", "allow" to "Allow duplicates"))),
        SettingField("conflict_policy", "Conflict policy", "Decision when UP and DOWN both qualify.", SettingKind.Choice(listOf(
            "up_first" to "UP first", "down_first" to "DOWN first", "highest_ask" to "Highest ask", "block_both" to "Block both"))),

        SettingField("stage0_enabled", "Stage 0: early buy + sell", "On: before the final window, buy when the ask is low enough and BTC is far enough beyond the price to beat.", SettingKind.Toggle),
        SettingField("stage0_start_seconds", "Starts at (seconds left)", "Stage 0 may start buying when this many seconds are left.", num(1.0, 300.0, true, "s")),
        SettingField("stage0_end_seconds", "Ends at (seconds left)", "Stage 0 stops buying when this many seconds are left.", num(0.0, 299.0, true, "s")),
        SettingField("stage0_min_ask", "Min ask", "Buys only while the best ask is at or above this decimal.", price),
        SettingField("stage0_max_ask", "Max ask", "Buys only while the best ask is at or below this decimal; also the highest price it pays.", price),
        SettingField("stage0_high_buy_after_pct", "Expensive buys start after", "PERCENT of the stage 0 window (not seconds) that must pass before buys at the expensive-buy price or more. 0 = whole window, 100 = never.", num(0.0, 100.0, suffix = "%")),
        SettingField("stage0_min_price_gap_usd", "Min price gap", "BTC must already be this many USD past the price to beat. 0 = filter off.", num(0.0, 1_000_000.0, suffix = "USD")),
        SettingField("stage0_mid_from_ask", "Mid tier from (buy price)", "Shares bought below this price use the low take-profit %.", price),
        SettingField("stage0_high_from_ask", "High tier from (buy price)", "Shares bought at or above this price use the high take-profit %.", price),
        SettingField("stage0_take_profit_pct_low", "Take-profit % (low tier)", "Percent above the buy price. 0 = held to settlement.", num(0.0, 500.0, suffix = "%")),
        SettingField("stage0_take_profit_pct_mid", "Take-profit % (mid tier)", "Percent above the buy price. 0 = held to settlement.", num(0.0, 500.0, suffix = "%")),
        SettingField("stage0_take_profit_pct_high", "Take-profit % (high tier)", "Percent above the buy price, sell price capped at 0.99. 0 = held to settlement.", num(0.0, 500.0, suffix = "%")),
        SettingField("stage0_stop_loss_pct", "Stop-loss %", "Market sell once the best bid is this many percent below the average buy. 0 = off.", num(0.0, 99.0, suffix = "%")),
        SettingField("stage0_high_buy_price", "Expensive buy from (timing + stop-loss)", "From this price a stage 0 buy counts as expensive: later entry and its own stop-loss.", price),
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
        SettingField("stage2_min_price_gap_usd", "Min price gap", "Same price-to-beat rule for stage 2 buys. 0 = off.", num(0.0, 1_000_000.0, suffix = "USD")),
        SettingField("stage2_sell_enabled", "Take-profit sell", "On: stage 2 shares are sold when the best bid reaches the sell price.", SettingKind.Toggle),
        SettingField("stage2_sell_price", "Sell at", "Must be higher than the stage 2 ask trigger.", num(0.02, 0.999)),
        SettingField("stage2_stop_loss_pct", "Stop-loss %", "Market sell once the best bid is this many percent below the average buy. 0 = off.", num(0.0, 99.0, suffix = "%")),

        SettingField("stage3_enabled", "Stage 3: on / off", "Off: no buys and no stop-loss for stage 3. Stage 3 has no take-profit.", SettingKind.Toggle),
        SettingField("stage3_seconds", "Seconds left", "From this many seconds before expiry stage 3 may buy. Stage 1 stops buying at this mark.", num(1.0, 300.0, true, "s")),
        SettingField("stage3_threshold", "Ask trigger", "Stage 3 buys when a best ask is at or above this decimal.", price),
        SettingField("stage3_min_price_gap_usd", "Min price gap", "Same price-to-beat rule for stage 3 buys. 0 = off.", num(0.0, 1_000_000.0, suffix = "USD")),
        SettingField("stage3_stop_loss_pct", "Stop-loss %", "Market sell once the best bid is this many percent below the average buy. 0 = off.", num(0.0, 99.0, suffix = "%")),

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

    val sections: List<SettingSection> = listOf(
        SettingSection("entry", "Entry", listOf(
            SettingGroup("Final-window entry", "When the final-window stages fire a marketable BUY.",
                listOf("final_window_seconds", "trigger_threshold")),
            SettingGroup("Order handling", null, listOf("duplicate_policy", "conflict_policy")),
        )),
        SettingSection("stage0", "Stage 0", listOf(
            SettingGroup("Activation", null, listOf("stage0_enabled", "stage0_start_seconds", "stage0_end_seconds")),
            SettingGroup("Entry band", "Ask range and BTC gap in which stage 0 may buy.",
                listOf("stage0_min_ask", "stage0_max_ask", "stage0_high_buy_after_pct", "stage0_min_price_gap_usd")),
            SettingGroup("Take-profit tiers", "The buy price decides the tier. 0 % means hold to settlement.",
                listOf("stage0_mid_from_ask", "stage0_high_from_ask", "stage0_take_profit_pct_low", "stage0_take_profit_pct_mid", "stage0_take_profit_pct_high")),
            SettingGroup("Stop-loss", null, listOf("stage0_stop_loss_pct", "stage0_high_buy_price", "stage0_high_buy_stop_loss_pct")),
        )),
        SettingSection("stage1", "Stage 1", listOf(
            SettingGroup("Entry", null, listOf("stage1_enabled", "min_price_gap_usd")),
            SettingGroup("Exit", null, listOf("stage1_sell_enabled", "stage1_sell_max_buy", "stage1_take_profit_pct", "stage1_sell_price_high", "stage1_stop_loss_pct")),
        )),
        SettingSection("stage2", "Stage 2", listOf(
            SettingGroup("Entry", null, listOf("stage2_enabled", "stage2_seconds", "stage2_threshold", "stage2_min_price_gap_usd")),
            SettingGroup("Exit", null, listOf("stage2_sell_enabled", "stage2_sell_price", "stage2_stop_loss_pct")),
        )),
        SettingSection("stage3", "Stage 3", listOf(
            SettingGroup("Entry", null, listOf("stage3_enabled", "stage3_seconds", "stage3_threshold", "stage3_min_price_gap_usd")),
            SettingGroup("Exit", "Stage 3 has no take-profit.", listOf("stage3_stop_loss_pct")),
        )),
        SettingSection("risk", "Risk", listOf(
            SettingGroup("Sizing", null, listOf("trade_amount_usdc", "max_execution_price")),
            SettingGroup("Daily limits", null, listOf("max_daily_loss", "daily_profit_target", "max_trades_per_day", "max_open_positions")),
            SettingGroup("Data quality", null, listOf("stale_data_max_age_ms")),
        )),
        SettingSection("paper", "Paper", listOf(
            SettingGroup("Paper fill model", null, listOf("paper_slippage_bps", "paper_fee_rate")),
        )),
    )
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
        Triple(listOf("stage0_mid_from_ask", "stage0_high_from_ask"),
            { d -> d.getValue("stage0_mid_from_ask") > d.getValue("stage0_high_from_ask") },
            "Mid tier border must not be higher than the high tier border."),
        Triple(listOf("stage1_sell_price_high", "stage1_sell_max_buy"),
            { d -> d.getValue("stage1_sell_price_high") <= d.getValue("stage1_sell_max_buy") },
            "High sell price must be higher than the stage 1 cheap-buy limit."),
        Triple(listOf("stage2_sell_price", "stage2_threshold"),
            { d -> d.getValue("stage2_sell_price") <= d.getValue("stage2_threshold") },
            "Sell price must be higher than the stage 2 ask trigger."),
        Triple(listOf("max_execution_price", "trigger_threshold", "stage2_threshold", "stage3_threshold"),
            { d -> d.getValue("max_execution_price") < maxOf(d.getValue("trigger_threshold"), d.getValue("stage2_threshold"), d.getValue("stage3_threshold")) },
            "Must be at least the trigger and the stage 2/3 ask triggers."),
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
