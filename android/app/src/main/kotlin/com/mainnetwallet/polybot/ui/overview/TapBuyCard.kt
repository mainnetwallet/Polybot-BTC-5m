package com.mainnetwallet.polybot.ui.overview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mainnetwallet.polybot.data.model.BookSnapshot
import com.mainnetwallet.polybot.data.model.BotState
import com.mainnetwallet.polybot.data.model.MarketSnapshot
import com.mainnetwallet.polybot.data.model.TapLimits
import com.mainnetwallet.polybot.ui.Fmt
import com.mainnetwallet.polybot.ui.components.Label
import com.mainnetwallet.polybot.ui.components.Pill
import com.mainnetwallet.polybot.ui.theme.Mono
import com.mainnetwallet.polybot.ui.theme.Poly
import com.mainnetwallet.polybot.ui.theme.Space

/** What `amount` pays if the side wins: shares bought at the ask, one dollar each. */
internal fun tapWin(amount: Double, ask: Double?): String {
    if (ask == null || ask.isNaN() || ask <= 0.0) return "win \u2014"
    val win = amount / ask
    return "win " + if (win >= 1000.0) Fmt.usdGrouped(win, 0) else Fmt.usd(win, if (win >= 100.0) 0 else 2)
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
    balance: Double,
    amounts: List<Double>,
    limits: TapLimits?,
    locked: Boolean,
    busyKey: String?,
    enabled: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onTap: (side: String, amount: Double) -> Unit,
    onSaveSettings: (amounts: List<Double>, enabled: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val c = Poly.colors
    var editing by remember { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "tapChevron")
    val ended = market == null || market.remainingSeconds <= 0.0
    val blocked: String? = when {
        !enabled -> "One-tap is OFF: open the settings (gear) to turn it on"
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
                Label("One-tap buy", Modifier.weight(1f))
                Text(
                    (if (bot.isLive) "LIVE" else "PAPER") + " \u00B7 " + Fmt.usdGrouped(balance),
                    style = Mono.small,
                    color = if (bot.isLive) scheme.error else scheme.onSurfaceVariant,
                )
                if (!enabled) Pill("OFF", scheme.onSurfaceVariant, scheme.surfaceContainerHighest)
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
                        val isUp = side == "UP"
                        val tone = if (isUp) c.up else c.down
                        val container = if (isUp) c.upContainer else c.downContainer
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Column(Modifier.width(52.dp)) {
                                Text(
                                    if (isUp) "UP" else "DOWN",
                                    style = Mono.small.copy(fontWeight = FontWeight.Bold),
                                    color = tone,
                                )
                                Text(
                                    if (ask == null) "--" else Fmt.centsShort(ask),
                                    style = Mono.small,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                            amounts.forEach { amount ->
                                val key = "$side:$amount"
                                Button(
                                    onClick = { onTap(side, amount) },
                                    enabled = enabled && blocked == null && ask != null && !locked && busyKey == null,
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 3.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = tone),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 44.dp),
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            if (busyKey == key) "\u2026" else tapMoney(amount),
                                            style = Mono.body.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp),
                                            maxLines = 1,
                                        )
                                        Text(
                                            tapWin(amount, ask),
                                            style = Mono.small.copy(fontSize = 10.sp),
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
            onDismiss = { editing = false },
            onSave = { newAmounts, on ->
                editing = false
                onSaveSettings(newAmounts, on)
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
    onDismiss: () -> Unit,
    onSave: (List<Double>, Boolean) -> Unit,
) {
    var on by remember { mutableStateOf(enabled) }
    var drafts by remember { mutableStateOf(amounts.map { tapMoney(it).removePrefix("$") }) }
    val parsed = drafts.map { it.trim().toDoubleOrNull() }
    val valid = parsed.all { it != null && it >= min && it <= max }
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
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(parsed.map { it ?: 1.0 }, on) }, enabled = valid) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
