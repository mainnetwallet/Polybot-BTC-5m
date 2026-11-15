package com.mainnetwallet.polybot.ui.trading

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.mainnetwallet.polybot.data.ActionResult
import com.mainnetwallet.polybot.data.model.OrderRow
import com.mainnetwallet.polybot.data.model.PositionRow
import com.mainnetwallet.polybot.data.model.SellableHolding
import com.mainnetwallet.polybot.domain.Tone
import com.mainnetwallet.polybot.domain.orderKind
import com.mainnetwallet.polybot.domain.resultLabel
import com.mainnetwallet.polybot.domain.statusTone
import com.mainnetwallet.polybot.ui.Fmt
import com.mainnetwallet.polybot.ui.LocalGraph
import com.mainnetwallet.polybot.ui.LocalUi
import com.mainnetwallet.polybot.ui.components.EmptyState
import com.mainnetwallet.polybot.ui.components.KeyValueRow
import com.mainnetwallet.polybot.ui.components.Label
import com.mainnetwallet.polybot.ui.components.PolyCard
import com.mainnetwallet.polybot.ui.components.SectionTitle
import com.mainnetwallet.polybot.ui.components.SidePill
import com.mainnetwallet.polybot.ui.components.SkeletonCard
import com.mainnetwallet.polybot.ui.components.StatTile
import com.mainnetwallet.polybot.ui.components.ToneChip
import com.mainnetwallet.polybot.ui.components.pnlColor
import com.mainnetwallet.polybot.ui.graphViewModel
import com.mainnetwallet.polybot.ui.safety.ConfirmDialog
import com.mainnetwallet.polybot.ui.safety.LiveSellSheet
import com.mainnetwallet.polybot.ui.theme.Mono
import com.mainnetwallet.polybot.ui.theme.Poly
import com.mainnetwallet.polybot.ui.theme.Space
import kotlinx.coroutines.launch

