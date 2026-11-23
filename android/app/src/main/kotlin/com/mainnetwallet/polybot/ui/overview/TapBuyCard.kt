package com.mainnetwallet.polybot.ui.overview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mainnetwallet.polybot.data.model.BookSnapshot
import com.mainnetwallet.polybot.data.model.BotState
import com.mainnetwallet.polybot.data.model.MarketSnapshot
import com.mainnetwallet.polybot.data.model.TAP_MIN_ASK_DEFAULT
import com.mainnetwallet.polybot.data.model.TapLimits
import com.mainnetwallet.polybot.ui.Fmt
import com.mainnetwallet.polybot.ui.components.FlashBackground
import com.mainnetwallet.polybot.ui.components.Label
import com.mainnetwallet.polybot.ui.theme.Mono
import com.mainnetwallet.polybot.ui.theme.Poly
import com.mainnetwallet.polybot.ui.theme.Space

/** What `amount` pays if the side wins: shares bought at the ask, one dollar each. */
internal fun tapWin(amount: Double, ask: Double?): String {
    if (ask == null || ask.isNaN() || ask <= 0.0) return "win \u2014"
    val win = amount / ask
    return "win " + if (win >= 1000.0) Fmt.usdGrouped(win, 0) else Fmt.usd(win, if (win >= 100.0) 0 else 2)
}

/**
 * Side lock of the one-tap card: tapping UP or DOWN selects it, tapping the selected side again clears it.
 * Only the buy buttons of the selected side work; with no side selected every buy button is off.
 */
internal fun tapSideSelection(current: String?, tapped: String): String? = if (current == tapped) null else tapped

internal fun tapSideEnabled(selected: String?, side: String): Boolean = selected != null && selected == side

/**
 * A side whose best ask is under [minAsk] (40 cents) cannot be bought with a tap: its buy buttons stay off, so a
 * stray tap on a nearly-lost side can never buy. The server refuses such a buy too.
 */
internal fun tapAskTooLow(ask: Double?, minAsk: Double): Boolean =
    ask != null && !ask.isNaN() && ask < minAsk - 1e-9

/** 0.4 -> "40", 0.375 -> "37.5": the minimum share price is edited in cents. */
internal fun tapCentsText(price: Double): String {
    val cents = Math.round(price * 1000.0) / 10.0
    return if (cents == Math.floor(cents)) cents.toLong().toString() else cents.toString()
}

private fun tapMoney(amount: Double): String =
    if (amount == Math.floor(amount)) "$" + amount.toLong() else Fmt.usd(amount, 2)

/**
 * One-tap buy, kept small so the positions under it stay on the screen: a header line and one row per side.
 * One tap on a dollar button is one market buy (no confirm step). The buttons lock for a second after every tap
 * ([locked]); the shares are tagged "One-tap" and held to settlement or sold by hand from the position box.
 */
