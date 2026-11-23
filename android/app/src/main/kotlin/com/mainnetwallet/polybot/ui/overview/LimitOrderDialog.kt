package com.mainnetwallet.polybot.ui.overview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mainnetwallet.polybot.data.model.BookSnapshot
import com.mainnetwallet.polybot.data.model.BuyLimitRow
import com.mainnetwallet.polybot.data.model.LimitRow
import com.mainnetwallet.polybot.data.model.SellableHolding
import com.mainnetwallet.polybot.data.model.TAP_STAGE
import com.mainnetwallet.polybot.domain.LimitOrder
import com.mainnetwallet.polybot.domain.SellTarget
import com.mainnetwallet.polybot.ui.Fmt
import com.mainnetwallet.polybot.ui.components.KeyValueRow
import com.mainnetwallet.polybot.ui.components.Label
import com.mainnetwallet.polybot.ui.components.SidePill
import com.mainnetwallet.polybot.ui.components.pnlColor
import com.mainnetwallet.polybot.ui.theme.Mono
import com.mainnetwallet.polybot.ui.theme.Poly
import com.mainnetwallet.polybot.ui.theme.Space
import java.util.Locale
import kotlin.math.roundToInt

// Text colour on the green / red fills (the same dark tones as the web dashboard's Buy / Sell buttons).
private val OnUpFill = Color(0xFF06281C)
private val OnDownFill = Color(0xFF2A0A10)

private fun usdText(amount: Double): String = "$" + String.format(Locale.US, "%.2f", amount).removeSuffix(".00")

private fun bookOf(side: String, up: BookSnapshot?, down: BookSnapshot?): BookSnapshot? =
    if (side.equals("UP", ignoreCase = true)) up else down

/**
 * "Limit" pill (opens the Limit buy/sell dialog) in the header of the one-tap buy card, next to its ON / OFF button: one dialog for a limit BUY and a limit SELL, the same as the web
 * dashboard. Both limits are kept by the bot (code), never placed on the exchange, so nothing has to be cancelled there.
 *  - Buy: once the best ask is at or below the limit the bot makes ONE one-tap buy of the chosen amount that never pays
 *    more than the limit, then the limit is gone. It is manual like a one-tap buy: no stage rules, no take profit / stop loss.
 *  - Sell: pick one of the active positions (a strategy stage or the one-tap shares of a side) and give THAT position its
 *    own limit price. A one-tap position that a buy limit is still waiting to buy is listed too.
 */