private sealed interface SellTarget {
    data class Stage(val marketId: String, val side: String, val stage: Int, val size: Double) : SellTarget
    data class All(val marketId: String, val stages: Int) : SellTarget
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradingScreen(modifier: Modifier = Modifier) {
    val vm: TradingViewModel = graphViewModel()
    val graph = LocalGraph.current
    val ui = LocalUi.current
    val repo = graph.repository
    val state by vm.state.collectAsStateWithLifecycle()
    val snapshot by repo.snapshot.collectAsStateWithLifecycle()
    val prefs by graph.prefs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var refreshing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<SellTarget?>(null) }
    var detail by remember { mutableStateOf<OrderRow?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    val snap = snapshot
    val live = snap?.bot?.isLive == true

    suspend fun send(t: SellTarget): ActionResult = when (t) {
        is SellTarget.Stage -> repo.sellNow(t.marketId, t.side, t.stage)
        is SellTarget.All -> repo.sellAll(t.marketId)
    }

    fun execute(t: SellTarget) {
        if (busy) return
        busy = true
        scope.launch {
            val result = send(t)
            busy = false
            ui.report(result)
        }
    }

    // Live sells always get the confirm sheet; paper sells ask unless the person turned that off in Settings.
    fun request(t: SellTarget) {
        ui.tap()
        if (live || prefs?.confirmSells != false) target = t else execute(t)
    }

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
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                SkeletonCard(lines = 3)
                SkeletonCard(lines = 4)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.lg),
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                item {
                    PolyCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
                            StatTile("Balance", Fmt.usdGrouped(snap.account.balance), Modifier.weight(1f))
                            StatTile("Daily PnL", Fmt.signedUsd(snap.account.dailyPnl), Modifier.weight(1f), valueColor = pnlColor(snap.account.dailyPnl))
                            StatTile("Open orders", snap.openOrders.size.toString(), Modifier.weight(1f))
                        }
                    }
                }

                item { SectionTitle("Sell now") }
                item {
                    SellableCard(
                        holdings = snap.sellable,
                        busy = busy,
                        live = live,
                        onSellStage = { h -> request(SellTarget.Stage(h.marketId, h.side, h.stage, h.size)) },
                        onSellAll = { marketId, stages -> request(SellTarget.All(marketId, stages)) },
                    )
                }

                item { SectionTitle("Open positions") }
                if (snap.positions.isEmpty()) {
                    item {
                        PolyCard {
                            EmptyState(
                                "No open positions",
                                "Filled buys show up here until the market settles.",
                                icon = Icons.Filled.Inventory2,
                            )
                        }
                    }
                } else {
                    items(snap.positions, key = { "pos-${it.id}" }) { PositionCard(it) }
                }

                if (snap.openOrders.isNotEmpty()) {
                    item { SectionTitle("Open orders") }
                    items(snap.openOrders, key = { "open-${it.id}" }) { order ->
                        OrderCard(order, onClick = { detail = order })
                    }
                }

                item {
                    SectionTitle("Order history") {
                        TextButton(
                            onClick = { confirmClear = true },
                            enabled = state.orders.isNotEmpty() && !busy,
                        ) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                            Text("Clear")
                        }
                    }
                }
                when {
                    state.loading -> item { SkeletonCard(lines = 3) }
                    state.error != null && state.orders.isEmpty() -> item {
                        PolyCard {
                            EmptyState("Could not load the history", state.error, icon = Icons.Filled.ReceiptLong)
                        }
                    }
                    state.orders.isEmpty() -> item {
                        PolyCard {
                            EmptyState(
                                "No orders yet",
                                "Orders appear here as soon as the engine places one.",
                                icon = Icons.Filled.ReceiptLong,
                            )
                        }
                    }
                    else -> items(state.orders, key = { "hist-${it.id}" }) { order ->
                        OrderCard(order, onClick = { detail = order })
                    }
                }
            }
        }
    }

    // ---- confirmations --------------------------------------------------------------------------------------
    val pending = target
    if (pending != null) {
        val title: String
        val body: String
        when (pending) {
            is SellTarget.Stage -> {
                title = "Sell ${pending.side} stage ${pending.stage}"
                body = "Sell ${Fmt.num(pending.size, 2)} shares at the market now."
            }
            is SellTarget.All -> {
                title = "Sell everything"
                body = "Sell every stage that holds shares in this market (${pending.stages}) at the market now."
            }
        }
        if (live) {
            LiveSellSheet(
                title = title,
                body = body,
                confirmLabel = "Sell now",
                onConfirm = { send(pending) },
                onDismiss = { target = null },
            )
        } else {
            ConfirmDialog(
                title = title,
                text = body,
                confirmLabel = "Sell",
                onConfirm = { execute(pending) },
                onDismiss = { target = null },
                destructive = true,
            )
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Clear order history?",
            text = "This clears the ${snap?.bot?.mode ?: "current"} order list. Daily PnL, trades today and stats are not changed.",
            confirmLabel = "Clear",
            onConfirm = {
                scope.launch {
                    val result = repo.clearOrderHistory()
                    ui.report(result)
                    vm.reload()
                }
            },
            onDismiss = { confirmClear = false },
            destructive = true,
        )
    }

    detail?.let { order -> OrderDetailSheet(order, onDismiss = { detail = null }) }
}

