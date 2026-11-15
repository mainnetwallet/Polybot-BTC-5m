package com.mainnetwallet.polybot.ui.overview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.mainnetwallet.polybot.ui.components.FlashBackground
import com.mainnetwallet.polybot.ui.components.Label
import com.mainnetwallet.polybot.ui.components.Pill
import com.mainnetwallet.polybot.ui.components.SidePill
import com.mainnetwallet.polybot.ui.components.Sparkline
import com.mainnetwallet.polybot.ui.components.pnlColor
import com.mainnetwallet.polybot.ui.theme.Mono
import com.mainnetwallet.polybot.ui.theme.Poly
import com.mainnetwallet.polybot.ui.theme.Space
import kotlinx.coroutines.delay

// The Overview is built to fit one phone screen: compact cards (12dp padding), three-column stat rows and
// the engine controls pinned in a dock at the bottom, so nothing important needs scrolling.

/** Compact card used on the Overview: 12dp padding, 6dp between children. */
@Composable
private fun CompactCard(
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = borderColor?.let { BorderStroke(1.dp, it) },
    ) {
        Column(
            modifier = Modifier.padding(Space.md),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

/** Small caps title on the left, status on the right. Fixed min height so the card does not jump. */
@Composable
private fun CardHeader(title: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Label(title, Modifier.weight(1f))
        trailing()
    }
}

/** Label over a value; one line, so three of them fit in a row. */
@Composable
private fun MiniStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
    hint: String? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Label(label)
        Text(
            value,
            style = Mono.body.copy(fontWeight = FontWeight.Medium),
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Countdown ring for the current 5-minute window. The clock follows the server, not the phone. */
@Composable
fun MarketCard(market: MarketSnapshot?, inWindow: Boolean, offsetMs: Long, modifier: Modifier = Modifier) {
    val c = Poly.colors
    CompactCard(modifier) {
        if (market == null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                Icon(Icons.Filled.HourglassEmpty, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                Column {
                    Text("No active market", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Waiting for the next 5-minute BTC window.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
                    ringSize = 96.dp,
                    strokeWidth = 8.dp,
                    valueStyle = Mono.large.copy(fontSize = 22.sp),
                    caption = "left",
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CardHeader("Market") {
                        if (inWindow) {
                            Pill("FINAL WINDOW", c.warn, c.warnContainer)
                        } else {
                            Pill("OBSERVING", MaterialTheme.colorScheme.onSecondaryContainer, MaterialTheme.colorScheme.secondaryContainer)
                        }
                    }
                    Text(
                        market.slug.ifBlank { Fmt.shortId(market.marketId) },
                        style = Mono.small,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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

/** One outcome: best ask (flashes on change), bid and sizes, armed flag and a small ask history. */
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
    CompactCard(modifier, borderColor = if (armed) c.warn else null) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SidePill(side)
            Spacer(Modifier.weight(1f))
            if (armed) Pill("ARMED", c.warn, c.warnContainer) else Label("Best ask")
        }
        FlashBackground(book?.bestAsk) {
            Text(
                Fmt.cents(book?.bestAsk),
                style = Mono.large.copy(fontSize = 24.sp),
                color = tone,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        Text(
            "Bid ${Fmt.cents(book?.bestBid)} \u00B7 ${Fmt.num(book?.bidSize, 0)}/${Fmt.num(book?.askSize, 0)}",
            style = Mono.small,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Sparkline(history, tone, height = 24.dp, description = "$side ask history")
    }
}

/** BTC price against the price to beat, with the gap, a sparkline and a feed health flag. */
@Composable
fun BtcCard(btc: BtcState?, history: List<Float>, modifier: Modifier = Modifier) {
    val c = Poly.colors
    val ageMs = btc?.ageMs
    CompactCard(modifier) {
        CardHeader("BTC") {
            if (btc != null && btc.enabled && ageMs != null) {
                Text(
                    Fmt.num(ageMs / 1000.0, 1) + " s",
                    style = Mono.small,
                    color = if (btc.stale) c.warn else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                MiniStat("Price", Fmt.usdGrouped(btc.price), Modifier.weight(1f))
                MiniStat("To beat", Fmt.usdGrouped(btc.priceToBeat), Modifier.weight(1f))
                MiniStat(
                    "Gap",
                    if (btc.diff == null) "\u2014" else Fmt.signedUsd(btc.diff),
                    Modifier.weight(1f),
                    valueColor = pnlColor(btc.diff),
                )
            }
            Sparkline(history, MaterialTheme.colorScheme.primary, height = 32.dp, reference = btc.priceToBeat?.toFloat(), description = "BTC price chart")
            if (!btc.lastError.isNullOrBlank()) {
                Text(btc.lastError, style = MaterialTheme.typography.bodySmall, color = c.down, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun AccountCard(account: AccountState, modifier: Modifier = Modifier) {
    CompactCard(modifier) {
        CardHeader("Account") {
            Text(account.mode.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            MiniStat("Balance", Fmt.usdGrouped(account.balance), Modifier.weight(1f))
            MiniStat("Daily PnL", Fmt.signedUsd(account.dailyPnl), Modifier.weight(1f), valueColor = pnlColor(account.dailyPnl))
            MiniStat("Realized", Fmt.signedUsd(account.realizedPnl), Modifier.weight(1f), valueColor = pnlColor(account.realizedPnl))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            MiniStat("Unrealized", Fmt.signedUsd(account.unrealizedPnl), Modifier.weight(1f), valueColor = pnlColor(account.unrealizedPnl))
            MiniStat("Trades", account.tradesToday.toString(), Modifier.weight(1f))
            MiniStat(
                "Settling",
                account.positionsAwaitingSettlement.toString(),
                Modifier.weight(1f),
                hint = if (account.pendingRedeems > 0) "${account.pendingRedeems} redeem" else null,
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

/** One line about the last order: side, status, fill and time. */
@Composable
fun LastOrderCard(order: LastOrder?, modifier: Modifier = Modifier) {
    val c = Poly.colors
    CompactCard(modifier) {
        if (order == null) {
            Label("Last order")
            Text(
                "No order yet. The engine buys when an ask reaches the trigger inside the final window.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Label("Last order \u00B7 ${Fmt.time(order.ts)}")
                    Text(
                        "${order.status.replace('_', ' ')} \u00B7 ${Fmt.num(order.filledSize, 2)} sh @ ${Fmt.cents(order.avgPrice)}",
                        style = Mono.small,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (order.reason.isNotBlank()) {
                        Text(
                            order.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!order.error.isNullOrBlank()) {
                        Text(order.error, style = MaterialTheme.typography.bodySmall, color = c.down, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                SidePill(order.side)
            }
        }
    }
}

/** A one-line message above the controls (risk halt, kill switch on). */
@Composable
private fun DockNotice(text: String, color: Color, container: Color) {
    Surface(shape = RoundedCornerShape(10.dp), color = container, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = Space.md, vertical = Space.sm),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Square kill switch button: one tap engages it (emergency stop), a second tap asks to clear it. */
@Composable
private fun KillButton(active: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = Poly.colors
    val container = if (active) c.live else MaterialTheme.colorScheme.errorContainer
    val content = if (active) Color.White else MaterialTheme.colorScheme.onErrorContainer
    Surface(
        modifier = modifier
            .size(width = 60.dp, height = 48.dp)
            .toggleable(value = active, enabled = enabled, role = Role.Switch, onValueChange = onToggle),
        shape = RoundedCornerShape(12.dp),
        color = container,
        contentColor = content,
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(if (active) "CLEAR" else "KILL", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Engine controls pinned at the bottom of the Overview: Start/Stop, PAPER | LIVE and the kill switch in
 * one row, so they are always reachable without scrolling. Risk halts and "live not ready" show above.
 */
@Composable
fun EngineDock(
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
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
    ) {
        Column(
            Modifier.padding(horizontal = Space.md, vertical = Space.sm),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            if (!bot.riskHalt.isNullOrBlank()) {
                DockNotice("Risk halt: ${bot.riskHalt}", c.warn, c.warnContainer)
            } else if (bot.killSwitch) {
                DockNotice("Kill switch is on. New orders are blocked until you clear it.", c.down, c.downContainer)
            }
            if (!live.ready && live.reasons.isNotEmpty()) {
                Text(
                    "Live is not ready: " + live.reasons.joinToString("; "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (bot.running) {
                    Button(
                        onClick = onStop,
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                        contentPadding = PaddingValues(horizontal = Space.sm),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text(" Stop", maxLines = 1)
                    }
                } else {
                    Button(
                        onClick = onStart,
                        enabled = !busy && !bot.killSwitch,
                        colors = if (bot.isLive) {
                            ButtonDefaults.buttonColors(containerColor = c.live, contentColor = Color.White)
                        } else {
                            ButtonDefaults.buttonColors()
                        },
                        contentPadding = PaddingValues(horizontal = Space.sm),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text(" Start", maxLines = 1)
                    }
                }
                ModeToggle(selected = bot.mode, enabled = !busy, onSelect = onMode, modifier = Modifier.weight(1.4f))
                KillButton(active = bot.killSwitch, enabled = !busy, onToggle = onKill)
            }
        }
    }
}
