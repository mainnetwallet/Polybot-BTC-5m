package com.mainnetwallet.polybot.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mainnetwallet.polybot.data.store.ThemeMode
import com.mainnetwallet.polybot.domain.SettingField
import com.mainnetwallet.polybot.domain.SettingKind
import com.mainnetwallet.polybot.domain.SettingsSpec
import com.mainnetwallet.polybot.ui.LocalGraph
import com.mainnetwallet.polybot.ui.LocalUi
import com.mainnetwallet.polybot.ui.components.EmptyState
import com.mainnetwallet.polybot.ui.components.PolyCard
import com.mainnetwallet.polybot.ui.components.SectionTitle
import com.mainnetwallet.polybot.ui.components.SkeletonCard
import com.mainnetwallet.polybot.ui.graphViewModel
import com.mainnetwallet.polybot.ui.safety.ConfirmDialog
import com.mainnetwallet.polybot.ui.safety.PhraseDialog
import com.mainnetwallet.polybot.ui.theme.Space
import kotlinx.coroutines.launch

private const val APP_TAB = "app"
private const val DANGER_TAB = "danger"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onEditConnection: () -> Unit, modifier: Modifier = Modifier) {
    val vm: SettingsViewModel = graphViewModel()
    val ui = LocalUi.current
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(SettingsSpec.sections.first().id) }
    var refreshing by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    val tabs = SettingsSpec.sections.map { it.id to it.title } + listOf(DANGER_TAB to "Danger zone", APP_TAB to "App")

    Column(modifier.fillMaxSize()) {
        PrimaryScrollableTabRow(selectedTabIndex = tabs.indexOfFirst { it.first == tab }.coerceAtLeast(0), edgePadding = Space.sm) {
            tabs.forEach { (id, title) ->
                Tab(selected = tab == id, onClick = { tab = id }, text = { Text(title, maxLines = 1) })
            }
        }
        PullToRefreshBox(
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
                    .padding(Space.lg),
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                when {
                    tab == APP_TAB -> AppSection(onEditConnection)
                    tab == DANGER_TAB -> DangerZone()
                    state.loading -> {
                        SkeletonCard(lines = 4)
                        SkeletonCard(lines = 4)
                    }
                    state.loadError != null && state.saved.isEmpty() -> PolyCard {
                        EmptyState("Could not load the settings", state.loadError, icon = Icons.Filled.CloudOff)
                    }
                    else -> {
                        val section = SettingsSpec.sections.first { it.id == tab }
                        section.groups.forEach { group ->
                            PolyCard {
                                SectionTitle(group.title)
                                group.description?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                group.keys.mapNotNull { SettingsSpec.field(it) }.forEachIndexed { index, field ->
                                    if (index > 0) HorizontalDivider(Modifier.padding(vertical = Space.xs))
                                    FieldRow(
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
                        OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth(), enabled = !state.saving) {
                            Text("Reset all settings to defaults")
                        }
                    }
                }
            }
        }
        if (state.dirty.isNotEmpty() && tab != APP_TAB && tab != DANGER_TAB) {
            SaveBar(
                changed = state.dirty.mapNotNull { SettingsSpec.field(it)?.label },
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

    if (confirmReset) {
        ConfirmDialog(
            title = "Reset all settings?",
            text = "Every trading setting returns to its default value. Unsaved edits are lost.",
            confirmLabel = "Reset",
            destructive = true,
            onConfirm = { scope.launch { ui.report(vm.resetToDefaults()) } },
            onDismiss = { confirmReset = false },
        )
    }
}

@Composable
private fun SaveBar(
    changed: List<String>,
    problems: Int,
    saving: Boolean,
    canSave: Boolean,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = Space.lg, vertical = Space.sm),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Text(
                "${changed.size} unsaved: " + changed.take(4).joinToString(", ") + if (changed.size > 4) " and ${changed.size - 4} more" else "",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
            )
            if (problems > 0) {
                Text(
                    "$problems field${if (problems == 1) "" else "s"} need attention.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                OutlinedButton(onClick = onDiscard, enabled = !saving, modifier = Modifier.weight(1f)) { Text("Discard") }
                Button(onClick = onSave, enabled = canSave, modifier = Modifier.weight(1f)) {
                    if (saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Save")
                }
            }
        }
    }
}

@Composable
private fun FieldRow(field: SettingField, value: String, error: String?, dirty: Boolean, enabled: Boolean, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        when (val kind = field.kind) {
            SettingKind.Toggle -> Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(field.label + if (dirty) " •" else "", style = MaterialTheme.typography.bodyLarge)
                    Text(field.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = value == "true", onCheckedChange = { onChange(it.toString()) }, enabled = enabled)
            }
            is SettingKind.Number -> {
                OutlinedTextField(
                    value = value,
                    onValueChange = onChange,
                    label = { Text(field.label + if (dirty) " •" else "") },
                    suffix = kind.suffix?.let { { Text(it) } },
                    singleLine = true,
                    enabled = enabled,
                    isError = error != null,
                    supportingText = { Text(error ?: field.hint) },
                    keyboardOptions = KeyboardOptions(keyboardType = if (kind.integer) KeyboardType.Number else KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is SettingKind.Choice -> {
                Text(field.label + if (dirty) " •" else "", style = MaterialTheme.typography.bodyLarge)
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    kind.options.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            pair.forEach { (id, label) ->
                                FilterChip(selected = value == id, onClick = { onChange(id) }, label = { Text(label) }, enabled = enabled)
                            }
                        }
                    }
                }
                Text(error ?: field.hint, style = MaterialTheme.typography.bodySmall, color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (error != null && field.kind !is SettingKind.Number && field.kind !is SettingKind.Choice) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun DangerZone() {
    val graph = LocalGraph.current
    val repo = graph.repository
    val ui = LocalUi.current
    val scope = rememberCoroutineScope()
    val snapshot by repo.snapshot.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<String?>(null) }
    var balanceText by remember { mutableStateOf("") }
    val starting = snapshot?.account?.startingBalance
    val container = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)

    PolyCard(container = container, borderColor = MaterialTheme.colorScheme.error) {
        SectionTitle("Danger zone")
        Text(
            "These actions change or wipe data on the PC. Each one asks for confirmation.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DangerButton("Reset paper account", "Sets the paper balance and clears paper positions.") {
            balanceText = starting?.let { com.mainnetwallet.polybot.ui.Fmt.plain(it) } ?: "1000"
            dialog = "paper"
        }
        DangerButton("Reset daily stats", "Zeroes today's PnL and trade counters.") { dialog = "daily" }
        DangerButton("Reset server (wipe everything)", "Deletes orders, positions, logs and stats. Type RESET ALL to confirm.") { dialog = "server" }
    }

    when (dialog) {
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

@Composable
private fun DangerButton(title: String, hint: String, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text(title) }
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AppSection(onEditConnection: () -> Unit) {
    val graph = LocalGraph.current
    val ui = LocalUi.current
    val scope = rememberCoroutineScope()
    val prefs by graph.prefs.collectAsStateWithLifecycle()
    var confirmLogout by remember { mutableStateOf(false) }
    val p = prefs ?: return

    PolyCard {
        SectionTitle("Connection")
        Text(p.pcUrl, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onEditConnection, modifier = Modifier.fillMaxWidth()) { Text("Edit PC address and token") }
    }
    PolyCard {
        SectionTitle("Appearance")
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = p.themeMode == mode,
                    onClick = { scope.launch { graph.prefsStore.setTheme(mode) } },
                    label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }
    }
    PolyCard {
        SectionTitle("Behaviour")
        ToggleRow("Haptic feedback", "Short vibration on actions.", p.haptics) {
            scope.launch { graph.prefsStore.setHaptics(it) }
        }
        HorizontalDivider()
        ToggleRow("Confirm paper sells", "Ask before a manual sell in paper mode. Live sells always ask.", p.confirmSells) {
            scope.launch { graph.prefsStore.setConfirmSells(it) }
        }
    }
    PolyCard {
        SectionTitle("Account")
        Text(
            "Log out forgets the API token on this phone. The PC address stays so you only enter the token again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { confirmLogout = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text("Log out") }
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

@Composable
private fun ToggleRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
