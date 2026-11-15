package com.mainnetwallet.polybot.ui.strategy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.mainnetwallet.polybot.ui.components.Label
import com.mainnetwallet.polybot.ui.components.Pill
import com.mainnetwallet.polybot.ui.components.PolyCard
import com.mainnetwallet.polybot.ui.components.SectionTitle
import com.mainnetwallet.polybot.ui.components.SidePill
import com.mainnetwallet.polybot.ui.components.SkeletonCard
import com.mainnetwallet.polybot.ui.components.ToneChip
import com.mainnetwallet.polybot.ui.graphViewModel
import com.mainnetwallet.polybot.ui.theme.Mono
import com.mainnetwallet.polybot.ui.theme.Poly
import com.mainnetwallet.polybot.ui.theme.Space
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StrategyScreen(modifier: Modifier = Modifier) {
    val vm: StrategyViewModel = graphViewModel()
    val repo = LocalGraph.current.repository
    val state by vm.state.collectAsStateWithLifecycle()
    val snapshot by repo.snapshot.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val snap = snapshot

    PullToRefreshBox(
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
                SkeletonCard(lines = 3)
                SkeletonCard(lines = 4)
            }
        } else {
            val remaining = snap.strategy.remainingSeconds ?: snap.market?.remainingSeconds
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Space.lg),
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                item { EvaluationCard(snap.strategy, remaining) }
                item { PhaseCard(snap) }
                item { SectionTitle("Stages") }
                item { Stage0Card(snap, remaining) }
                item { Stage1Card(snap, remaining) }
                item { Stage2Card(snap, remaining) }
                item { Stage3Card(snap, remaining) }
                item { SectionTitle("Recent triggers") }
                when {
                    state.loading -> item { SkeletonCard(lines = 3) }
                    state.triggers.isEmpty() -> item {
                        PolyCard {
                            EmptyState(
                                "No trigger events yet",
                                "A row is written when an ask reaches a trigger, including blocked and skipped decisions.",
                                icon = Icons.Filled.TrackChanges,
                            )
                        }
                    }
                    else -> items(state.triggers, key = { it.id }) { TriggerCard(it) }
                }
            }
        }
    }
}

private fun cfg(snap: Snapshot, key: String): Double? = (snap.config[key] as? JsonPrimitive)?.doubleOrNull

private fun secs(v: Double): String = Fmt.plain(v) + "s"

private fun pct(v: Double): String = if (v <= 0.0) "off" else Fmt.plain(v) + "%"

private fun usdGap(v: Double?): String = if (v == null) "—" else if (v <= 0.0) "off" else "$" + Fmt.plain(v)

