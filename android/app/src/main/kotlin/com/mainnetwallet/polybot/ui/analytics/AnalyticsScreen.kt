package com.mainnetwallet.polybot.ui.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.mainnetwallet.polybot.ui.components.PolyPullToRefresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mainnetwallet.polybot.data.model.Analytics
import com.mainnetwallet.polybot.data.model.Modes
import com.mainnetwallet.polybot.domain.AnalyticsInsights
import com.mainnetwallet.polybot.domain.Tone
import com.mainnetwallet.polybot.ui.Fmt
import com.mainnetwallet.polybot.ui.LocalUi
import com.mainnetwallet.polybot.ui.components.Dot
import com.mainnetwallet.polybot.ui.components.EmptyState
import com.mainnetwallet.polybot.ui.components.Label
import com.mainnetwallet.polybot.ui.components.PnlBadge
import com.mainnetwallet.polybot.ui.components.ProCard
import com.mainnetwallet.polybot.ui.components.ProHero
import com.mainnetwallet.polybot.ui.components.ProSectionHeader
import com.mainnetwallet.polybot.ui.components.ProStat
import com.mainnetwallet.polybot.ui.components.RingGauge
import com.mainnetwallet.polybot.ui.components.SkeletonCard
import com.mainnetwallet.polybot.ui.components.StatTile
import com.mainnetwallet.polybot.ui.components.ToneChip
import com.mainnetwallet.polybot.ui.components.pnlColor
import com.mainnetwallet.polybot.ui.graphViewModel
import com.mainnetwallet.polybot.ui.overview.ModeToggle
import com.mainnetwallet.polybot.ui.theme.Mono
import com.mainnetwallet.polybot.ui.theme.Poly
import com.mainnetwallet.polybot.ui.theme.Space
import kotlin.math.abs
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(modifier: Modifier = Modifier) {
    val vm: AnalyticsViewModel = graphViewModel()
    val ui = LocalUi.current
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }

    PolyPullToRefresh(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            scope.launch {
                vm.reload()
                refreshing = false
            }
        },
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            LedgerHeader(mode = state.mode, onSelect = {
                ui.tap()
                vm.select(it)
            })
            val a = state.analytics
            when {
                a != null -> AnalyticsBody(a, state.mode)
                state.loading -> AnalyticsSkeleton()
                else -> ProCard {
                    EmptyState(
                        if (state.error != null) "Could not load analytics" else "No analytics yet",
                        hint = state.error,
                        icon = Icons.Filled.BarChart,
                    )
                }
            }
        }
    }
}

// ---- header -----------------------------------------------------------------------------------------------------

/** PAPER | LIVE switch plus a reminder that it only changes which ledger is shown, not the bot. */
@Composable
private fun LedgerHeader(mode: String, onSelect: (String) -> Unit) {
    val c = Poly.colors
    val live = mode == Modes.LIVE
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        ModeToggle(selected = mode, enabled = true, onSelect = onSelect)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Dot(if (live) c.live else c.info, size = 6.dp)
            Text(
                "Viewing the $mode ledger. This does not switch the bot.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AnalyticsSkeleton() {
    SkeletonCard(lines = 4)
    SkeletonCard(lines = 3)
    SkeletonCard(lines = 3)
}

// ---- body -------------------------------------------------------------------------------------------------------

@Composable
private fun AnalyticsBody(a: Analytics, mode: String) {
    val insights = remember(a) { AnalyticsInsights.of(a) }
    PnlHero(a, insights, mode)
    if (a.totalTrades == 0) {
        ProCard {
            EmptyState(
                "No trades yet",
                hint = "Statistics appear after the first fill on this ledger.",
                icon = Icons.Filled.BarChart,
            )
        }
    } else {
        WinRateCard(a, insights)
        if (insights.breakEvenWinRate != null) EdgeCard(a, insights)
        PnlBreakdownCard(a)
        ActivityCard(a, insights)
    }
}

/** Total PnL as the big number, the per-trade average as a badge, and the three figures that make it up. */
@Composable
private fun PnlHero(a: Analytics, insights: AnalyticsInsights, mode: String) {
    val live = mode == Modes.LIVE
    val avg = insights.avgPnlPerSettled
    ProHero {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label("Total PnL", Modifier.weight(1f))
            ToneChip(mode.uppercase(), if (live) Tone.Down else Tone.Info)
        }
        Text(
            Fmt.signedUsd(a.totalPnl),
            style = Mono.xlarge,
            color = pnlColor(a.totalPnl),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (avg != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                PnlBadge(text = Fmt.signedUsd(avg), value = avg)
                Text(
                    "avg per settled trade",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
            ProStat("Realized", Fmt.signedUsd(a.realizedPnl), Modifier.weight(1f), pnlColor(a.realizedPnl))
            ProStat("Unrealized", Fmt.signedUsd(a.unrealizedPnl), Modifier.weight(1f), pnlColor(a.unrealizedPnl))
            ProStat("Fees", Fmt.usd(a.feesPaid, 3), Modifier.weight(1f))
        }
    }
}

/** Win rate ring (green when it beats the break-even point, red when it does not) with wins, losses and open. */
@Composable
private fun WinRateCard(a: Analytics, insights: AnalyticsInsights) {
    val c = Poly.colors
    val scheme = MaterialTheme.colorScheme
    val edge = insights.edgePoints
    val ringColor = when {
        edge == null -> scheme.primary
        edge >= 0.0 -> c.up
        else -> c.down
    }
    ProCard {
        ProSectionHeader("Win rate", subtitle = "${insights.decided} decided trades")
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xl),
        ) {
            RingGauge(
                fraction = ((a.winRate ?: 0.0) / 100.0).toFloat(),
                color = ringColor,
                description = "Win rate ${Fmt.percent(a.winRate)}",
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Fmt.percent(a.winRate), style = Mono.medium.copy(fontSize = 20.sp), maxLines = 1)
                    Text(
                        "win rate",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                LegendRow(c.up, "Wins", a.wins.toString())
                LegendRow(c.down, "Losses", a.losses.toString())
                LegendRow(scheme.outline, "Open", insights.openTrades.toString())
            }
        }
        if (insights.decided > 0) WinLossBar(a.wins, a.losses)
    }
}