@Composable
fun TapBuyCard(
    market: MarketSnapshot?,
    bot: BotState,
    up: BookSnapshot?,
    down: BookSnapshot?,
    upArmed: Boolean = false,
    downArmed: Boolean = false,
    balance: Double,
    amounts: List<Double>,
    limits: TapLimits?,
    /** The owner's own lowest share price (One-tap settings, saved on this phone); null = use the server's. */
    minAskPref: Double?,
    locked: Boolean,
    busyKey: String?,
    enabled: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onTap: (side: String, amount: Double, minAsk: Double) -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onSaveSettings: (amounts: List<Double>, enabled: Boolean, minAsk: Double) -> Unit,
    modifier: Modifier = Modifier,
    /** Shown in the header right after the title, on the left (the "Limit" pill lives here). */
    headerExtra: @Composable () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val c = Poly.colors
    var editing by remember { mutableStateOf(false) }
    // "UP" / "DOWN" / null: only the selected side can buy; null (the start) = every buy button is off
    var selectedSide by rememberSaveable { mutableStateOf<String?>(null) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "tapChevron")
    val ended = market == null || market.remainingSeconds <= 0.0
    val minAsk = minAskPref ?: limits?.minAsk ?: TAP_MIN_ASK_DEFAULT
    val blocked: String? = when {
        !enabled -> "One-tap is OFF: tap the OFF button to turn it on"
        bot.killSwitch -> "Kill switch is on"
        !bot.running -> "Start the bot to buy"
        ended -> "Waiting for the next market"
        else -> null
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainer,
    ) {
        Column(
            Modifier.padding(horizontal = Space.md, vertical = Space.sm),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 32.dp)
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                Label("One-tap buy")
                headerExtra()
                Spacer(Modifier.weight(1f))
                // The ON / OFF button of one-tap buy: one click turns it OFF, the next turns it ON.
                // ON = tinted and outlined (green in paper, red in live), OFF = grey. The balance next to it stays plain text.
                val modeTone = if (bot.isLive) scheme.error else c.up
                Text(
                    if (enabled) "ON" else "OFF",
                    style = Mono.small.copy(fontWeight = FontWeight.Bold),
                    color = if (enabled) modeTone else scheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (enabled) modeTone.copy(alpha = 0.16f) else scheme.surfaceContainerHighest)
                        .border(
                            BorderStroke(1.dp, if (enabled) modeTone else scheme.outlineVariant),
                            RoundedCornerShape(8.dp),
                        )
                        .clickable { onSetEnabled(!enabled) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
                Text(
                    Fmt.usdGrouped(balance),
                    style = Mono.small,
                    color = if (bot.isLive) scheme.error else scheme.onSurfaceVariant,
                )
                if (expanded) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "One-tap settings",
                        tint = scheme.primary,
                        modifier = Modifier
                            .clickable { editing = true }
                            .padding(4.dp)
                            .size(20.dp),
                    )
                }
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse one-tap buy" else "Expand one-tap buy",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(chevron),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("UP" to up, "DOWN" to down).forEach { (side, book) ->
                        val ask = book?.bestAsk
                        val tooLow = tapAskTooLow(ask, minAsk)
                        val isUp = side == "UP"
                        val tone = if (isUp) c.up else c.down
                        val container = if (isUp) c.upContainer else c.downContainer
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val selected = selectedSide == side
                            // tap the side to select it (the other one is deselected); tap it again to deselect.
                            // The tile also carries the price (best ask), the bid and the ARMED flag, so the separate odds card is gone.
                            val armed = if (isUp) upArmed else downArmed
                            Column(
                                Modifier
                                    .width(100.dp)
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) container else scheme.surfaceContainer)
                                    .border(
                                        BorderStroke(if (selected) 2.dp else 1.dp, if (selected) tone else scheme.outlineVariant),
                                        RoundedCornerShape(10.dp),
                                    )
                                    .clickable { selectedSide = tapSideSelection(selectedSide, side) }
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (isUp) "UP" else "DOWN",
                                        style = Mono.body.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp),
                                        color = if (selected) tone else tone.copy(alpha = 0.6f),
                                        maxLines = 1,
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        "bid " + Fmt.num(book?.bestBid?.times(100.0), 0),
                                        style = Mono.small.copy(fontSize = 11.sp),
                                        color = scheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    FlashBackground(ask) {
                                        Text(
                                            Fmt.cents(ask),
                                            style = Mono.large.copy(fontSize = 17.sp),
                                            color = if (tooLow) scheme.tertiary else tone,
                                            maxLines = 1,
                                        )
                                    }
                                    Spacer(Modifier.weight(1f))
                                    if (armed) {
                                        Text(
                                            "ARMED",
                                            style = Mono.small.copy(fontWeight = FontWeight.Bold, fontSize = 9.sp),
                                            color = c.warn,
                                            maxLines = 1,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(50))
                                                .background(c.warnContainer)
                                                .padding(horizontal = 5.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                            }
                            amounts.forEach { amount ->
                                val key = "$side:$amount"
                                Button(
                                    onClick = { if (tapSideEnabled(selectedSide, side) && !tooLow) onTap(side, amount, minAsk) },
                                    enabled = enabled && blocked == null && tapSideEnabled(selectedSide, side) &&
                                        ask != null && !tooLow && !locked && busyKey == null,
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = tone),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 38.dp),
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            if (busyKey == key) "\u2026" else tapMoney(amount),
                                            style = Mono.body.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp),
                                            maxLines = 1,
                                        )
                                        Text(
                                            tapWin(amount, ask),
                                            style = Mono.small.copy(fontSize = 9.sp),
                                            maxLines = 1,
                                            textAlign = TextAlign.Center,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (blocked != null) {
                        Text(blocked, style = MaterialTheme.typography.bodySmall, color = scheme.tertiary)
                    }
                }
            }
        }
    }

    if (editing) {
        TapSettingsDialog(
            amounts = amounts,
            enabled = enabled,
            min = limits?.minUsdc ?: 1.0,
            max = limits?.maxUsdc ?: 100.0,
            minAsk = minAsk,
            onDismiss = { editing = false },
            onSave = { newAmounts, on, newMinAsk ->
                editing = false
                onSaveSettings(newAmounts, on, newMinAsk)
            },
        )
    }
}

/** Centered settings dialog: the ON/OFF switch (default OFF) and the three buy amounts. Nothing changes until Save. */
@Composable
private fun TapSettingsDialog(
    amounts: List<Double>,
    enabled: Boolean,
    min: Double,
    max: Double,
    minAsk: Double,
    onDismiss: () -> Unit,
    onSave: (List<Double>, Boolean, Double) -> Unit,
) {
    var on by remember { mutableStateOf(enabled) }
    var minCents by remember { mutableStateOf(tapCentsText(minAsk)) }
    val minCentsValue = minCents.trim().toDoubleOrNull()
    val minValid = minCentsValue != null && minCentsValue >= 0.0 && minCentsValue <= 99.0
    var drafts by remember { mutableStateOf(amounts.map { tapMoney(it).removePrefix("$") }) }
    val parsed = drafts.map { it.trim().toDoubleOrNull() }
    val valid = parsed.all { it != null && it >= min && it <= max } && minValid
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("One-tap buy settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (on) "One-tap buy: ON" else "One-tap buy: OFF",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (on) scheme.primary else scheme.onSurface,
                        )
                        Text(
                            if (on) "A tap on a dollar button buys at once." else "Buttons are locked, a stray tap cannot buy.",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = on, onCheckedChange = { on = it })
                }
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Text("Buy amounts (USDC)", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        drafts.forEachIndexed { i, value ->
                            OutlinedTextField(
                                value = value,
                                onValueChange = { text -> drafts = drafts.toMutableList().also { it[i] = text } },
                                prefix = { Text("$") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Text(
                        "Each from " + tapMoney(min) + " to " + tapMoney(max) + ". Saved on this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Text("Lowest share price", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = minCents,
                        onValueChange = { minCents = it },
                        suffix = { Text("\u00A2") },
                        singleLine = true,
                        isError = !minValid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(140.dp),
                    )
                    Text(
                        "The buttons of a side priced under this stay off (0 to 99, 0 = no minimum). Default 40.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(parsed.map { it ?: 1.0 }, on, (minCentsValue ?: 40.0) / 100.0) },
                enabled = valid,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
