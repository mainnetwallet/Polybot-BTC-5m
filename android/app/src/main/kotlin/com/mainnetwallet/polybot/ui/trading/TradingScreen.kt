package com.mainnetwallet.polybot.ui.trading

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.mainnetwallet.polybot.ui.components.PolyPullToRefresh
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.mainnetwallet.polybot.ui.components.IconBadge
import com.mainnetwallet.polybot.ui.components.KeyValueRow
import com.mainnetwallet.polybot.ui.components.Label
import com.mainnetwallet.polybot.ui.components.PnlBadge
import com.mainnetwallet.polybot.ui.components.ProCard
import com.mainnetwallet.polybot.ui.components.ProHero
import com.mainnetwallet.polybot.ui.components.ProSectionHeader
import com.mainnetwallet.polybot.ui.components.ProStat
import com.mainnetwallet.polybot.ui.components.SegmentedTabs
import com.mainnetwallet.polybot.ui.components.SidePill
import com.mainnetwallet.polybot.ui.components.SkeletonCard
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
    val slice by vm.slice.collectAsStateWithLifecycle()
    val prefs by graph.prefs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var refreshing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<SellTarget?>(null) }
    var detail by remember { mutableStateOf<OrderRow?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var filter by rememberSaveable { mutableStateOf(0) }
    val historyFilter = HistoryFilter.entries.getOrElse(filter) { HistoryFilter.All }

    val snap = slice
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

    // Manual sells (paper and live) get the confirm sheet unless the person turned that off in Settings (default: off).
    // Until the preferences have loaded (null) it still asks, which is the safe side.
    fun request(t: SellTarget) {
        ui.tap()
        if (prefs?.confirmSells != false) target = t else execute(t)
    }

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
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                SkeletonCard(lines = 3)
                SkeletonCard(lines = 4)
            }
        } else {
            val shownOrders = remember(state.orders, historyFilter) { state.orders.filter { historyFilter.matches(it) } }
            if (tab == HISTORY_TAB) {
                // History: balance, tabs and filters stay put; only the order list below them scrolls.
                Column(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.padding(start = Space.lg, end = Space.lg, top = Space.lg),
                        verticalArrangement = Arrangement.spacedBy(Space.md),
                    ) {
                        AccountHero(snap, live, compact = true)
                        TradingTabs(snap, tab) { tab = it }
                        ProSectionHeader(
                            "Order history",
                            subtitle = "Latest ${state.orders.size} orders",
                            trailing = {
                                TextButton(
                                    onClick = { confirmClear = true },
                                    enabled = state.orders.isNotEmpty() && !busy,
                                ) {
                                    Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                                    Text("Clear")
                                }
                            },
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            HistoryFilter.entries.forEachIndexed { index, f ->
                                FilterChip(
                                    selected = index == filter,
                                    onClick = { filter = index },
                                    label = { Text(f.label, maxLines = 1) },
                                )
                            }
                        }
                    }
                    LazyColumn(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(Space.lg),
                        verticalArrangement = Arrangement.spacedBy(Space.md),
                    ) {
                        when {
                            state.loading -> item { SkeletonCard(lines = 3) }
                            state.error != null && state.orders.isEmpty() -> item {
                                ProCard {
                                    EmptyState("Could not load the history", state.error, icon = Icons.Filled.ReceiptLong)
                                }
                            }
                            state.orders.isEmpty() -> item {
                                ProCard {
                                    EmptyState(
                                        "No orders yet",
                                        "Orders appear here as soon as the engine places one.",
                                        icon = Icons.Filled.ReceiptLong,
                                    )
                                }
                            }
                            shownOrders.isEmpty() -> item {
                                ProCard {
                                    EmptyState(
                                        "No matching orders",
                                        "Try another filter.",
                                        icon = Icons.Filled.ReceiptLong,
                                    )
                                }
                            }
                            else -> items(shownOrders, key = { "hist-${it.id}" }, contentType = { "order" }) { order ->
                                OrderRowItem(order, onClick = { detail = order })
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(Space.lg),
                    verticalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    item { AccountHero(snap, live) }
                    item { TradingTabs(snap, tab) { tab = it } }

                    if (tab == 0) {
                        // ---- positions + instant sell -----------------------------------------------------------
                        if (snap.sellable.isNotEmpty()) {
                            item { ProSectionHeader("Quick sell", subtitle = "Sell shares at the market, per stage") }
                            item {
                                QuickSell(
                                    holdings = snap.sellable,
                                    busy = busy,
                                    live = live,
                                    onSellStage = { h -> request(SellTarget.Stage(h.marketId, h.side, h.stage, h.size)) },
                                    onSellAll = { marketId, stages -> request(SellTarget.All(marketId, stages)) },
                                )
                            }
                        }
                        item { ProSectionHeader("Open positions", subtitle = "Held until the market settles") }
                        if (snap.positions.isEmpty()) {
                            item {
                                ProCard {
                                    EmptyState(
                                        "No open positions",
                                        "Filled buys show up here until the market settles.",
                                        icon = Icons.Filled.Inventory2,
                                    )
                                }
                            }
                        } else {
                            items(snap.positions, key = { "pos-${it.id}" }, contentType = { "position" }) { PositionCard(it) }
                        }
                    } else {
                        // ---- orders that are still working ------------------------------------------------------
                        item { ProSectionHeader("Open orders", subtitle = "Waiting on the exchange") }
                        if (snap.openOrders.isEmpty()) {
                            item {
                                ProCard {
                                    EmptyState(
                                        "No open orders",
                                        "Orders that are still working appear here.",
                                        icon = Icons.Filled.ReceiptLong,
                                    )
                                }
                            }
                        } else {
                            items(snap.openOrders, key = { "open-${it.id}" }, contentType = { "order" }) { order ->
                                OrderRowItem(order, onClick = { detail = order })
                            }
                        }
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

private val TabLabels = listOf("Positions", "Open orders", "History")
private const val HISTORY_TAB = 2

@Composable
private fun TradingTabs(snap: TradingSlice, tab: Int, onSelect: (Int) -> Unit) {
    SegmentedTabs(
        labels = TabLabels,
        selected = tab,
        onSelect = onSelect,
        counts = listOf(snap.positions.size, snap.openOrders.size, 0),
    )
}

private enum class HistoryFilter(val label: String) {
    All("All"),
    Buys("Buys"),
    Sells("Sells"),
    Issues("Issues");

    fun matches(o: OrderRow): Boolean = when (this) {
        All -> true
        Buys -> o.action == "BUY"
        Sells -> o.action == "SELL"
        Issues -> !o.error.isNullOrBlank() || statusTone(o.status) == Tone.Down
    }
}

// ---- account hero -----------------------------------------------------------------------------------------------

@Composable
private fun AccountHero(snap: TradingSlice, live: Boolean, compact: Boolean = false) {
    val account = snap.account
    val base = account.balance - account.dailyPnl
    val dailyPct: Double? = if (base > 0.0) account.dailyPnl / base * 100.0 else null
    val dailyText = if (dailyPct != null) {
        Fmt.signedUsd(account.dailyPnl) + "  " + Fmt.signedPercent(dailyPct)
    } else {
        Fmt.signedUsd(account.dailyPnl)
    }
    ProHero {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label("Balance", Modifier.weight(1f))
            ToneChip(snap.bot.mode.uppercase(), if (live) Tone.Down else Tone.Info)
        }
        Text(Fmt.usdGrouped(account.balance), style = Mono.xlarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            PnlBadge(text = dailyText, value = account.dailyPnl)
            Text("today", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!compact) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
                ProStat("Unrealized", Fmt.signedUsd(account.unrealizedPnl), Modifier.weight(1f), pnlColor(account.unrealizedPnl))
                ProStat("Realized", Fmt.signedUsd(account.realizedPnl), Modifier.weight(1f), pnlColor(account.realizedPnl))
                ProStat("Trades", account.tradesToday.toString(), Modifier.weight(1f))
            }
        }
    }
}

// ---- quick sell -------------------------------------------------------------------------------------------------

@Composable
private fun QuickSell(
    holdings: List<SellableHolding>,
    busy: Boolean,
    live: Boolean,
    onSellStage: (SellableHolding) -> Unit,
    onSellAll: (String, Int) -> Unit,
) {
    val c = Poly.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        holdings.groupBy { it.marketId }.forEach { (marketId, group) ->
            ProCard(accent = if (live) c.live else null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Label("Market ${Fmt.shortId(marketId)}", Modifier.weight(1f))
                    if (live) ToneChip("LIVE", Tone.Down)
                }
                group.forEachIndexed { index, h ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.md),
                    ) {
                        SidePill(h.side)
                        Column(Modifier.weight(1f)) {
                            Text("Stage ${h.stage}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${Fmt.num(h.size, 2)} sh  ·  entry ${Fmt.cents(h.entryPrice)}",
                                style = Mono.small,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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

// ---- positions --------------------------------------------------------------------------------------------------

@Composable
private fun PositionCard(p: PositionRow) {
    val c = Poly.colors
    val isUp = p.side.equals("up", ignoreCase = true)
    val roi: Double? = if (p.costBasis > 0.0) p.unrealizedPnl / p.costBasis * 100.0 else null
    ProCard(accent = if (isUp) c.up else c.down) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            SidePill(p.side)
            ToneChip(
                if (p.awaitingSettlement) "SETTLING" else p.status.uppercase().replace('_', ' '),
                statusTone(if (p.awaitingSettlement) "settling" else p.status),
            )
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                Text(Fmt.shortId(p.marketId), style = Mono.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Label("Unrealized PnL")
                Text(
                    Fmt.signedUsd(p.unrealizedPnl),
                    style = Mono.large,
                    color = pnlColor(p.unrealizedPnl),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PnlBadge(text = Fmt.signedPercent(roi), value = roi)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
            ProStat("Size", Fmt.num(p.size, 2), Modifier.weight(1f))
            ProStat("Avg price", Fmt.cents(p.avgPrice), Modifier.weight(1f))
            ProStat("Cost", Fmt.usd(p.costBasis), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
            ProStat("Realized", Fmt.signedUsd(p.realizedPnl), Modifier.weight(1f), pnlColor(p.realizedPnl))
            ProStat("Fees", Fmt.usd(p.fees, 3), Modifier.weight(1f))
            ProStat("Payout if win", Fmt.usd(p.size), Modifier.weight(1f))
        }
        if (!p.resolution.isNullOrBlank()) {
            Text(
                "Resolved: ${p.resolution}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---- orders -----------------------------------------------------------------------------------------------------

private class OrderLook(val icon: ImageVector, val fg: Color, val bg: Color)

@Composable
private fun orderLook(kind: String?): OrderLook {
    val c = Poly.colors
    val s = MaterialTheme.colorScheme
    return when (kind) {
        "BUY" -> OrderLook(Icons.Filled.Add, s.onPrimaryContainer, s.primaryContainer)
        "TAKE PROFIT" -> OrderLook(Icons.Filled.ArrowUpward, c.up, c.upContainer)
        "STOP LOSS" -> OrderLook(Icons.Filled.ArrowDownward, c.down, c.downContainer)
        "MANUAL SELL" -> OrderLook(Icons.Filled.TouchApp, c.warn, c.warnContainer)
        "SELL" -> OrderLook(Icons.Filled.Remove, s.onSurfaceVariant, s.surfaceVariant)
        else -> OrderLook(Icons.Filled.SwapVert, s.onSurfaceVariant, s.surfaceVariant)
    }
}

private fun kindTitle(kind: String?): String =
    kind?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Order"

private fun resultTone(result: String): Tone = when (result) {
    "WIN" -> Tone.Up
    "LOSS" -> Tone.Down
    "BUY" -> Tone.Info
    else -> Tone.Warn
}

@Composable
private fun OrderRowItem(order: OrderRow, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val kind = orderKind(order.action, order.reason)
    val look = orderLook(kind)
    val result = resultLabel(order.action, order.filledSize, order.result)
    val pnl = order.resultPnl
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainer,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = Space.md, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            IconBadge(look.icon, look.fg, look.bg)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Text(
                        kindTitle(kind),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    SidePill(order.side)
                    if (order.stage != null) {
                        Text("S${order.stage}", style = Mono.small, color = scheme.onSurfaceVariant)
                    }
                }
                Text(
                    "${Fmt.num(order.filledSize, 2)} sh @ ${Fmt.cents(order.avgFillPrice)}",
                    style = Mono.small,
                    color = scheme.onSurfaceVariant,
                )
                if (!order.error.isNullOrBlank()) {
                    Text(
                        order.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = Poly.colors.down,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (pnl != null) {
                    Text(
                        Fmt.signedUsd(pnl),
                        style = Mono.body.copy(fontWeight = FontWeight.SemiBold),
                        color = pnlColor(pnl),
                    )
                } else if (result != null) {
                    ToneChip(result, resultTone(result))
                } else {
                    ToneChip(order.status.replace('_', ' '), statusTone(order.status))
                }
                Text(Fmt.time(order.createdAt), style = Mono.small, color = scheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrderDetailSheet(order: OrderRow, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val kind = orderKind(order.action, order.reason)
    val look = orderLook(kind)
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
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                IconBadge(look.icon, look.fg, look.bg, size = 48.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(kindTitle(kind), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Order #${order.id}  ·  ${Fmt.dateTime(order.createdAt)}",
                        style = Mono.small,
                        color = scheme.onSurfaceVariant,
                    )
                }
                SidePill(order.side)
            }

            ProCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Label("Result PnL")
                        Text(
                            Fmt.signedUsd(order.resultPnl),
                            style = Mono.large,
                            color = pnlColor(order.resultPnl),
                            maxLines = 1,
                        )
                    }
                    ToneChip(order.status.replace('_', ' '), statusTone(order.status))
                }
            }

            ProCard {
                Label("Execution")
                KeyValueRow("Requested", "${Fmt.usd(order.requestedNotional)} @ ${Fmt.cents(order.requestedPrice)}")
                KeyValueRow("Submitted price", Fmt.cents(order.submittedPrice))
                KeyValueRow("Filled", "${Fmt.num(order.filledSize, 4)} sh")
                KeyValueRow("Avg fill", Fmt.cents(order.avgFillPrice))
                KeyValueRow("Fee", Fmt.usd(order.fee, 4))
                KeyValueRow("Latency", if (order.latencyMs == null) "—" else Fmt.num(order.latencyMs, 0) + " ms")
            }

            ProCard {
                Label("Reference")
                KeyValueRow("Stage", order.stage?.toString() ?: "—")
                KeyValueRow("Market", Fmt.shortId(order.marketId, 10, 6))
                KeyValueRow("Exchange id", Fmt.shortId(order.exchangeOrderId, 14, 6))
                KeyValueRow("Result", order.result ?: "—")
            }

            if (!order.reason.isNullOrBlank()) {
                ProCard {
                    Label("Reason")
                    Text(order.reason, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (!order.error.isNullOrBlank()) {
                ProCard(accent = Poly.colors.down) {
                    Label("Error")
                    Text(order.error, style = MaterialTheme.typography.bodyMedium, color = Poly.colors.down)
                }
            }

            FilledTonalButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        }
    }
}
