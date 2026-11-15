package com.mainnetwallet.polybot.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mainnetwallet.polybot.data.model.AccountState
import com.mainnetwallet.polybot.data.model.BookSnapshot
import com.mainnetwallet.polybot.data.model.BotState
import com.mainnetwallet.polybot.data.model.BtcState
import com.mainnetwallet.polybot.data.model.LastOrder
import com.mainnetwallet.polybot.data.model.LiveReadiness
import com.mainnetwallet.polybot.data.model.MarketSnapshot
import com.mainnetwallet.polybot.data.model.Modes
import com.mainnetwallet.polybot.ui.Fmt
import com.mainnetwallet.polybot.ui.components.CountdownRing
import com.mainnetwallet.polybot.ui.components.EmptyState
import com.mainnetwallet.polybot.ui.components.FlashBackground
import com.mainnetwallet.polybot.ui.components.KeyValueRow
import com.mainnetwallet.polybot.ui.components.Label
import com.mainnetwallet.polybot.ui.components.Pill
import com.mainnetwallet.polybot.ui.components.PolyCard
import com.mainnetwallet.polybot.ui.components.SectionTitle
import com.mainnetwallet.polybot.ui.components.SidePill
import com.mainnetwallet.polybot.ui.components.Sparkline
import com.mainnetwallet.polybot.ui.components.StatTile
import com.mainnetwallet.polybot.ui.components.pnlColor
import com.mainnetwallet.polybot.ui.theme.Mono
import com.mainnetwallet.polybot.ui.theme.Poly
import com.mainnetwallet.polybot.ui.theme.Space
import kotlinx.coroutines.delay

