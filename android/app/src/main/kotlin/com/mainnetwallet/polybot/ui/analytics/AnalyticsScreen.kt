package com.mainnetwallet.polybot.ui.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mainnetwallet.polybot.data.model.Analytics
import com.mainnetwallet.polybot.ui.Fmt
import com.mainnetwallet.polybot.ui.LocalUi
import com.mainnetwallet.polybot.ui.components.EmptyState
import com.mainnetwallet.polybot.ui.components.PolyCard
import com.mainnetwallet.polybot.ui.components.SectionTitle
import com.mainnetwallet.polybot.ui.components.SkeletonCard
import com.mainnetwallet.polybot.ui.components.StatTile
import com.mainnetwallet.polybot.ui.components.pnlColor
import com.mainnetwallet.polybot.ui.graphViewModel
import com.mainnetwallet.polybot.ui.overview.ModeToggle
import com.mainnetwallet.polybot.ui.theme.Poly
import com.mainnetwallet.polybot.ui.theme.Space
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(modifier: Modifier = Modifier) {
    val vm: AnalyticsViewModel = graphViewModel()
    val ui = LocalUi.current
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }

    PullToRefreshBox(
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
            ModeToggle(selected = state.mode, enabled = true, onSelect = {
                ui.tap()
                vm.select(it)
            })
            Text(
                "Viewing the ${state.mode} ledger. This does not switch the bot.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val a = state.analytics
            when {
                a != null -> AnalyticsBody(a)
                state.loading -> {
                    SkeletonCard(lines = 4)
                    SkeletonCard(lines = 3)
                }
                else -> PolyCard {
                    EmptyState("No analytics yet", state.error, icon = Icons.Filled.BarChart)
                }
            }
        }
    }
}

@Composable
private fun AnalyticsBody(a: Analytics) {
    val c = Poly.colors
    PolyCard {
        SectionTitle("Performance")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
            StatTile(
                "Win rate",
                Fmt.percent(a.winRate),
                Modifier.weight(1f),
                valueColor = if (a.winRate != null && a.winRate >= 50.0) c.up else androidx.compose.ui.graphics.Color.Unspecified,
                hint = "${a.wins}W / ${a.losses}L",
            )
            StatTile("Total trades", a.totalTrades.toString(), Modifier.weight(1f), hint = "${a.closedTrades} settled")
        }
        val decided = a.wins + a.losses
        if (decided > 0) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp),
            ) {
                if (a.wins > 0) {
                    Box(
                        Modifier
                            .weight(a.wins.toFloat())
                            .height(10.dp)
                            .background(c.up, RoundedCornerShape(topStart = 5.dp, bottomStart = 5.dp)),
                    )
                }
                if (a.losses > 0) {
                    Box(
                        Modifier
                            .weight(a.losses.toFloat())
                            .height(10.dp)
                            .background(c.down, RoundedCornerShape(topEnd = 5.dp, bottomEnd = 5.dp)),
                    )
                }
            }
        }
    }
    PolyCard {
        SectionTitle("Profit and loss")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
            StatTile("Total PnL", Fmt.signedUsd(a.totalPnl), Modifier.weight(1f), valueColor = pnlColor(a.totalPnl))
            StatTile("Realized", Fmt.signedUsd(a.realizedPnl), Modifier.weight(1f), valueColor = pnlColor(a.realizedPnl))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
            StatTile("Unrealized", Fmt.signedUsd(a.unrealizedPnl), Modifier.weight(1f), valueColor = pnlColor(a.unrealizedPnl))
            StatTile("Fees paid", Fmt.usd(a.feesPaid, 3), Modifier.weight(1f))
        }
    }
    PolyCard {
        SectionTitle("Activity")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
            StatTile("Volume", Fmt.usdGrouped(a.volumeUsdc), Modifier.weight(1f), hint = "USDC notional")
            StatTile("Avg entry", Fmt.cents(a.avgEntryPrice), Modifier.weight(1f), hint = "share price")
        }
    }
}