@Composable
private fun SellableCard(
    holdings: List<SellableHolding>,
    busy: Boolean,
    live: Boolean,
    onSellStage: (SellableHolding) -> Unit,
    onSellAll: (String, Int) -> Unit,
) {
    val c = Poly.colors
    PolyCard(borderColor = if (live && holdings.isNotEmpty()) c.live else null) {
        if (holdings.isEmpty()) {
            EmptyState(
                "Nothing to sell",
                "Shares that can be sold at the market appear here, per stage.",
                icon = Icons.Filled.ShowChart,
            )
        } else {
            holdings.groupBy { it.marketId }.forEach { (marketId, group) ->
                Label("Market ${Fmt.shortId(marketId)}")
                group.forEach { h ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                        SidePill(h.side)
                        Column(Modifier.weight(1f)) {
                            Text("Stage ${h.stage}", style = MaterialTheme.typography.titleSmall)
                            Text("${Fmt.num(h.size, 2)} shares", style = Mono.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(onClick = { onSellStage(h) }, enabled = !busy) { Text("Sell now") }
                    }
                }
                Button(
                    onClick = { onSellAll(marketId, group.size) },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Sell all (${group.size} stage${if (group.size == 1) "" else "s"})") }
            }
        }
    }
}

@Composable
private fun PositionCard(p: PositionRow) {
    PolyCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            SidePill(p.side)
            ToneChip(
                if (p.awaitingSettlement) "SETTLING" else p.status.uppercase().replace('_', ' '),
                statusTone(if (p.awaitingSettlement) "settling" else p.status),
            )
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                Text(Fmt.shortId(p.marketId), style = Mono.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
            StatTile("Size", Fmt.num(p.size, 2), Modifier.weight(1f))
            StatTile("Avg price", Fmt.cents(p.avgPrice), Modifier.weight(1f))
            StatTile("Cost", Fmt.usd(p.costBasis), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
            StatTile("Unrealized", Fmt.signedUsd(p.unrealizedPnl), Modifier.weight(1f), valueColor = pnlColor(p.unrealizedPnl))
            StatTile("Realized", Fmt.signedUsd(p.realizedPnl), Modifier.weight(1f), valueColor = pnlColor(p.realizedPnl))
            StatTile("Fees", Fmt.usd(p.fees, 3), Modifier.weight(1f))
        }
        if (!p.resolution.isNullOrBlank()) {
            Text("Resolved: ${p.resolution}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OrderCard(order: OrderRow, onClick: () -> Unit) {
    val result = resultLabel(order.action, order.filledSize, order.result)
    PolyCard(Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            orderKind(order.action, order.reason)?.let { kind ->
                ToneChip(kind, if (kind == "STOP LOSS") Tone.Down else if (kind == "TAKE PROFIT") Tone.Up else if (kind == "MANUAL SELL") Tone.Warn else Tone.Info)
            }
            SidePill(order.side)
            if (order.stage != null) Text("S${order.stage}", style = Mono.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                Text(Fmt.time(order.createdAt), style = Mono.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            ToneChip(order.status.replace('_', ' '), statusTone(order.status))
            if (result != null) {
                val tone = when (result) {
                    "WIN" -> Tone.Up
                    "LOSS" -> Tone.Down
                    "BUY" -> Tone.Info
                    else -> Tone.Warn
                }
                val pnl = order.resultPnl
                ToneChip(if (pnl != null) "$result ${Fmt.signedUsd(pnl)}" else result, tone)
            }
        }
        Text(
            "${Fmt.num(order.filledSize, 2)} sh @ ${Fmt.cents(order.avgFillPrice)}  ·  fee ${Fmt.usd(order.fee, 3)}",
            style = Mono.small,
        )
        if (!order.error.isNullOrBlank()) {
            Text(order.error, style = MaterialTheme.typography.bodySmall, color = Poly.colors.down)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrderDetailSheet(order: OrderRow, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = Space.lg, vertical = Space.sm),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text("Order #${order.id}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                SidePill(order.side)
            }
            KeyValueRow("Time", Fmt.dateTime(order.createdAt))
            KeyValueRow("Kind", orderKind(order.action, order.reason) ?: "—")
            KeyValueRow("Stage", order.stage?.toString() ?: "—")
            KeyValueRow("Status", order.status.replace('_', ' '))
            KeyValueRow("Market", Fmt.shortId(order.marketId, 10, 6))
            KeyValueRow("Requested", "${Fmt.usd(order.requestedNotional)} @ ${Fmt.cents(order.requestedPrice)}")
            KeyValueRow("Submitted price", Fmt.cents(order.submittedPrice))
            KeyValueRow("Filled", "${Fmt.num(order.filledSize, 4)} sh")
            KeyValueRow("Avg fill", Fmt.cents(order.avgFillPrice))
            KeyValueRow("Fee", Fmt.usd(order.fee, 4))
            KeyValueRow("Latency", if (order.latencyMs == null) "—" else Fmt.num(order.latencyMs, 0) + " ms")
            KeyValueRow("Result", order.result ?: "—")
            KeyValueRow("Result PnL", Fmt.signedUsd(order.resultPnl), valueColor = pnlColor(order.resultPnl))
            KeyValueRow("Exchange id", Fmt.shortId(order.exchangeOrderId, 14, 6))
            if (!order.reason.isNullOrBlank()) {
                Label("Reason")
                Text(order.reason, style = MaterialTheme.typography.bodyMedium)
            }
            if (!order.error.isNullOrBlank()) {
                Label("Error")
                Text(order.error, style = MaterialTheme.typography.bodyMedium, color = Poly.colors.down)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = Space.sm), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}
