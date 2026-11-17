package com.mainnetwallet.polybot.ui.strategy

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.mainnetwallet.polybot.ui.components.PolyPullToRefresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mainnetwallet.polybot.data.model.Snapshot
import com.mainnetwallet.polybot.data.model.StrategyState
import com.mainnetwallet.polybot.data.model.TriggerRow
import com.mainnetwallet.polybot.domain.Tone
import com.mainnetwallet.polybot.domain.statusTone
import com.mainnetwallet.polybot.ui.Fmt
import com.mainnetwallet.polybot.ui.LocalGraph
import com.mainnetwallet.polybot.ui.components.EmptyState
import com.mainnetwallet.polybot.ui.components.KeyValueRow
import com.mainnetwallet.polybot.ui.components.PolyCard
import com.mainnetwallet.polybot.ui.components.ProCard
import com.mainnetwallet.polybot.ui.components.ProSectionHeader
import com.mainnetwallet.polybot.ui.components.SidePill
import com.mainnetwallet.polybot.ui.components.SkeletonCard
import com.mainnetwallet.polybot.ui.collectSelectedAsState
import com.mainnetwallet.polybot.ui.components.ToneChip
import com.mainnetwallet.polybot.ui.graphViewModel
import com.mainnetwallet.polybot.ui.theme.Mono
import com.mainnetwallet.polybot.ui.theme.Poly
import com.mainnetwallet.polybot.ui.theme.Space
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Which trigger decisions the list shows. */
private enum class TriggerFilter(val label: String) {
    All("All"),
    Filled("Filled"),
    Skipped("Skipped"),
    Blocked("Blocked");

    fun matches(t: TriggerRow): Boolean = when (this) {
        All -> true
        Filled -> statusTone(t.decision) == Tone.Up
        Skipped -> statusTone(t.decision) == Tone.Warn
        Blocked -> statusTone(t.decision) == Tone.Down
    }
}

/**
 * Strategy tab: a timeline of when each stage trades, expandable stage cards with every parameter, the
 * conflict/duplicate rules and the recent trigger events.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StrategyScreen(modifier: Modifier = Modifier) {
    val vm: StrategyViewModel = graphViewModel()
    val repo = LocalGraph.current.repository
    val state by vm.state.collectAsStateWithLifecycle()
    // This tab shows settings, not prices: it reads a view without the per-tick fields (asks, armed flags, reason,
    // fractional countdown), so it is not rebuilt several times a second while the user scrolls.
    val view by repo.snapshot.collectSelectedAsState { it?.toStrategyView() }
    val remainingNow by repo.snapshot.collectSelectedAsState { s ->
        // Whole seconds, rounded up: for whole-second stage limits "remaining <= limit" gives the same answer.
        (s?.strategy?.remainingSeconds ?: s?.market?.remainingSeconds)?.let { ceil(it) }
    }
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var filterIndex by rememberSaveable { mutableStateOf(0) }
    val snap = view

    PolyPullToRefresh(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            scope.launch {
                repo.refresh()
                vm.reload()
                refreshing = false
            }
        },
        modifier = modifier.fillMaxSize(),
    ) {
        if (snap == null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(Space.lg),
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                SkeletonCard(lines = 4)
                SkeletonCard(lines = 4)
                SkeletonCard(lines = 3)
            }
        } else {
            val remaining = remainingNow
            val stages = stageSpecs(snap, remaining)
            val filter = TriggerFilter.entries.getOrElse(filterIndex) { TriggerFilter.All }
            val shown = state.triggers.filter { filter.matches(it) }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Space.md),
                verticalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                item(key = "timeline") { TimelineCard(snap, remaining) }
                item(key = "stages-header") {
                    ProSectionHeader("Stages", subtitle = "Tap a stage to see all of its parameters")
                }
                items(stages, key = { "stage-" + it.index }) { StageCard(it) }
                if (snap.strategy.conflictPolicy.isNotBlank() || snap.strategy.duplicatePolicy.isNotBlank()) {
                    item(key = "rules") { RulesCard(snap.strategy) }
                }
                item(key = "triggers-header") {
                    ProSectionHeader(
                        "Recent triggers",
                        subtitle = "Latest $MAX_TRIGGERS asks that reached a trigger, including blocked and skipped",
                    )
                }
                item(key = "trigger-filters") {
                    TriggerFilters(
                        selected = filterIndex,
                        counts = triggerCounts(state.triggers),
                        onSelect = { filterIndex = it },
                    )
                }
                if (state.error != null && state.triggers.isEmpty()) {
                    item(key = "trigger-error") { ErrorNote(state.error.orEmpty()) }
                }
                when {
                    state.loading -> item(key = "trigger-loading") { SkeletonCard(lines = 3) }
                    shown.isEmpty() -> item(key = "trigger-empty") {
                        PolyCard {
                            if (state.triggers.isEmpty()) {
                                EmptyState(
                                    "No trigger events yet",
                                    "A row is written when an ask reaches a trigger, including blocked and skipped decisions.",
                                    icon = Icons.Filled.TrackChanges,
                                )
                            } else {
                                EmptyState(
                                    "No ${filter.label.lowercase()} triggers",
                                    "Pick another filter to see the other decisions.",
                                    icon = Icons.Filled.TrackChanges,
                                )
                            }
                        }
                    }
                    else -> items(shown, key = { "trigger-" + it.id }) { TriggerCard(it) }
                }
            }
        }
    }
}

/** What the Strategy tab needs from a snapshot: the stage settings, without the values that change on every tick. */
private data class StrategyView(
    val strategy: StrategyState,
    val durationSeconds: Double?,
    val config: Map<String, JsonElement>,
)