@Composable
fun LimitOrderButton(
    marketId: String?,
    upBook: BookSnapshot?,
    downBook: BookSnapshot?,
    sellable: List<SellableHolding>,
    buyLimits: List<BuyLimitRow>,
    sellLimits: List<LimitRow>,
    canSet: Boolean,
    minUsd: Double,
    maxUsd: Double,
    onSetBuy: (side: String, price: Double, amount: Double) -> Unit,
    onCancelBuy: (side: String) -> Unit,
    onSetSell: (side: String, stage: Int, price: Double) -> Unit,
    onCancelSell: (side: String, stage: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val c = Poly.colors
    var open by remember { mutableStateOf(false) }
    val buyHere = if (marketId == null) emptyList() else buyLimits.filter { it.marketId == marketId }
    val sellHere = if (marketId == null) emptyList() else sellLimits.filter { it.marketId == marketId }
    val activeCount = buyHere.size + sellHere.size

    // Small pill in the header of the one-tap buy card, the same look as its ON / OFF button:
    // grey = no limit set, amber (with the count) = limits are waiting.
    val usable = marketId != null && (canSet || activeCount > 0)
    val tone = if (activeCount > 0) c.warn else scheme.onSurfaceVariant
    val fill = if (activeCount > 0) c.warnContainer else scheme.surfaceContainerHighest
    val edge = if (activeCount > 0) c.warn else scheme.outlineVariant
    Text(
        if (activeCount > 0) "Limit $activeCount" else "Limit",
        style = Mono.small.copy(fontWeight = FontWeight.Bold),
        color = if (usable) tone else tone.copy(alpha = 0.4f),
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(fill)
            .border(BorderStroke(1.dp, edge), RoundedCornerShape(8.dp))
            .clickable(enabled = usable) { open = true }
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )

    if (open && marketId != null) {
        LimitOrderDialog(
            marketId = marketId,
            upBook = upBook,
            downBook = downBook,
            sellable = sellable,
            buyLimits = buyHere,
            sellLimits = sellHere,
            canSet = canSet,
            minUsd = minUsd,
            maxUsd = maxUsd,
            onSetBuy = onSetBuy,
            onCancelBuy = onCancelBuy,
            onSetSell = onSetSell,
            onCancelSell = onCancelSell,
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun LimitOrderDialog(
    marketId: String,
    upBook: BookSnapshot?,
    downBook: BookSnapshot?,
    sellable: List<SellableHolding>,
    buyLimits: List<BuyLimitRow>,
    sellLimits: List<LimitRow>,
    canSet: Boolean,
    minUsd: Double,
    maxUsd: Double,
    onSetBuy: (side: String, price: Double, amount: Double) -> Unit,
    onCancelBuy: (side: String) -> Unit,
    onSetSell: (side: String, stage: Int, price: Double) -> Unit,
    onCancelSell: (side: String, stage: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val c = Poly.colors

    // start from what is already set, so the dialog doubles as "change"
    val firstUpBuy = buyLimits.firstOrNull { it.side.equals("UP", ignoreCase = true) }
    var buyMode by remember { mutableStateOf(true) }
    var side by remember { mutableStateOf("UP") }
    var buyDraft by remember { mutableStateOf(firstUpBuy?.let { LimitOrder.centsText(it.price) }.orEmpty()) }
    var usd by remember { mutableStateOf(firstUpBuy?.let { Fmt.plain(it.amount) } ?: "1") }
    var selKey by remember { mutableStateOf<String?>(null) }
    var sellDrafts by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    val targets = LimitOrder.sellTargets(marketId, sellable, buyLimits)
    val selected = targets.firstOrNull { it.key == selKey } ?: targets.firstOrNull()
    fun sellLimitOf(t: SellTarget): LimitRow? =
        sellLimits.firstOrNull { it.side.equals(t.side, ignoreCase = true) && it.stage == t.stage }
    val selectedLimit = selected?.let { sellLimitOf(it) }

    val viewSide = if (buyMode) side else (selected?.side ?: side)
    val book = bookOf(viewSide, upBook, downBook)
    val bid = book?.bestBid
    val ask = book?.bestAsk
    val ref = if (buyMode) ask else bid

    val sellDraft = selected?.let { sellDrafts[it.key] ?: selectedLimit?.let { l -> LimitOrder.centsText(l.price) } } ?: ""
    val draft = if (buyMode) buyDraft else sellDraft
    val setDraft: (String) -> Unit = { text ->
        if (buyMode) {
            buyDraft = text
        } else if (selected != null) {
            sellDrafts = sellDrafts + (selected.key to text)
        }
    }
    val cents = LimitOrder.parseCents(draft)
    val priceOk = cents != null
    val amount = LimitOrder.parseAmount(usd, minUsd, maxUsd)
    val amountOk = amount != null

    val firesNow = cents != null && (if (buyMode) LimitOrder.buyFiresNow(ref, cents) else LimitOrder.sellFiresNow(ref, cents))
    val valid = priceOk && (if (buyMode) amountOk else selected != null)
    val buyProfit = if (buyMode && cents != null && amount != null) LimitOrder.buyProfit(amount, cents) else null
    val proceeds = if (!buyMode && cents != null && selected?.size != null) LimitOrder.sellProceeds(selected.size, cents) else null
    val sellProfit = if (!buyMode && cents != null) LimitOrder.sellProfit(selected?.size, selected?.entry, cents) else null

    val hint: String? = when {
        draft.isNotBlank() && !priceOk -> "Enter a price from 1 to 99."
        buyMode ->
            if (firesNow && ref != null) "The ask (${LimitOrder.centsText(ref)}¢) is already at or below this limit: it buys right away." else null
        selected != null && selected.size == null -> "Nothing is held yet. This limit applies to the shares your buy limit buys."
        firesNow && ref != null -> "The bid (${LimitOrder.centsText(ref)}¢) is already at or above this limit: the shares are sold right away."
        ref != null ->
            "The bot sells when the best bid reaches ${if (cents != null) LimitOrder.centsText(cents) else "this price"}¢ or higher. Bid now: ${LimitOrder.centsText(ref)}¢."
        else -> "The bot sells when the best bid reaches this price or higher. There is no bid right now."
    }

    val subtitle = if (buyMode) {
        if (bid != null || ask != null) {
            "$side · bid ${if (bid != null) Fmt.centsShort(bid) else "—"} · ask ${if (ask != null) Fmt.centsShort(ask) else "—"}"
        } else {
            "No order book right now"
        }
    } else if (targets.isNotEmpty()) {
        "Pick a position, then set its own limit"
    } else {
        "No active position"
    }

    val saveLabel = if (buyMode) {
        "Set buy limit"
    } else if (selected != null) {
        "${if (selectedLimit != null) "Update" else "Set"} ${selected.side} sell limit"
    } else {
        "Set sell limit"
    }

    val switchSide: (String) -> Unit = { next ->
        side = next
        val b = buyLimits.firstOrNull { it.side.equals(next, ignoreCase = true) }
        buyDraft = b?.let { LimitOrder.centsText(it.price) }.orEmpty()
        if (b != null) usd = Fmt.plain(b.amount)
    }

    val save: () -> Unit = {
        if (valid && canSet && cents != null) {
            if (buyMode) {
                if (amount != null) {
                    onSetBuy(side, cents / 100.0, amount)
                    // a buy limit is one thing to set, so the dialog closes; a sell limit stays open for the next position
                    onDismiss()
                }
            } else if (selected != null) {
                onSetSell(selected.side, selected.stage, cents / 100.0)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .padding(horizontal = Space.md, vertical = Space.lg)
                .fillMaxWidth()
                .heightIn(max = 720.dp),
            shape = RoundedCornerShape(24.dp),
            color = scheme.surfaceContainerHigh,
        ) {
            Column {
                // header
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = Space.lg, end = Space.lg, top = Space.md, bottom = Space.sm),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Label("Limit order")
                    Text(
                        if (buyMode) "Buy limit" else "Sell limit",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }

                // body
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Space.lg, vertical = Space.sm),
                    verticalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SegRow(
                            labels = listOf("Buy", "Sell"),
                            selected = if (buyMode) 0 else 1,
                            containers = listOf(c.up, c.down),
                            contents = listOf(OnUpFill, OnDownFill),
                            onSelect = { buyMode = it == 0 },
                        )
                        if (buyMode) {
                            SegRow(
                                labels = listOf("UP", "DOWN"),
                                selected = if (side == "UP") 0 else 1,
                                containers = listOf(c.up, c.down),
                                contents = listOf(OnUpFill, OnDownFill),
                                onSelect = { switchSide(if (it == 0) "UP" else "DOWN") },
                            )
                        }
                    }

                    if (!buyMode) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Active positions", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                if (targets.isNotEmpty()) {
                                    Text("${targets.size} open", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                                }
                            }
                            if (targets.isEmpty()) {
                                Text(
                                    "No open position to sell right now. Buy first, or set a buy limit: its shares show up here once it fills.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                )
                            } else {
                                targets.forEach { t ->
                                    SellTargetRow(
                                        target = t,
                                        selected = selected?.key == t.key,
                                        limit = sellLimitOf(t),
                                        bid = bookOf(t.side, upBook, downBook)?.bestBid,
                                        onClick = { selKey = t.key },
                                    )
                                }
                            }
                        }
                    }

                    if (buyMode || selected != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                if (buyMode) {
                                    "Buy when the ask is this low or lower (¢)"
                                } else {
                                    "Sell ${selected?.side} (${LimitOrder.stageLabel(selected?.stage ?: TAP_STAGE)}) at this price or better (¢)"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            LimitPriceSlider(
                                draft = draft,
                                onDraft = setDraft,
                                markCents = ref?.let { it * 100.0 },
                                markLabel = if (buyMode) "ask" else "bid",
                                fallback = ref?.let { it * 100.0 } ?: 50.0,
                                picks = if (buyMode) LimitOrder.BUY_PICKS else LimitOrder.SELL_PICKS,
                                tone = if (buyMode) c.up else c.down,
                                onTone = if (buyMode) OnUpFill else OnDownFill,
                                fillRight = !buyMode,
                            )
                            if (buyProfit != null) {
                                Text(
                                    "${Fmt.signedUsd(buyProfit)} profit if $side wins",
                                    style = Mono.small,
                                    color = c.up,
                                )
                            }
                            if (hint != null) {
                                Text(
                                    hint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (draft.isNotBlank() && !priceOk) scheme.error else scheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (buyMode) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Amount to spend", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            OutlinedTextField(
                                value = usd,
                                onValueChange = { usd = it },
                                prefix = { Text("$") },
                                singleLine = true,
                                isError = usd.isNotBlank() && !amountOk,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                LimitOrder.AMOUNT_PICKS.forEach { v ->
                                    FilterChip(
                                        modifier = Modifier.weight(1f),
                                        selected = amount == v.toDouble(),
                                        onClick = { usd = v.toString() },
                                        label = { Text("$$v", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = c.up,
                                            selectedLabelColor = OnUpFill,
                                        ),
                                    )
                                }
                            }
                            if (usd.isNotBlank() && !amountOk) {
                                Text(
                                    "Enter an amount from ${usdText(minUsd)} to ${usdText(maxUsd)}.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.error,
                                )
                            }
                        }
                    } else if (proceeds != null && selected != null) {
                        Surface(shape = RoundedCornerShape(12.dp), color = scheme.surfaceContainerHighest) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                KeyValueRow("Sells", "${Fmt.num(selected.size ?: 0.0, 1)} ${selected.side} shares")
                                KeyValueRow("You get at ${LimitOrder.centsText(cents ?: 0.0)}¢", usdText(proceeds))
                                if (sellProfit != null) {
                                    KeyValueRow(
                                        "Profit vs entry (${Fmt.centsShort(selected.entry)})",
                                        Fmt.signedUsd(sellProfit),
                                        valueColor = pnlColor(sellProfit),
                                    )
                                }
                            }
                        }
                    }

                    if (buyLimits.isNotEmpty() || sellLimits.isNotEmpty()) {
                        Column {
                            Text("Active limits", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            buyLimits.forEach { l ->
                                ActiveLimitRow(
                                    tag = "Buy ${l.side}",
                                    tagColor = c.up,
                                    text = "at ${LimitOrder.centsText(l.price)}¢ or lower · ${usdText(l.amount)}",
                                    onRemove = { onCancelBuy(l.side) },
                                )
                            }
                            sellLimits.forEach { l ->
                                ActiveLimitRow(
                                    tag = "Sell ${l.side}",
                                    tagColor = c.down,
                                    text = "at ${LimitOrder.centsText(l.price)}¢ or higher · ${LimitOrder.stageLabel(l.stage)}",
                                    onRemove = { onCancelSell(l.side, l.stage) },
                                )
                            }
                        }
                    }
                }

                // footer
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.md, vertical = Space.sm),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        onClick = save,
                        enabled = valid && canSet,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (buyMode) c.up else c.down,
                            contentColor = if (buyMode) OnUpFill else OnDownFill,
                        ),
                    ) { Text(saveLabel, maxLines = 1) }
                }
            }
        }
    }
}

