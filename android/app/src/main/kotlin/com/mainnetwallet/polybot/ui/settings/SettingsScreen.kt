package com.mainnetwallet.polybot.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mainnetwallet.polybot.BuildConfig
import com.mainnetwallet.polybot.data.ActionResult
import com.mainnetwallet.polybot.data.net.FeedState
import com.mainnetwallet.polybot.data.net.UrlUtils
import com.mainnetwallet.polybot.data.store.ThemeMode
import com.mainnetwallet.polybot.domain.SettingGroup
import com.mainnetwallet.polybot.domain.SettingSection
import com.mainnetwallet.polybot.domain.SettingsSpec
import com.mainnetwallet.polybot.domain.StageFacts
import com.mainnetwallet.polybot.ui.LocalGraph
import com.mainnetwallet.polybot.ui.LocalUi
import com.mainnetwallet.polybot.ui.collectSelectedAsState
import com.mainnetwallet.polybot.ui.components.EmptyState
import com.mainnetwallet.polybot.ui.components.IconBadge
import com.mainnetwallet.polybot.ui.components.Pill
import com.mainnetwallet.polybot.ui.components.PolyPullToRefresh
import com.mainnetwallet.polybot.ui.components.ProHero
import com.mainnetwallet.polybot.ui.components.ProStat
import com.mainnetwallet.polybot.ui.components.SegmentedTabs
import com.mainnetwallet.polybot.ui.components.SkeletonCard
import com.mainnetwallet.polybot.ui.graphViewModel
import com.mainnetwallet.polybot.ui.safety.ConfirmDialog
import com.mainnetwallet.polybot.ui.safety.PhraseDialog
import com.mainnetwallet.polybot.ui.theme.Mono
import com.mainnetwallet.polybot.ui.theme.Poly
import com.mainnetwallet.polybot.ui.theme.Space
import kotlinx.coroutines.launch

// Settings is a hub with detail pages, each one a navigation route (see MainScaffold):
//   SettingsHome         connection, one card per stage (with its own on/off switch), Risk and Paper,
//                        preferences, danger zone and log out
//   SettingsSectionPage  everything one section owns. A stage page holds that stage's timing, entry rules,
//                        take-profit and stop-loss; Risk holds the account-wide limits and order policies
//   SettingsDangerPage   resets that change or wipe data on the PC
// The three share one SettingsViewModel (scoped to the hub's back stack entry), so an edit made on a detail page
// is still there, and still counted, when the person comes back to the hub.