@Composable
private fun EvaluationCard(s: StrategyState, remaining: Double?) {
    val c = Poly.colors
    PolyCard {
        SectionTitle("Live evaluation") {
            if (s.inWindow) Pill("IN WINDOW", c.warn, c.warnContainer)
            else Pill("STANDBY", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
        }
        ArmMeter("UP", s.upAsk, s.triggerThreshold, s.upArmed)
        ArmMeter("DOWN", s.downAsk, s.triggerThreshold, s.downArmed)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                s.reason.replace('_', ' ').ifBlank { "waiting" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(if (remaining == null) "—" else "T-" + Fmt.plain(Math.max(0.0, Math.ceil(remaining))) + "s", style = Mono.body)
        }
    }
}

@Composable
private fun ArmMeter(side: String, ask: Double?, threshold: Double, armed: Boolean) {
    val c = Poly.colors
    val tone = if (side == "UP") c.up else c.down
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            SidePill(side)
            Text(Fmt.cents(ask), style = Mono.medium, color = tone, modifier = Modifier.weight(1f))
            if (armed) {
                Pill("ARMED", c.warn, c.warnContainer)
            } else if (ask != null && threshold > 0.0 && ask < threshold) {
                Text(Fmt.cents(threshold - ask) + " away", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("idle", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LinearProgressIndicator(
            progress = { (ask ?: 0.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = tone,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Text("trigger ${Fmt.cents(threshold)}", style = Mono.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PhaseCard(snap: Snapshot) {
    val c = Poly.colors
    val duration = snap.market?.durationSeconds
    val finalWindow = snap.strategy.finalWindowSeconds
    PolyCard {
        SectionTitle("Trading phases")
        if (duration == null || duration <= 0.0 || finalWindow <= 0.0) {
            Text(
                "Waiting for the market timing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val observe = Math.max(0.0, duration - finalWindow)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(34.dp),
            ) {
                if (observe > 0.0) {
                    Box(
                        Modifier
                            .weight(observe.toFloat())
                            .height(34.dp)
                            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Text("Observe", style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                }
                Box(
                    Modifier
                        .weight(finalWindow.toFloat())
                        .height(34.dp)
                        .background(c.warnContainer, RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text("Trade", style = MaterialTheme.typography.labelMedium, color = c.warn, maxLines = 1) }
            }
            Text(
                "${secs(observe)} observation (no orders), then ${secs(finalWindow)} active.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StageCard(title: String, enabled: Boolean, timeOpen: Boolean, rows: List<Pair<String, String>>) {
    val c = Poly.colors
    PolyCard(borderColor = if (enabled && timeOpen) c.warn else null) {
        SectionTitle(title) {
            if (enabled && timeOpen) {
                Pill("TIME WINDOW OPEN", c.warn, c.warnContainer)
                Box(Modifier.padding(start = 6.dp))
            }
            if (enabled) ToneChip("ON", Tone.Up) else ToneChip("OFF", Tone.Neutral)
        }
        rows.forEach { (key, value) -> KeyValueRow(key, value) }
    }
}

@Composable
private fun Stage0Card(snap: Snapshot, remaining: Double?) {
    val s = snap.strategy
    val open = remaining != null && remaining <= s.stage0StartSeconds && remaining > s.stage0EndSeconds
    StageCard(
        "Stage 0: early buy",
        s.stage0Enabled,
        open,
        listOf(
            "Window" to "${secs(s.stage0StartSeconds)} to ${secs(s.stage0EndSeconds)} left",
            "Ask band" to "${Fmt.cents(s.stage0MinAsk)} to ${Fmt.cents(s.stage0MaxAsk)}",
            "Min price gap" to usdGap(s.stage0MinPriceGapUsd),
            "Expensive buy from" to "${Fmt.cents(s.stage0HighBuyPrice)} after ${pct(s.stage0HighBuyAfterPct)} of window",
            "Tier borders" to "${Fmt.cents(s.stage0MidFromAsk)} / ${Fmt.cents(s.stage0HighFromAsk)}",
            "Take-profit low / mid / high" to "${pct(s.stage0TakeProfitPctLow)} / ${pct(s.stage0TakeProfitPctMid)} / ${pct(s.stage0TakeProfitPctHigh)}",
            "Stop-loss" to pct(s.stage0StopLossPct),
            "Expensive-buy stop-loss" to pct(s.stage0HighBuyStopLossPct),
        ),
    )
}

@Composable
private fun Stage1Card(snap: Snapshot, remaining: Double?) {
    val s = snap.strategy
    val open = remaining != null && remaining <= s.finalWindowSeconds
    StageCard(
        "Stage 1",
        s.stage1Enabled,
        open,
        listOf(
            "Window" to "last ${secs(s.finalWindowSeconds)}",
            "Ask trigger" to Fmt.cents(s.triggerThreshold),
            "Min price gap" to usdGap(cfg(snap, "min_price_gap_usd")),
            "Take-profit sell" to if (s.stage1SellEnabled) "on" else "off",
            "Cheap buy up to" to Fmt.cents(s.stage1SellMaxBuy),
            "Take-profit (cheap buy)" to pct(s.stage1TakeProfitPct),
            "Sell at (dearer buy)" to Fmt.cents(s.stage1SellPriceHigh),
            "Stop-loss" to pct(s.stage1StopLossPct),
        ),
    )
}

@Composable
private fun Stage2Card(snap: Snapshot, remaining: Double?) {
    val s = snap.strategy
    val open = remaining != null && remaining <= s.stage2Seconds
    StageCard(
        "Stage 2",
        s.stage2Enabled,
        open,
        listOf(
            "From" to "${secs(s.stage2Seconds)} left",
            "Ask trigger" to Fmt.cents(s.stage2Threshold),
            "Min price gap" to usdGap(cfg(snap, "stage2_min_price_gap_usd")),
            "Take-profit sell" to if (s.stage2SellEnabled) "on at ${Fmt.cents(s.stage2SellPrice)}" else "off",
            "Stop-loss" to pct(s.stage2StopLossPct),
        ),
    )
}

@Composable
private fun Stage3Card(snap: Snapshot, remaining: Double?) {
    val s = snap.strategy
    val open = remaining != null && remaining <= s.stage3Seconds
    StageCard(
        "Stage 3",
        s.stage3Enabled,
        open,
        listOf(
            "From" to "${secs(s.stage3Seconds)} left",
            "Ask trigger" to Fmt.cents(s.stage3Threshold),
            "Min price gap" to usdGap(cfg(snap, "stage3_min_price_gap_usd")),
            "Take-profit" to "none",
            "Stop-loss" to pct(s.stage3StopLossPct),
        ),
    )
}

@Composable
private fun TriggerCard(t: TriggerRow) {
    PolyCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            SidePill(t.side)
            ToneChip(t.decision.replace('_', ' ').ifBlank { "—" }, statusTone(t.decision))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                Text(Fmt.time(t.createdAt), style = Mono.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            "ask ${Fmt.cents(t.bestAsk)} (trigger ${Fmt.cents(t.threshold)})  ·  size ${Fmt.num(t.askSize, 0)}  ·  T-${Fmt.num(t.remainingSeconds, 1)}s",
            style = Mono.small,
        )
        if (t.reason.isNotBlank()) {
            Label("Reason")
            Text(t.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