/** Two or more equal tabs on a dark track; the chosen one is filled with its own colour (Buy green, Sell red, UP green, DOWN red). */
@Composable
private fun SegRow(
    labels: List<String>,
    selected: Int,
    containers: List<Color>,
    contents: List<Color>,
    onSelect: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surface)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) containers[i] else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(i) })
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (on) contents[i] else scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One selectable position of the sell list: side, stage, shares at avg cost, its sell limit (if set), live PnL and bid. */
@Composable
private fun SellTargetRow(
    target: SellTarget,
    selected: Boolean,
    limit: LimitRow?,
    bid: Double?,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val c = Poly.colors
    val pnl = if (target.size != null) stagePnl(target.entry, bid, target.size) else null
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) c.down.copy(alpha = 0.08f) else scheme.surfaceContainerHighest,
        border = BorderStroke(1.dp, if (selected) c.down else scheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                colors = RadioButtonDefaults.colors(selectedColor = c.down),
                modifier = Modifier.size(20.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SidePill(target.side)
                    Text(LimitOrder.stageLabel(target.stage), style = Mono.small, color = scheme.onSurfaceVariant)
                }
                Text(
                    if (target.size != null) {
                        "${Fmt.num(target.size, 1)} shares at avg ${Fmt.centsShort(target.entry)}"
                    } else {
                        "Waiting for your buy limit to fill"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (limit != null) "Sell at ${LimitOrder.centsText(limit.price)}¢ or better" else "No limit yet",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (limit != null) FontWeight.Bold else FontWeight.Normal,
                    color = if (limit != null) c.down else scheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    Fmt.signedUsd(pnl),
                    style = Mono.body.copy(fontWeight = FontWeight.Medium),
                    color = pnlColor(pnl),
                )
                Text("bid ${Fmt.centsShort(bid)}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ActiveLimitRow(tag: String, tagColor: Color, text: String, onRemove: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            tag,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = tagColor,
        )
        Text(
            "  $text",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRemove) { Text("Remove") }
    }
}