/** Countdown ring for the current 5-minute window. The clock follows the server, not the phone. */
@Composable
fun MarketCard(market: MarketSnapshot?, inWindow: Boolean, offsetMs: Long, modifier: Modifier = Modifier) {
    val c = Poly.colors
    PolyCard(modifier) {
        if (market == null) {
            EmptyState(
                title = "No active market",
                hint = "Waiting for the next 5-minute BTC window.",
                icon = Icons.Filled.HourglassEmpty,
            )
        } else {
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(market.marketId) {
                while (true) {
                    now = System.currentTimeMillis()
                    delay(200)
                }
            }
            val endMs = Fmt.parse(market.windowEnd)
            val remaining = if (endMs != null) (endMs - (now + offsetMs)) / 1000.0 else market.remainingSeconds
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CountdownRing(
                    remainingSeconds = remaining,
                    totalSeconds = market.durationSeconds,
                    color = if (inWindow) c.warn else MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Label("Current market")
                    Text(
                        market.slug.ifBlank { Fmt.shortId(market.marketId) },
                        style = Mono.small,
                        maxLines = 2,
                    )
                    if (inWindow) {
                        Pill("FINAL WINDOW", c.warn, c.warnContainer)
                    } else {
                        Pill("OBSERVING", MaterialTheme.colorScheme.onSecondaryContainer, MaterialTheme.colorScheme.secondaryContainer)
                    }
                    Text(
                        "Closes ${Fmt.time(market.windowEnd)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** One outcome: best ask (flashes on change), bid, sizes, armed flag and a small ask history. */
@Composable
fun SideCard(
    side: String,
    book: BookSnapshot?,
    armed: Boolean,
    history: List<Float>,
    modifier: Modifier = Modifier,
) {
    val c = Poly.colors
    val up = side.equals("up", ignoreCase = true)
    val tone = if (up) c.up else c.down
    PolyCard(modifier, borderColor = if (armed) c.warn else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SidePill(side)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                if (armed) Pill("ARMED", c.warn, c.warnContainer)
            }
        }
        Label("Best ask")
        FlashBackground(book?.bestAsk) {
            Text(Fmt.cents(book?.bestAsk), style = Mono.large, color = tone, modifier = Modifier.padding(horizontal = 4.dp))
        }
        KeyValueRow("Bid", Fmt.cents(book?.bestBid))
        KeyValueRow("Ask size", Fmt.num(book?.askSize, 0))
        KeyValueRow("Bid size", Fmt.num(book?.bidSize, 0))
        Sparkline(history, tone, height = 40.dp, description = "$side ask history")
    }
}

/** BTC price against the price to beat, with the gap, a sparkline and a feed health flag. */
@Composable
fun BtcCard(btc: BtcState?, history: List<Float>, modifier: Modifier = Modifier) {
    val c = Poly.colors
    PolyCard(modifier) {
        SectionTitle("BTC") {
            when {
                btc == null || !btc.enabled -> Pill("OFF", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
                !btc.connected -> Pill("DISCONNECTED", c.down, c.downContainer)
                btc.stale -> Pill("STALE", c.warn, c.warnContainer)
                else -> Pill("LIVE", c.up, c.upContainer)
            }
        }
        if (btc == null) {
            Text(
                "No BTC data yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
                StatTile("Price", Fmt.usdGrouped(btc.price), Modifier.weight(1f))
                StatTile("Price to beat", Fmt.usdGrouped(btc.priceToBeat), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
                StatTile(
                    "Gap",
                    if (btc.diff == null) "—" else Fmt.signedUsd(btc.diff),
                    Modifier.weight(1f),
                    valueColor = pnlColor(btc.diff),
                    hint = if (btc.diff == null) null else if (btc.diff > 0) "above" else if (btc.diff < 0) "below" else "level",
                )
                StatTile(
                    "Age",
                    if (btc.ageMs == null) "—" else Fmt.num(btc.ageMs / 1000.0, 1) + " s",
                    Modifier.weight(1f),
                    valueColor = if (btc.stale) c.warn else androidx.compose.ui.graphics.Color.Unspecified,
                )
            }
            Sparkline(history, MaterialTheme.colorScheme.primary, height = 56.dp, reference = btc.priceToBeat?.toFloat(), description = "BTC price chart")
            if (!btc.lastError.isNullOrBlank()) {
                Text(btc.lastError, style = MaterialTheme.typography.bodySmall, color = c.down)
            }
        }
    }
}

@Composable
fun AccountCard(account: AccountState, modifier: Modifier = Modifier) {
    PolyCard(modifier) {
        SectionTitle("Account") { Text(account.mode.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
            StatTile("Balance", Fmt.usdGrouped(account.balance), Modifier.weight(1f))
            StatTile("Daily PnL", Fmt.signedUsd(account.dailyPnl), Modifier.weight(1f), valueColor = pnlColor(account.dailyPnl))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
            StatTile("Realized", Fmt.signedUsd(account.realizedPnl), Modifier.weight(1f), valueColor = pnlColor(account.realizedPnl))
            StatTile("Unrealized", Fmt.signedUsd(account.unrealizedPnl), Modifier.weight(1f), valueColor = pnlColor(account.unrealizedPnl))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
            StatTile("Trades today", account.tradesToday.toString(), Modifier.weight(1f))
            StatTile(
                "Awaiting settlement",
                account.positionsAwaitingSettlement.toString(),
                Modifier.weight(1f),
                hint = if (account.pendingRedeems > 0) "${account.pendingRedeems} pending redeem" else null,
            )
        }
    }
}

/** Two big segments: PAPER | LIVE. */
@Composable
fun ModeToggle(selected: String, enabled: Boolean, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Poly.colors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(Modes.PAPER, Modes.LIVE).forEach { mode ->
                val isSelected = selected == mode
                val live = mode == Modes.LIVE
                val bg = when {
                    !isSelected -> androidx.compose.ui.graphics.Color.Transparent
                    live -> c.live
                    else -> MaterialTheme.colorScheme.primaryContainer
                }
                val fg = when {
                    !isSelected -> MaterialTheme.colorScheme.onSurfaceVariant
                    live -> androidx.compose.ui.graphics.Color.White
                    else -> MaterialTheme.colorScheme.onPrimaryContainer
                }
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .selectable(
                            selected = isSelected,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { if (!isSelected) onSelect(mode) },
                        ),
                    shape = RoundedCornerShape(9.dp),
                    color = bg,
                ) {
                    Text(
                        if (live) "LIVE" else "PAPER",
                        color = fg,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .padding(vertical = 10.dp)
                            .fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Engine controls: Start/Stop, Paper/Live, kill switch and what is stopping the bot. */
@Composable
fun BotCard(
    bot: BotState,
    live: LiveReadiness,
    busy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onMode: (String) -> Unit,
    onKill: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Poly.colors
    PolyCard(modifier, borderColor = if (bot.isLive) c.live else null) {
        SectionTitle("Engine") {
            when {
                bot.killSwitch -> Pill("KILL SWITCH", c.down, c.downContainer)
                bot.running -> Pill("RUNNING", c.up, c.upContainer)
                else -> Pill("STOPPED", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
            }
        }
        if (bot.running) {
            Button(
                onClick = onStop,
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                Text("  Stop engine")
            }
        } else {
            Button(
                onClick = onStart,
                enabled = !busy && !bot.killSwitch,
                colors = if (bot.isLive) {
                    ButtonDefaults.buttonColors(containerColor = c.live, contentColor = androidx.compose.ui.graphics.Color.White)
                } else {
                    ButtonDefaults.buttonColors()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(if (bot.isLive) "  Start LIVE engine" else "  Start paper engine")
            }
        }
        Label("Mode")
        ModeToggle(selected = bot.mode, enabled = !busy, onSelect = onMode)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Kill switch", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Stops the bot and blocks new orders until cleared.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = bot.killSwitch, onCheckedChange = onKill, enabled = !busy)
        }
        if (!bot.riskHalt.isNullOrBlank()) {
            Surface(shape = RoundedCornerShape(12.dp), color = c.warnContainer) {
                Row(
                    Modifier.padding(Space.md),
                    horizontalArrangement = Arrangement.spacedBy(Space.md),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = c.warn)
                    Column {
                        Text("Risk halt", style = MaterialTheme.typography.titleSmall)
                        Text(bot.riskHalt, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (!live.ready && live.reasons.isNotEmpty()) {
            Text(
                "Live is not ready: " + live.reasons.joinToString("; "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun LastOrderCard(order: LastOrder?, modifier: Modifier = Modifier) {
    val c = Poly.colors
    PolyCard(modifier) {
        SectionTitle("Last order") { if (order != null) SidePill(order.side) }
        if (order == null) {
            Text(
                "No order yet. Start the engine and wait for an ask to reach the trigger inside the final window.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            KeyValueRow("Status", order.status.replace('_', ' '))
            KeyValueRow("Filled", "${Fmt.num(order.filledSize, 2)} sh @ ${Fmt.cents(order.avgPrice)}")
            KeyValueRow("Time", Fmt.time(order.ts))
            if (order.reason.isNotBlank()) {
                Text(order.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!order.error.isNullOrBlank()) {
                Text(order.error, style = MaterialTheme.typography.bodyMedium, color = c.down)
            }
        }
    }
}