private fun Snapshot.toStrategyView() = StrategyView(
    strategy = strategy.copy(
        inWindow = false,
        remainingSeconds = null,
        upAsk = null,
        downAsk = null,
        upArmed = false,
        downArmed = false,
        reason = "",
    ),
    durationSeconds = market?.durationSeconds,
    config = config,
)

private fun cfg(snap: StrategyView, key: String): Double? = (snap.config[key] as? JsonPrimitive)?.doubleOrNull

private fun secs(v: Double): String = Fmt.plain(v) + "s"

private fun pct(v: Double): String = if (v <= 0.0) "off" else Fmt.plain(v) + "%"

private fun usdGap(v: Double?): String = if (v == null) "—" else if (v <= 0.0) "off" else "$" + Fmt.plain(v)

/** "60–94¢" from two 0..1 prices. */
private fun centsRange(low: Double, high: Double): String =
    Fmt.centsShort(low).removeSuffix("¢") + "–" + Fmt.centsShort(high)

private fun triggerCounts(rows: List<TriggerRow>): List<Int> = TriggerFilter.entries.map { f -> rows.count { f.matches(it) } }

private fun stageSpecs(snap: StrategyView, remaining: Double?): List<StageSpec> {
    val s = snap.strategy
    val open0 = remaining != null && remaining <= s.stage0StartSeconds && remaining > s.stage0EndSeconds
    val open1 = remaining != null && remaining <= s.stage1Seconds
    val open2 = remaining != null && remaining <= s.stage2Seconds
    val open3 = remaining != null && remaining <= s.stage3Seconds
    val tierValues = listOf(s.stage0TakeProfitPctLow, s.stage0TakeProfitPctMid, s.stage0TakeProfitPctHigh)
    val tiers0 = if (tierValues.all { it <= 0.0 }) "off" else tierValues.joinToString(" / ") { Fmt.plain(it) } + "%"
    return listOf(
        StageSpec(
            index = 0,
            title = "Early buy",
            window = "${secs(s.stage0StartSeconds)} → ${secs(s.stage0EndSeconds)} left",
            enabled = s.stage0Enabled,
            active = s.stage0Enabled && open0,
            highlights = listOf(
                "Entry ask" to centsRange(s.stage0MinAsk, s.stage0EarlyMaxAsk),
                "Take-profit" to tiers0,
                "Stop-loss" to pct(s.stage0StopLossPct),
            ),
            entry = listOf(
                "Window" to "${secs(s.stage0StartSeconds)} to ${secs(s.stage0EndSeconds)} left",
                "Ask band" to "${Fmt.cents(s.stage0MinAsk)} to ${Fmt.cents(s.stage0EarlyMaxAsk)}",
                "Expensive max ask" to Fmt.cents(s.stage0MaxAsk),
                "Min price gap" to usdGap(s.stage0MinPriceGapUsd),
                "Min price gap (expensive)" to usdGap(s.stage0HighMinPriceGapUsd),
                "Expensive buy from" to "${Fmt.cents(s.stage0HighBuyPrice)} after ${pct(s.stage0HighBuyAfterPct)} of window",
                "Tier borders" to "${Fmt.cents(s.stage0MidFromAsk)} / ${Fmt.cents(s.stage0HighFromAsk)}",
            ),
            exit = listOf(
                "Take-profit low / mid / high" to "${pct(s.stage0TakeProfitPctLow)} / ${pct(s.stage0TakeProfitPctMid)} / ${pct(s.stage0TakeProfitPctHigh)}",
                "Stop-loss" to pct(s.stage0StopLossPct),
                "Expensive-buy stop-loss" to pct(s.stage0HighBuyStopLossPct),
            ),
        ),
        StageSpec(
            index = 1,
            title = "Stage 1",
            window = "last ${secs(s.stage1Seconds)}",
            enabled = s.stage1Enabled,
            active = s.stage1Enabled && open1,
            highlights = listOf(
                "Entry ask" to "≥ " + Fmt.centsShort(s.stage1Threshold),
                "Take-profit" to if (s.stage1SellEnabled) pct(s.stage1TakeProfitPct) else "off",
                "Stop-loss" to pct(s.stage1StopLossPct),
            ),
            entry = listOf(
                "Window" to "last ${secs(s.stage1Seconds)}",
                "Ask trigger" to Fmt.cents(s.stage1Threshold),
                "Min price gap" to usdGap(cfg(snap, "min_price_gap_usd")),
            ),
            exit = listOf(
                "Take-profit sell" to if (s.stage1SellEnabled) "on" else "off",
                "Cheap buy up to" to Fmt.cents(s.stage1SellMaxBuy),
                "Take-profit (cheap buy)" to pct(s.stage1TakeProfitPct),
                "Sell at (dearer buy)" to Fmt.cents(s.stage1SellPriceHigh),
                "Stop-loss" to pct(s.stage1StopLossPct),
            ),
        ),
        StageSpec(
            index = 2,
            title = "Add-on",
            window = "from ${secs(s.stage2Seconds)} left",
            enabled = s.stage2Enabled,
            active = s.stage2Enabled && open2,
            highlights = listOf(
                "Entry ask" to "≥ " + Fmt.centsShort(s.stage2Threshold),
                "Take-profit" to if (s.stage2SellEnabled) "bid " + Fmt.centsShort(s.stage2SellPrice) else "off",
                "Stop-loss" to pct(s.stage2StopLossPct),
            ),
            entry = listOf(
                "From" to "${secs(s.stage2Seconds)} left",
                "Ask trigger" to Fmt.cents(s.stage2Threshold),
                "Min price gap" to usdGap(cfg(snap, "stage2_min_price_gap_usd")),
            ),
            exit = listOf(
                "Take-profit sell" to if (s.stage2SellEnabled) "on at ${Fmt.cents(s.stage2SellPrice)}" else "off",
                "Stop-loss" to pct(s.stage2StopLossPct),
            ),
        ),
        StageSpec(
            index = 3,
            title = "Add-on",
            window = "from ${secs(s.stage3Seconds)} left",
            enabled = s.stage3Enabled,
            active = s.stage3Enabled && open3,
            highlights = listOf(
                "Entry ask" to "≥ " + Fmt.centsShort(s.stage3Threshold),
                "Take-profit" to "hold",
                "Stop-loss" to pct(s.stage3StopLossPct),
            ),
            entry = listOf(
                "From" to "${secs(s.stage3Seconds)} left",
                "Ask trigger" to Fmt.cents(s.stage3Threshold),
                "Min price gap" to usdGap(cfg(snap, "stage3_min_price_gap_usd")),
            ),
            exit = listOf(
                "Take-profit" to "none",
                "Stop-loss" to pct(s.stage3StopLossPct),
            ),
        ),
    )
}