/**
 * A limit price in one drag: a - / + pair for single cents around a big editable number, a slider over the whole 1 to 99
 * range, a marker for the best ask / bid, and a few quick picks. [fillRight] fills the track right of the thumb
 * ("this price or higher" for a sell); otherwise the left part is filled ("this price or lower" for a buy).
 */
@Composable
private fun LimitPriceSlider(
    draft: String,
    onDraft: (String) -> Unit,
    markCents: Double?,
    markLabel: String,
    fallback: Double,
    picks: List<Int>,
    tone: Color,
    onTone: Color,
    fillRight: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val value = LimitOrder.parseCents(draft)
    val shown = LimitOrder.clampCents(value ?: fallback)
    val pick: (Int) -> Unit = { cents -> onDraft(LimitOrder.clampCents(cents.toDouble()).toString()) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StepButton("−") { pick(shown - 1) }
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center, fontWeight = FontWeight.ExtraBold),
                placeholder = {
                    Text(
                        shown.toString(),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.headlineSmall,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                },
                suffix = { Text("¢") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            StepButton("+") { pick(shown + 1) }
        }
        Slider(
            value = shown.toFloat(),
            onValueChange = { pick(it.roundToInt()) },
            valueRange = 1f..99f,
            steps = 97,
            colors = SliderDefaults.colors(
                thumbColor = tone,
                activeTrackColor = if (fillRight) scheme.surfaceVariant else tone,
                inactiveTrackColor = if (fillRight) tone else scheme.surfaceVariant,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
        if (markCents != null) {
            // where the market is on the track, so you can see how far the limit is from it (12dp = the thumb's inset)
            BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp).padding(horizontal = 12.dp)) {
                val frac = (LimitOrder.clampCents(markCents) - 1) / 98f
                Text(
                    "▲ $markLabel",
                    modifier = Modifier
                        .width(56.dp)
                        .offset(x = maxWidth * frac - 28.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Poly.colors.warn,
                    maxLines = 1,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            picks.chunked(4).forEach { rowPicks ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowPicks.forEach { p ->
                        FilterChip(
                            modifier = Modifier.weight(1f),
                            selected = value == p.toDouble(),
                            onClick = { pick(p) },
                            label = { Text("$p¢", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = tone, selectedLabelColor = onTone),
                        )
                    }
                    repeat(4 - rowPicks.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = scheme.surfaceContainerHighest, contentColor = scheme.onSurface),
        modifier = Modifier.size(44.dp),
    ) {
        Text(label, fontSize = 22.sp, fontWeight = FontWeight.Medium)
    }
}