/** Win rate against the break-even point implied by the average entry price. */
@Composable
private fun EdgeCard(a: Analytics, insights: AnalyticsInsights) {
    val c = Poly.colors
    val edge = insights.edgePoints
    val fill = when {
        edge == null -> MaterialTheme.colorScheme.primary
        edge >= 0.0 -> c.up
        else -> c.down
    }
    ProCard {
        ProSectionHeader("Edge vs entry price", subtitle = "Win rate against the break-even point")
        BreakEvenTrack(winRate = a.winRate, breakEven = insights.breakEvenWinRate, fill = fill)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
            ProStat("Win rate", Fmt.percent(a.winRate), Modifier.weight(1f))
            ProStat("Break-even", Fmt.percent(insights.breakEvenWinRate), Modifier.weight(1f))
            ProStat(
                "Edge",
                Fmt.signedPoints(edge),
                Modifier.weight(1f),
                valueColor = if (edge == null) Color.Unspecified else fill,
            )
        }
        Text(
            "Rough guide: a share bought at ${Fmt.centsShort(a.avgEntryPrice)} pays $1 on a win, so it breaks even " +
                "near a ${Fmt.percent(insights.breakEvenWinRate, 0)} win rate. Early exits and fees shift this.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Realized, unrealized and total PnL on one shared scale. */
@Composable
private fun PnlBreakdownCard(a: Analytics) {
    val maxAbs = maxOf(abs(a.realizedPnl), abs(a.unrealizedPnl), abs(a.totalPnl))
    ProCard {
        ProSectionHeader("Profit and loss", subtitle = "Settled trades and open positions")
        PnlBreakdownRow("Realized", a.realizedPnl, maxAbs)
        PnlBreakdownRow("Unrealized", a.unrealizedPnl, maxAbs)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        PnlBreakdownRow("Total", a.totalPnl, maxAbs, emphasized = true)
    }
}

/** Volume, trade counts, sizing and what trading costs. */
@Composable
private fun ActivityCard(a: Analytics, insights: AnalyticsInsights) {
    val divider = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    ProCard {
        ProSectionHeader("Activity", subtitle = "Size and cost of the trading")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
            StatTile("Volume", Fmt.usdGrouped(a.volumeUsdc), Modifier.weight(1f), hint = "USDC notional")
            StatTile(
                "Trades",
                a.totalTrades.toString(),
                Modifier.weight(1f),
                hint = "${a.closedTrades} settled · ${insights.openTrades} open",
            )
        }
        HorizontalDivider(color = divider)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
            StatTile("Avg trade size", Fmt.usd(insights.avgTradeSize), Modifier.weight(1f), hint = "per trade")
            StatTile("Avg entry", Fmt.cents(a.avgEntryPrice), Modifier.weight(1f), hint = "share price")
        }
        HorizontalDivider(color = divider)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
            StatTile("Fees paid", Fmt.usd(a.feesPaid, 3), Modifier.weight(1f), hint = "all trades")
            StatTile(
                "Fees / volume",
                Fmt.percent(insights.feePercentOfVolume, 2),
                Modifier.weight(1f),
                hint = "cost of trading",
            )
        }
    }
}