@Composable
private fun TimelineCard(snap: StrategyView, remaining: Double?) {
    val s = snap.strategy
    val duration = snap.durationSeconds
    ProCard(contentPadding = Space.md, spacing = Space.sm) {
        ProSectionHeader("Stage timeline", subtitle = "When each stage is allowed to trade in this market")
        if (duration == null || duration <= 0.0) {
            Text(
                "Waiting for the market timing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val lanes = listOf(
                StageLane(
                    "S0", s.stage0StartSeconds, s.stage0EndSeconds, s.stage0Enabled,
                    s.stage0Enabled && remaining != null && remaining <= s.stage0StartSeconds && remaining > s.stage0EndSeconds,
                ),
                StageLane("S1", s.stage1Seconds, 0.0, s.stage1Enabled, s.stage1Enabled && remaining != null && remaining <= s.stage1Seconds),
                StageLane("S2", s.stage2Seconds, 0.0, s.stage2Enabled, s.stage2Enabled && remaining != null && remaining <= s.stage2Seconds),
                StageLane("S3", s.stage3Seconds, 0.0, s.stage3Enabled, s.stage3Enabled && remaining != null && remaining <= s.stage3Seconds),
            )
            StageTimeline(duration = duration, remaining = remaining, lanes = lanes)
            TimelineLegend()
            val observe = Math.max(0.0, duration - s.stage1Seconds)
            Text(
                "Early buy can trade during the observation phase; the stage 1 starts after ${secs(observe)}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RulesCard(s: StrategyState) {
    ProCard(contentPadding = Space.md, spacing = Space.sm) {
        ProSectionHeader("Rules", subtitle = "What the engine does when signals collide")
        if (s.conflictPolicy.isNotBlank()) KeyValueRow("Conflict policy", s.conflictPolicy.replace('_', ' '))
        if (s.duplicatePolicy.isNotBlank()) KeyValueRow("Duplicate policy", s.duplicatePolicy.replace('_', ' '))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TriggerFilters(selected: Int, counts: List<Int>, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        TriggerFilter.entries.forEachIndexed { index, f ->
            val count = counts.getOrElse(index) { 0 }
            FilterChip(
                selected = index == selected,
                onClick = { onSelect(index) },
                label = { Text(if (count > 0) "${f.label} · $count" else f.label, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun ErrorNote(message: String) {
    val c = Poly.colors
    ProCard(accent = c.down) {
        Text("Could not load trigger events", style = MaterialTheme.typography.titleSmall)
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TriggerCard(t: TriggerRow) {
    val c = Poly.colors
    val tone = statusTone(t.decision)
    val accent = when (tone) {
        Tone.Up -> c.up
        Tone.Warn -> c.warn
        Tone.Down -> c.down
        else -> null
    }
    ProCard(accent = accent, contentPadding = Space.md, spacing = Space.sm) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            SidePill(t.side)
            // The decision text can be long ("blocked max execution price"): it gets the leftover width and wraps.
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                ToneChip(t.decision.replace('_', ' ').ifBlank { "—" }, tone)
            }
            Text(Fmt.time(t.createdAt), style = Mono.small, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            CompactStat("Ask", Fmt.centsShort(t.bestAsk), Modifier.weight(1f))
            CompactStat("Trigger", Fmt.centsShort(t.threshold), Modifier.weight(1f))
            CompactStat("Size", Fmt.num(t.askSize, 0), Modifier.weight(1f))
            CompactStat("Left", "T-" + Fmt.num(t.remainingSeconds, 1) + "s", Modifier.weight(1f))
        }
        if (t.reason.isNotBlank()) {
            Text(
                t.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
