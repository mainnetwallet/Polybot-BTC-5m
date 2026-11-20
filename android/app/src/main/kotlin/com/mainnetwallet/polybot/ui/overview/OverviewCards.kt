package com.mainnetwallet.polybot.ui.overview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.mainnetwallet.polybot.data.model.BuyGuardState
import com.mainnetwallet.polybot.data.model.LiveReadiness
import com.mainnetwallet.polybot.data.model.MarketSnapshot
import com.mainnetwallet.polybot.data.model.Modes
import com.mainnetwallet.polybot.data.model.PositionRow
import com.mainnetwallet.polybot.data.model.SellableHolding
import com.mainnetwallet.polybot.data.model.StrategyState
import com.mainnetwallet.polybot.domain.ringStage
import com.mainnetwallet.polybot.ui.Fmt
import com.mainnetwallet.polybot.ui.components.Dot
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

// Overview layout, top to bottom: hero (countdown, BTC and account numbers), odds (UP | DOWN) and the position
// box with its instant Sell buttons. The engine controls live in the top bar.

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
    compact: Boolean = false,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 1.dp)) {
        Label(label)
        Text(
            value,
            style = Mono.body.copy(fontWeight = FontWeight.Medium, fontSize = if (compact) 13.sp else 14.sp),
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

/**
 * Countdown ring for the hero card: the arc is the share of the window that is left, with the state, the whole
 * seconds left and a caption inside. It has no size of its own: it fills what the caller gives it (a square),
 * and the text scales with that size.
 */
@Composable
private fun HeroRing(
    fraction: Float,
    remainingSeconds: Double,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val track = scheme.surfaceVariant
    val seconds = Math.ceil(Math.max(0.0, remainingSeconds)).toInt()
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val d = maxWidth.value
        Canvas(Modifier.fillMaxSize()) {
            val stroke = (d * 0.07f).dp.toPx()
            val inset = stroke / 2
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            if (fraction > 0f) {
                drawArc(color, -90f, 360f * fraction, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = (d * 0.105f).sp, letterSpacing = 0.6.sp),
                color = scheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                seconds.toString(),
                style = Mono.large.copy(fontSize = (d * 0.30f).sp, fontWeight = FontWeight.Light),
                color = color,
                maxLines = 1,
            )
            Text(
                "SEC LEFT",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = (d * 0.085f).sp, letterSpacing = 0.5.sp),
                color = scheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * Hero card: the 5-minute window countdown, the BTC price and its gap to the price to beat, and the account
 * numbers (balance, PnL, trades) in one place. The clock follows the server, not the phone.
 * The card tints amber while a stage window is open.
 */
@Composable
fun HeroCard(
    market: MarketSnapshot?,
    btc: BtcState?,
    btcHistory: List<Float>,
    account: AccountState,
    inWindow: Boolean,
    strategy: StrategyState,
    endLocalMs: Long?,
    modifier: Modifier = Modifier,
) {
    val c = Poly.colors
    val scheme = MaterialTheme.colorScheme
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(market?.marketId) {
        if (market != null) {
            while (true) {
                now = System.currentTimeMillis()
                delay(100)
            }
        }
    }
    // The countdown is the backend's remaining_seconds (Polymarket time), ticking smoothly on the phone between frames.
    val remaining: Double? = market?.let { m ->
        if (endLocalMs != null) (endLocalMs - now) / 1000.0 else m.remainingSeconds
    }
    // Only the hero ring has its own colors, one per stage (violet while waiting, then stage 0 to 3).
    val stage = ringStage(inWindow, remaining, strategy)
    val ringColor = c.ring(stage)
    // Accent drives the clock, progress bar and card tint (amber while a stage window is open, primary while waiting).
    val accent = if (stage != null) c.warn else scheme.primary
    val fraction: Float = if (market != null && remaining != null && market.durationSeconds > 0) {
        (remaining / market.durationSeconds).coerceIn(0.0, 1.0).toFloat()
    } else {
        0f
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = scheme.surfaceContainer,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
    ) {
        Box(Modifier.background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.16f), Color.Transparent)))) {
            Column(Modifier.padding(horizontal = Space.md, vertical = Space.sm), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                // The ring fills the free middle of the top block: as tall as the countdown + BTC rows, drawn behind them.
                Box(Modifier.fillMaxWidth()) {
                    if (market != null && remaining != null) {
                        Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                            HeroRing(
                                fraction = fraction,
                                remainingSeconds = remaining,
                                label = if (stage != null) "Stage $stage" else "Waiting",
                                color = ringColor,
                                modifier = Modifier.fillMaxHeight().aspectRatio(1f),
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    ) {
                        Label(if (market == null) "Waiting for the next window" else "Closes in", Modifier.weight(1f))
                        if (account.positionsAwaitingSettlement > 0) {
                            val redeem = if (account.pendingRedeems > 0) " \u00B7 ${account.pendingRedeems} redeem" else ""
                            Pill("${account.positionsAwaitingSettlement} settling$redeem", scheme.onSurfaceVariant, scheme.surfaceVariant)
                        }
                        if (market != null) {
                            if (stage != null) {
                                Pill("STAGE $stage", c.warn, c.warnContainer)
                            } else {
                                Pill("OBSERVING", scheme.onSecondaryContainer, scheme.secondaryContainer)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (remaining == null) "--:--" else Fmt.clock(remaining),
                                style = Mono.xlarge.copy(fontSize = 30.sp),
                                color = if (market == null) scheme.onSurfaceVariant else accent,
                                maxLines = 1,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Label("BTC")
                            Text(
                                Fmt.usdGrouped(btc?.price),
                                style = Mono.large.copy(fontSize = 19.sp),
                                maxLines = 1,
                            )
                            val feedText: String
                            val feedColor: Color
                            when {
                                btc == null || !btc.enabled -> {
                                    feedText = "off"
                                    feedColor = scheme.onSurfaceVariant
                                }
                                !btc.connected -> {
                                    feedText = "disconnected"
                                    feedColor = c.down
                                }
                                btc.stale -> {
                                    feedText = "stale"
                                    feedColor = c.warn
                                }
                                else -> {
                                    feedText = "live"
                                    feedColor = c.up
                                }
                            }
                            val ageMs = btc?.ageMs
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Dot(feedColor, size = 6.dp)
                                Text(
                                    if (ageMs == null) feedText else feedText + " " + Fmt.num(ageMs / 1000.0, 1) + " s",
                                    style = Mono.small,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(scheme.surfaceVariant, RoundedCornerShape(50)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(accent, RoundedCornerShape(50)),
                    )
                }
                if (btc != null) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    ) {
                        val diff = btc.diff
                        if (diff != null) {
                            if (diff > 0) {
                                Pill("Gap " + Fmt.signedUsd(diff), c.up, c.upContainer)
                            } else if (diff < 0) {
                                Pill("Gap " + Fmt.signedUsd(diff), c.down, c.downContainer)
                            } else {
                                Pill("Gap " + Fmt.signedUsd(diff), scheme.onSurfaceVariant, scheme.surfaceVariant)
                            }
                        }
                        Text(
                            "To beat " + Fmt.usdGrouped(btc.priceToBeat),
                            style = Mono.small,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!btc.lastError.isNullOrBlank()) {
                        Text(btc.lastError, style = MaterialTheme.typography.bodySmall, color = c.down, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Sparkline(
                    btcHistory,
                    scheme.primary,
                    height = 18.dp,
                    reference = btc?.priceToBeat?.toFloat(),
                    description = "BTC price chart",
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(scheme.outlineVariant.copy(alpha = 0.6f)),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column(Modifier.weight(1f)) {
                        Label("Balance")
                        Text(Fmt.usdGrouped(account.balance), style = Mono.large.copy(fontSize = 17.sp), maxLines = 1)
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Label("Daily PnL")
                        Text(
                            Fmt.signedUsd(account.dailyPnl),
                            style = Mono.large.copy(fontSize = 17.sp),
                            color = pnlColor(account.dailyPnl),
                            maxLines = 1,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    MiniStat("Realized", Fmt.signedUsd(account.realizedPnl), Modifier.weight(1f), valueColor = pnlColor(account.realizedPnl))
                    MiniStat("Unrealized", Fmt.signedUsd(account.unrealizedPnl), Modifier.weight(1f), valueColor = pnlColor(account.unrealizedPnl))
                    MiniStat("Trades", account.tradesToday.toString(), Modifier.weight(1f))
                }
            }
        }
    }
}

/** UP and DOWN side by side under a split bar. Each tile: best ask (flashes), bid, size, armed flag, ask history. */
@Composable
fun OddsCard(
    up: BookSnapshot?,
    down: BookSnapshot?,
    upArmed: Boolean,
    downArmed: Boolean,
    upHistory: List<Float>,
    downHistory: List<Float>,
    modifier: Modifier = Modifier,
) {
    val upAsk = up?.bestAsk
    val downAsk = down?.bestAsk
    val upShare: Float? = if (upAsk != null && downAsk != null && upAsk + downAsk > 0.0) {
        (upAsk / (upAsk + downAsk)).toFloat()
    } else {
        null
    }
    CompactCard(modifier) {
        SplitBar(upShare ?: 0.5f)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            OddsTile("UP", up, upArmed, upHistory, Modifier.weight(1f))
            OddsTile("DOWN", down, downArmed, downHistory, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SplitBar(upShare: Float, modifier: Modifier = Modifier) {
    val c = Poly.colors
    val w = upShare.coerceIn(0.03f, 0.97f)
    Row(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(50)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            Modifier
                .weight(w)
                .fillMaxHeight()
                .background(c.up),
        )
        Box(
            Modifier
                .weight(1f - w)
                .fillMaxHeight()
                .background(c.down),
        )
    }
}

@Composable
private fun OddsTile(side: String, book: BookSnapshot?, armed: Boolean, history: List<Float>, modifier: Modifier = Modifier) {
    val c = Poly.colors
    val up = side.equals("up", ignoreCase = true)
    val tone = if (up) c.up else c.down
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = if (up) c.upContainer else c.downContainer,
        border = if (armed) BorderStroke(1.5.dp, c.warn) else null,
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(side.uppercase(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = tone)
                Spacer(Modifier.weight(1f))
                if (armed) Pill("ARMED", c.warn, c.warnContainer)
            }
            FlashBackground(book?.bestAsk) {
                Text(
                    Fmt.cents(book?.bestAsk),
                    style = Mono.large.copy(fontSize = 22.sp),
                    color = tone,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "Bid " + Fmt.cents(book?.bestBid),
                    style = Mono.small,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Size " + Fmt.num(book?.askSize, 0),
                    style = Mono.small,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Sparkline(history, tone, height = 14.dp, description = "$side ask history")
        }
    }
}

/** Two big segments: PAPER | LIVE. */
@Composable
fun ModeToggle(
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
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
                            .padding(vertical = if (compact) 6.dp else 10.dp)
                            .fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** A one-line message (risk halt, kill switch on). */
@Composable
private fun NoticeRow(
    text: String,
    color: Color,
    container: Color,
    maxLines: Int = 2,
    action: (@Composable () -> Unit)? = null,
) {
    Surface(shape = RoundedCornerShape(10.dp), color = container, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = Space.md, vertical = Space.sm),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            action?.invoke()
        }
    }
}

/** "42 min", "1 h 05 min", "<1 min": the time left until buying switches itself back on. */
private fun fmtLeft(minutes: Long): String = when {
    minutes < 1 -> "<1 min"
    minutes < 60 -> "$minutes min"
    else -> "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')} min"
}

/** Risk halt, kill switch and "live not ready" messages for the Overview. Draws nothing when there is none. */
@Composable
fun EngineNotices(
    bot: BotState,
    live: LiveReadiness,
    modifier: Modifier = Modifier,
    buyGuard: BuyGuardState? = null,
    onKeepOff: (() -> Unit)? = null,
) {
    val c = Poly.colors
    val hasRisk = !bot.riskHalt.isNullOrBlank()
    val liveNotReady = !live.ready && live.reasons.isNotEmpty()
    val buysOff = buyGuard != null && !buyGuard.buysEnabled
    if (!hasRisk && !bot.killSwitch && !liveNotReady && !buysOff) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        if (buyGuard != null && buysOff) {
            val why = buyGuard.reason?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""
            val left = buyGuard.minutesUntilResume(System.currentTimeMillis())
            val resume = when {
                left == null -> "It stays off until you tap BUY."
                buyGuard.isManualOff -> "It comes back by itself next day (in ${fmtLeft(left)}); tap BUY to turn it on earlier."
                else -> "Back on by itself in ${fmtLeft(left)} unless you act; tap BUY to turn it on now."
            }
            val keepOff = onKeepOff
            NoticeRow(
                "Buying is OFF (${buyGuard.offLabel})$why. Exits keep running. $resume",
                c.down,
                c.downContainer,
                maxLines = 5,
                action = if (keepOff != null && !buyGuard.isManualOff) {
                    { TextButton(onClick = keepOff) { Text("Off today") } }
                } else {
                    null
                },
            )
        }
        if (hasRisk) {
            NoticeRow("Risk halt: ${bot.riskHalt}", c.warn, c.warnContainer)
        } else if (bot.killSwitch) {
            NoticeRow("Kill switch is on. New orders are blocked until you clear it.", c.down, c.downContainer)
        }
        if (liveNotReady) {
            Text(
                "Live is not ready: " + live.reasons.joinToString("; "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Buy switch: shows BUY ON while buying is allowed and a highlighted BUY OFF while it is switched off. One tap flips it. */
@Composable
private fun BuyButton(buysOn: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = Poly.colors
    val container = if (buysOn) MaterialTheme.colorScheme.surfaceVariant else c.down
    val content = if (buysOn) MaterialTheme.colorScheme.onSurfaceVariant else Color.White
    Surface(
        modifier = modifier
            .size(width = 52.dp, height = 40.dp)
            .toggleable(value = buysOn, enabled = enabled, role = Role.Switch, onValueChange = onToggle),
        shape = RoundedCornerShape(12.dp),
        color = container,
        contentColor = content,
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("BUY", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text(if (buysOn) "ON" else "OFF", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
    }
}

/** Kill switch button: one tap engages it (emergency stop), a second tap asks to clear it. */
@Composable
private fun KillButton(active: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = Poly.colors
    val container = if (active) c.live else MaterialTheme.colorScheme.errorContainer
    val content = if (active) Color.White else MaterialTheme.colorScheme.onErrorContainer
    Surface(
        modifier = modifier
            .size(width = 52.dp, height = 40.dp)
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
            Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(if (active) "CLEAR" else "KILL", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Engine controls in one 40dp row: Start/Stop, PAPER | LIVE and the kill switch. They live in the top bar,
 * so they are reachable from every tab. The state and the confirmation dialogs are in [EngineControls].
 */
@Composable
fun EngineControlsRow(
    bot: BotState,
    busy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onMode: (String) -> Unit,
    onKill: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    buysOn: Boolean = true,
    onBuys: (Boolean) -> Unit = {},
) {
    val c = Poly.colors
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                    .height(40.dp),
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
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
                    .height(40.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(" Start", maxLines = 1)
            }
        }
        ModeToggle(selected = bot.mode, enabled = !busy, onSelect = onMode, modifier = Modifier.weight(1.5f), compact = true)
        BuyButton(buysOn = buysOn, enabled = !busy, onToggle = onBuys)
        KillButton(active = bot.killSwitch, enabled = !busy, onToggle = onKill)
    }
}

/** Key used in the "selling" set while a Sell all request is on its way. */
internal const val SELL_ALL_KEY = "__all__"

/** Key of one stage row in the "selling" set, used to block a second tap on the same row while its request is on the way. */
internal fun sellKey(h: SellableHolding): String = "${h.marketId}:${h.side}:${h.stage}"

@Composable
private fun SellButton(label: String, enabled: Boolean, onClick: () -> Unit, compact: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = if (compact) 12.dp else 14.dp, vertical = 2.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = scheme.errorContainer,
            contentColor = scheme.onErrorContainer,
        ),
        modifier = Modifier.heightIn(min = if (compact) 32.dp else 36.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** (bid - entry) x shares: what this stage would make or lose if it sold at the best bid right now. */
internal fun stagePnl(entry: Double?, bid: Double?, size: Double): Double? =
    if (entry == null || bid == null) null else (bid - entry) * size

/** The same gain or loss as a percent of the entry price, e.g. "+8.4%". */
internal fun stagePct(entry: Double?, bid: Double?): String? {
    if (entry == null || bid == null || entry <= 0.0) return null
    val pct = (bid - entry) / entry * 100.0
    return (if (pct > 0) "+" else "") + Fmt.percent(pct)
}

/**
 * Position box: what is held in the live market, one block per side (shares and total PnL) with one card per
 * stage under it: its shares, what it paid (Entry), the best Bid and its own live PnL (green gain, red loss),
 * and its own Sell button. Sell all is in the header.
 * A tap sells at the market right away: there is no confirm dialog or sheet and no extra delay. `selling`
 * only disables a button while its own request is on the way, so a double tap cannot send it twice.
 */
@Composable
fun PositionBoxCard(
    marketId: String?,
    positions: List<PositionRow>,
    sellable: List<SellableHolding>,
    upBook: BookSnapshot?,
    downBook: BookSnapshot?,
    selling: Set<String>,
    onSell: (SellableHolding) -> Unit,
    onSellAll: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val open = if (marketId == null) {
        emptyList()
    } else {
        positions
            .filter { it.marketId == marketId && it.status == "open" && it.size > 0 }
            .sortedByDescending { it.side.equals("UP", ignoreCase = true) }
    }
    val sellableHere = if (marketId == null) emptyList() else sellable.filter { it.marketId == marketId }
    val sellingAll = SELL_ALL_KEY in selling

    CompactCard(modifier) {
        CardHeader("Position") {
            if (marketId != null && sellableHere.isNotEmpty()) {
                SellButton(
                    label = if (sellingAll) "Selling\u2026" else "Sell all",
                    enabled = !sellingAll,
                    onClick = { onSellAll(marketId) },
                )
            }
        }
        if (open.isEmpty()) {
            Text(
                "No open position in this market",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        } else {
            open.forEach { p ->
                val bid = (if (p.side.equals("UP", ignoreCase = true)) upBook else downBook)?.bestBid
                val stages = sellableHere.filter { it.side.equals(p.side, ignoreCase = true) }
                // Live PnL per stage: (best bid now - what this stage paid) x its shares. It follows every book update.
                val stagePnl = stages.map { stagePnl(it.entryPrice, bid, it.size) }
                val total = if (stages.isNotEmpty() && stagePnl.all { it != null }) stagePnl.sumOf { it ?: 0.0 } else p.unrealizedPnl
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    SidePill(p.side)
                    Text(
                        "${Fmt.num(p.size, 2)} sh",
                        style = Mono.small,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        Fmt.signedUsd(total),
                        style = Mono.body.copy(fontWeight = FontWeight.Medium),
                        color = pnlColor(total),
                    )
                }
                stages.forEachIndexed { i, h ->
                    val busy = sellKey(h) in selling
                    val pnl = stagePnl[i]
                    // One card per stage: "Stage N", its shares and its Sell button on top, Entry / Bid / PnL under them.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(scheme.surfaceContainerHigh)
                            .padding(horizontal = Space.sm, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Space.sm),
                        ) {
                            Text("Stage ${h.stage}", style = Mono.small, color = scheme.onSurfaceVariant)
                            Text("${Fmt.num(h.size, 2)} sh", style = Mono.small, modifier = Modifier.weight(1f))
                            SellButton(
                                label = if (busy) "\u2026" else "Sell",
                                enabled = !busy,
                                onClick = { onSell(h) },
                                compact = true,
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            MiniStat("Entry", Fmt.cents(h.entryPrice), Modifier.weight(1f), compact = true)
                            MiniStat("Bid", Fmt.cents(bid), Modifier.weight(1f), compact = true)
                            MiniStat(
                                "PnL",
                                Fmt.signedUsd(pnl),
                                Modifier.weight(1f),
                                valueColor = pnlColor(pnl),
                                hint = stagePct(h.entryPrice, bid),
                                compact = true,
                            )
                        }
                    }
                }
                if (stages.isEmpty()) {
                    // held, but nothing sellable right now (a sell is in flight, kill switch, ...): show the whole position
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        MiniStat("Avg", Fmt.cents(p.avgPrice), Modifier.weight(1f))
                        MiniStat("Bid", Fmt.cents(bid), Modifier.weight(1f))
                        MiniStat("PnL", Fmt.signedUsd(p.unrealizedPnl), Modifier.weight(1f), valueColor = pnlColor(p.unrealizedPnl))
                    }
                }
            }
        }
    }
}