/** The hub: where the PC connection, the trading sections, app preferences and account actions start. */
@Composable
fun SettingsHome(
    onEditConnection: () -> Unit,
    onOpenSection: (String) -> Unit,
    onOpenDanger: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: SettingsViewModel = graphViewModel()
    val graph = LocalGraph.current
    val ui = LocalUi.current
    val scope = rememberCoroutineScope()
    val prefs by graph.prefs.collectAsStateWithLifecycle()
    val feed by graph.repository.feedState.collectAsStateWithLifecycle()
    val live by graph.repository.snapshot.collectSelectedAsState { it?.bot?.isLive == true }
    var confirmLogout by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val p = prefs ?: return
    val colors = Poly.colors
    val serverUrl = UrlUtils.serverUrl(p.pcUrl, p.lastTunnelFor, p.lastTunnelUrl)

    PageFrame(vm, showSaveBar = true, modifier = modifier) { state ->
        ConnectionHero(
            pcUrl = p.pcUrl,
            serverUrl = serverUrl,
            feed = feed,
            live = live,
            onEdit = onEditConnection,
            onOpenServer = {
                // Hands the URL to Android, which opens it in the phone's default browser app.
                if (serverUrl != null) {
                    runCatching { uriHandler.openUri(serverUrl) }.onFailure {
                        ui.report(ActionResult(false, "No browser app found to open the link."))
                    }
                }
            },
        )

        val stageSections = SettingsSpec.sections.filter { StageFacts.stageOf(it) != null }
        val generalSections = SettingsSpec.sections.filter { StageFacts.stageOf(it) == null }

        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            GroupHeader(
                "Stages",
                caption = "Each stage keeps its own timing, entry rules, take-profit and stop-loss. The switch turns a stage on or off.",
            )
            SettingsCard {
                stageSections.forEachIndexed { index, section ->
                    if (index > 0) RowDivider()
                    StageRow(section, state, vm, onClick = { onOpenSection(section.id) })
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            GroupHeader("General", caption = "Rules that apply across all stages. Changes are sent when you save.")
            SettingsCard {
                generalSections.forEachIndexed { index, section ->
                    if (index > 0) RowDivider()
                    SectionRow(section, state, onClick = { onOpenSection(section.id) })
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            GroupHeader("Preferences", caption = "Stored on this phone only.")
            SettingsCard {
                AppearanceRow(mode = p.themeMode, onSelect = { scope.launch { graph.prefsStore.setTheme(it) } })
                RowDivider()
                SettingsToggleRow(
                    icon = Icons.Filled.TouchApp,
                    title = "Haptic feedback",
                    hint = "Short vibration on actions.",
                    checked = p.haptics,
                    onChange = { scope.launch { graph.prefsStore.setHaptics(it) } },
                )
                RowDivider()
                SettingsToggleRow(
                    icon = Icons.Filled.CheckCircle,
                    title = "Confirm sells",
                    hint = "Ask before a manual sell, in paper and live mode. Off sells immediately.",
                    checked = p.confirmSells,
                    onChange = { scope.launch { graph.prefsStore.setConfirmSells(it) } },
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            GroupHeader("Safety and account")
            SettingsCard {
                SettingsNavRow(
                    icon = Icons.Filled.Warning,
                    title = "Danger zone",
                    subtitle = "Reset settings, the paper account, daily stats or the whole server.",
                    onClick = onOpenDanger,
                    tint = colors.down,
                    container = colors.downContainer,
                )
                RowDivider()
                SettingsNavRow(
                    icon = Icons.AutoMirrored.Filled.Logout,
                    title = "Log out",
                    subtitle = "Forgets the API token on this phone. The PC address stays.",
                    onClick = { confirmLogout = true },
                    tint = colors.down,
                    container = colors.downContainer,
                    titleColor = MaterialTheme.colorScheme.error,
                    chevron = false,
                )
            }
        }

        Text(
            "Polybot for Android · v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (confirmLogout) {
        ConfirmDialog(
            title = "Log out?",
            text = "The saved API token is removed from this phone.",
            confirmLabel = "Log out",
            destructive = true,
            onConfirm = {
                ui.strong()
                scope.launch { graph.prefsStore.logout() }
            },
            onDismiss = { confirmLogout = false },
        )
    }
}

/**
 * Everything one section owns, grouped as in [SettingsSpec]. A stage page opens with a header (its state, key
 * numbers and master switch); a switched-off stage keeps its fields editable but dimmed.
 */
@Composable
fun SettingsSectionPage(sectionId: String, owner: ViewModelStoreOwner, modifier: Modifier = Modifier) {
    val vm: SettingsViewModel = graphViewModel(owner)
    val scope = rememberCoroutineScope()
    val section = SettingsSpec.sections.firstOrNull { it.id == sectionId } ?: return
    val stage = StageFacts.stageOf(section)
    var tabState by rememberSaveable(sectionId) { mutableIntStateOf(0) }

    PageFrame(vm, showSaveBar = true, modifier = modifier) { state ->
        when {
            state.loading -> {
                SkeletonCard(lines = 4)
                SkeletonCard(lines = 4)
            }
            state.loadError != null && state.saved.isEmpty() -> LoadError(
                message = state.loadError,
                onRetry = { scope.launch { vm.load() } },
            )
            else -> {
                val stageOff = stage != null && !StageFacts.enabled(stage, state.draft)
                if (stage != null) StageHero(section, stage, state, vm)
                if (stageOff) StageOffNotice()
                // A stage is split into tabs (Timing / Entry / Exits) so a screen shows a handful of fields.
                val pages = SettingsSpec.pagesOf(section)
                val tab = tabState.coerceIn(0, pages.lastIndex)
                if (pages.size > 1) {
                    SegmentedTabs(
                        labels = pages.map { page ->
                            val keys = SettingsSpec.keysOf(page)
                            if (keys.any { it in state.errors }) "${page.label} !" else page.label
                        },
                        selected = tab,
                        onSelect = { tabState = it },
                        counts = pages.map { page -> SettingsSpec.keysOf(page).count { it in state.dirty } },
                    )
                }
                Column(
                    Modifier.alpha(if (stageOff) 0.6f else 1f),
                    verticalArrangement = Arrangement.spacedBy(Space.lg),
                ) {
                    pages[tab].groups.forEach { group -> GroupBlock(section, group, state, vm) }
                }
            }
        }
    }
}

/** Resets that change or wipe data on the PC. Every action asks for confirmation first. */
@Composable
fun SettingsDangerPage(owner: ViewModelStoreOwner, modifier: Modifier = Modifier) {
    val vm: SettingsViewModel = graphViewModel(owner)
    val graph = LocalGraph.current
    val repo = graph.repository
    val ui = LocalUi.current
    val scope = rememberCoroutineScope()
    val starting by repo.snapshot.collectSelectedAsState { it?.account?.startingBalance }
    var dialog by remember { mutableStateOf<String?>(null) }
    var balanceText by remember { mutableStateOf("") }
    val colors = Poly.colors

    PageFrame(vm, showSaveBar = false, modifier = modifier) { _ ->
        SettingsCard(container = colors.downContainer.copy(alpha = 0.35f)) {
            Row(
                Modifier.padding(Space.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.down)
                Text(
                    "These actions change or wipe data on the PC. Each one asks for confirmation first.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            GroupHeader("Settings")
            SettingsCard {
                DangerRow(
                    icon = Icons.Filled.Restore,
                    title = "Reset settings to defaults",
                    hint = "Every trading setting returns to its default value. Unsaved edits are lost.",
                    onClick = { dialog = "settings" },
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            GroupHeader("Paper trading")
            SettingsCard {
                DangerRow(
                    icon = Icons.Filled.DeleteSweep,
                    title = "Reset paper account",
                    hint = "Sets the paper balance and clears paper positions.",
                    onClick = {
                        balanceText = starting?.let { com.mainnetwallet.polybot.ui.Fmt.plain(it) } ?: "1000"
                        dialog = "paper"
                    },
                )
                RowDivider()
                DangerRow(
                    icon = Icons.Filled.Restore,
                    title = "Reset daily stats",
                    hint = "Zeroes today's PnL and trade counters.",
                    onClick = { dialog = "daily" },
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            GroupHeader("Server")
            SettingsCard {
                DangerRow(
                    icon = Icons.Filled.Warning,
                    title = "Reset server (wipe everything)",
                    hint = "Deletes orders, positions, logs and stats. Type RESET ALL to confirm.",
                    onClick = { dialog = "server" },
                )
            }
        }
    }

    when (dialog) {
        "settings" -> ConfirmDialog(
            title = "Reset all settings?",
            text = "Every trading setting returns to its default value. Unsaved edits are lost.",
            confirmLabel = "Reset",
            destructive = true,
            onConfirm = { scope.launch { ui.report(vm.resetToDefaults()) } },
            onDismiss = { dialog = null },
        )
        "paper" -> {
            val amount = balanceText.trim().toDoubleOrNull()
            val valid = amount != null && amount > 0
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Reset paper account") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                        Text("Paper balance and paper positions are reset. Live data is not touched.")
                        OutlinedTextField(
                            value = balanceText,
                            onValueChange = { balanceText = it },
                            label = { Text("Starting balance") },
                            suffix = { Text("USDC") },
                            singleLine = true,
                            isError = !valid,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = valid,
                        onClick = {
                            val value = amount ?: return@TextButton
                            dialog = null
                            scope.launch { ui.report(repo.resetPaper(value)) }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Reset") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
            )
        }
        "daily" -> ConfirmDialog(
            title = "Reset daily stats?",
            text = "Today's PnL and trade counters go back to zero. Orders and positions stay.",
            confirmLabel = "Reset",
            destructive = true,
            onConfirm = { scope.launch { ui.report(repo.resetDaily()) } },
            onDismiss = { dialog = null },
        )
        "server" -> PhraseDialog(
            title = "Reset the whole server",
            body = "This wipes all orders, positions, logs and statistics on the PC. It cannot be undone.",
            confirmLabel = "Wipe everything",
            hint = "Type RESET ALL",
            requiredExact = "RESET ALL",
            onSubmit = { repo.resetServer(it) },
            onDismiss = { dialog = null },
        )
    }
}

/**
 * Shared frame of every Settings page: pull to refresh, a scrolling column with the page content and, when
 * there are unsaved edits, the floating save bar underneath.
 */
@Composable
private fun PageFrame(
    vm: SettingsViewModel,
    showSaveBar: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(SettingsViewModel.State) -> Unit,
) {
    val ui = LocalUi.current
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().imePadding()) {
        PolyPullToRefresh(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                scope.launch {
                    vm.load()
                    refreshing = false
                }
            },
            modifier = Modifier.weight(1f),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.lg, vertical = Space.md),
                verticalArrangement = Arrangement.spacedBy(Space.lg),
            ) {
                content(state)
            }
        }
        if (showSaveBar && state.dirty.isNotEmpty()) {
            SettingsSaveBar(
                changed = state.dirty.map { SettingsSpec.displayName(it) },
                problems = state.errors.size,
                saving = state.saving,
                canSave = state.canSave,
                onDiscard = vm::discard,
                onSave = {
                    scope.launch {
                        val result = vm.save()
                        ui.report(result)
                    }
                },
            )
        }
    }
}

@Composable
private fun GroupBlock(section: SettingSection, group: SettingGroup, state: SettingsViewModel.State, vm: SettingsViewModel) {
    // A stage's on/off switch lives in the page header, not in a group.
    val enabledKey = SettingsSpec.enabledKey(section)
    val fields = group.keys.filter { it != enabledKey }.mapNotNull { SettingsSpec.field(it) }
    if (fields.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        GroupHeader(group.title, caption = group.description, icon = groupIcon(group.title))
        SettingsCard {
            // The low take-profit tier has no field of its own: it starts at the stage 0 min ask.
            val showsTiers = group.keys.firstOrNull() == "stage0_mid_from_ask"
            if (showsTiers) {
                DerivedValueRow(
                    title = "Low tier from (buy price)",
                    hint = "Not editable: the low tier starts at the stage 0 min ask. Shares bought from here up to the mid tier price use the low take-profit %.",
                    value = state.draft["stage0_min_ask"].orEmpty(),
                )
            }
            fields.forEachIndexed { index, field ->
                if (index > 0 || showsTiers) RowDivider()
                SettingFieldRow(
                    field = field,
                    value = state.draft[field.key].orEmpty(),
                    error = state.errors[field.key],
                    dirty = field.key in state.dirty,
                    enabled = !state.saving,
                    onChange = { vm.edit(field.key, it) },
                )
            }
        }
    }
}

private fun groupIcon(title: String): ImageVector = when (title) {
    "Timing" -> Icons.Filled.Timer
    "Entry", "Entry band" -> Icons.Filled.Bolt
    "Take-profit", "Take-profit tiers" -> Icons.Filled.TrendingUp
    "Stop-loss" -> Icons.Filled.Shield
    "Sizing" -> Icons.Filled.Tune
    "Daily limits" -> Icons.Filled.Flag
    "Order handling" -> Icons.Filled.Layers
    "Data quality" -> Icons.Filled.CheckCircle
    "Paper fill model" -> Icons.Filled.Science
    else -> Icons.Filled.Tune
}

/**
 * Top of a stage page, kept short: icon, name and the stage's master switch on one line, then the four numbers
 * that define the stage (read from the draft, so they follow edits as they are typed).
 */
@Composable
private fun StageHero(section: SettingSection, stage: Int, state: SettingsViewModel.State, vm: SettingsViewModel) {
    val scheme = MaterialTheme.colorScheme
    val meta = sectionMeta(section.id)
    val on = StageFacts.enabled(stage, state.draft)
    val enabledKey = SettingsSpec.enabledKey(section)

    ProHero {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            IconBadge(meta.icon, scheme.primary, scheme.primaryContainer, size = 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Text(if (on) "${section.title} is on" else "${section.title} is off", style = MaterialTheme.typography.titleMedium)
                    if (enabledKey != null && enabledKey in state.dirty) EditedPill()
                }
                Text(meta.description, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            if (enabledKey != null) {
                Switch(
                    checked = on,
                    onCheckedChange = { vm.edit(enabledKey, it.toString()) },
                    enabled = !state.saving,
                )
            }
        }
        StageFacts.facts(stage, state.draft).chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                row.forEach { fact -> ProStat(fact.label, fact.value, Modifier.weight(1f)) }
            }
        }
    }
}

/** Shown under the header of a switched-off stage. */
@Composable
private fun StageOffNotice() {
    val colors = Poly.colors
    SettingsCard(container = colors.warnContainer.copy(alpha = 0.45f)) {
        Row(
            Modifier.padding(Space.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.warn)
            Text(
                "This stage is off: it does not buy, take profit or stop out. Your values below are kept for when you switch it on again.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LoadError(message: String?, onRetry: () -> Unit) {
    SettingsCard {
        EmptyState("Could not load the settings", hint = message, icon = Icons.Filled.CloudOff)
        FilledTonalButton(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Space.lg, end = Space.lg, bottom = Space.lg),
        ) { Text("Try again") }
    }
}

private enum class Tone { Primary, Warn, Info }

private data class SectionMeta(
    val icon: ImageVector,
    val summary: String,
    val description: String = summary,
    val tone: Tone = Tone.Primary,
)

private fun sectionMeta(id: String): SectionMeta = when (id) {
    "stage0" -> SectionMeta(Icons.Filled.Timer, "Early buy with tiered take-profit", "Early buy with tiered take-profit and stop-loss.")
    "stage1" -> SectionMeta(Icons.Filled.TrendingUp, "First final-window buy", "First final-window buy once the ask reaches its trigger.")
    "stage2" -> SectionMeta(Icons.Filled.Layers, "One extra buy close to expiry", "Adds one more buy, only if stage 1 already bought before this stage starts.")
    "stage3" -> SectionMeta(Icons.Filled.Flag, "Last buy, with a stop-loss", "Last buy on its own rules. It has a stop-loss but no take-profit.")
    "risk" -> SectionMeta(Icons.Filled.Shield, "Sizing, daily limits, order policies and data quality", tone = Tone.Warn)
    "paper" -> SectionMeta(Icons.Filled.Science, "Simulated slippage and fees", tone = Tone.Info)
    else -> SectionMeta(Icons.Filled.Tune, "")
}

/** One row of the General list: icon, title, summary and an "N edited" pill. */
@Composable
private fun SectionRow(section: SettingSection, state: SettingsViewModel.State, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val colors = Poly.colors
    val meta = sectionMeta(section.id)
    val edited = SettingsSpec.dirtyIn(section, state.dirty)
    val (tint, container) = when (meta.tone) {
        Tone.Primary -> scheme.primary to scheme.primaryContainer
        Tone.Warn -> colors.warn to colors.warnContainer
        Tone.Info -> colors.info to colors.info.copy(alpha = 0.16f)
    }

    SettingsNavRow(
        icon = meta.icon,
        title = section.title,
        subtitle = meta.summary.ifBlank { null },
        onClick = onClick,
        tint = tint,
        container = container,
        trailing = {
            if (edited > 0) Pill("$edited edited", colors.info, colors.info.copy(alpha = 0.16f))
        },
    )
}

/**
 * One row of the Stages list: icon, name, the stage's window and ask trigger, an "N edited" pill and the stage's
 * own on/off switch. Tapping the row opens the stage page; the switch edits the draft like any other field.
 */
@Composable
private fun StageRow(section: SettingSection, state: SettingsViewModel.State, vm: SettingsViewModel, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val colors = Poly.colors
    val stage = StageFacts.stageOf(section) ?: return
    val meta = sectionMeta(section.id)
    val enabledKey = SettingsSpec.enabledKey(section)
    val on = StageFacts.enabled(stage, state.draft)
    val edited = SettingsSpec.dirtyIn(section, state.dirty)
    val facts = StageFacts.facts(stage, state.draft)
    val ask = facts.firstOrNull { it.label == "Ask" }?.value
    // A switched-off stage has no window of its own (the stages before it run on), so it says so instead.
    val subtitle = if (on) {
        listOfNotNull(StageFacts.window(stage, state.draft), ask?.let { "ask $it" }).joinToString("  ·  ")
    } else {
        "Switched off  ·  settings kept"
    }

    SettingsNavRow(
        icon = meta.icon,
        title = section.title,
        subtitle = subtitle,
        onClick = onClick,
        tint = if (on) scheme.primary else scheme.onSurfaceVariant,
        container = if (on) scheme.primaryContainer else scheme.surfaceVariant,
        trailing = {
            if (edited > 0) Pill("$edited edited", colors.info, colors.info.copy(alpha = 0.16f))
            if (enabledKey != null) {
                Switch(
                    checked = on,
                    onCheckedChange = { vm.edit(enabledKey, it.toString()) },
                    enabled = !state.saving,
                )
            }
        },
    )
}

/**
 * Connection summary at the top of the hub: address, the server URL the app is using (the Cloudflare tunnel for a
 * Gist address) with an Open button, feed status, PAPER or LIVE, and the edit button.
 */
@Composable
private fun ConnectionHero(
    pcUrl: String,
    serverUrl: String?,
    feed: FeedState,
    live: Boolean,
    onEdit: () -> Unit,
    onOpenServer: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = Poly.colors
    val (statusLabel, statusColors) = when (feed) {
        FeedState.Live -> "Connected" to (colors.up to colors.upContainer)
        FeedState.Connecting -> "Connecting" to (colors.warn to colors.warnContainer)
        FeedState.Reconnecting -> "Reconnecting" to (colors.warn to colors.warnContainer)
        FeedState.Offline -> "Offline" to (colors.down to colors.downContainer)
        FeedState.AuthFailed -> "Token rejected" to (colors.down to colors.downContainer)
        FeedState.Idle -> "Idle" to (scheme.onSurfaceVariant to scheme.surfaceVariant)
    }

    ProHero {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            IconBadge(Icons.Filled.Link, scheme.primary, scheme.primaryContainer, size = 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Your PC", style = MaterialTheme.typography.titleMedium)
                Text(
                    pcUrl.ifBlank { "Not set" },
                    style = Mono.small,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Server URL", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                Text(
                    serverUrl ?: "Waiting for the tunnel URL",
                    style = Mono.small,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(onClick = onOpenServer, enabled = serverUrl != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text("Open")
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Pill(statusLabel, statusColors.first, statusColors.second)
            if (live) Pill("LIVE", colors.live, colors.liveContainer) else Pill("PAPER", colors.info, colors.info.copy(alpha = 0.16f))
        }
        FilledTonalButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text("Edit address and token") }
    }
}

private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.DARK -> "Dark"
    ThemeMode.LIGHT -> "Light"
}

@Composable
private fun AppearanceRow(mode: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val modes = ThemeMode.entries
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            IconBadge(Icons.Filled.Palette, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer, size = 38.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Appearance", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    "Follow the phone, or always use one theme.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SegmentedTabs(
            labels = modes.map { themeLabel(it) },
            selected = modes.indexOf(mode).coerceAtLeast(0),
            onSelect = { onSelect(modes[it]) },
        )
    }
}

@Composable
private fun DangerRow(icon: ImageVector, title: String, hint: String, onClick: () -> Unit) {
    val colors = Poly.colors
    SettingsNavRow(
        icon = icon,
        title = title,
        subtitle = hint,
        onClick = onClick,
        tint = colors.down,
        container = colors.downContainer,
        titleColor = MaterialTheme.colorScheme.error,
        chevron = false,
    )
}
