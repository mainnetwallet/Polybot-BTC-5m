package com.mainnetwallet.polybot.ui.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.mainnetwallet.polybot.ui.components.PolyPullToRefresh
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mainnetwallet.polybot.core.AppJson
import com.mainnetwallet.polybot.data.model.LogRow
import com.mainnetwallet.polybot.data.net.FeedState
import com.mainnetwallet.polybot.domain.LevelFilter
import com.mainnetwallet.polybot.domain.LogsLogic
import com.mainnetwallet.polybot.domain.Tone
import com.mainnetwallet.polybot.ui.Fmt
import com.mainnetwallet.polybot.ui.LocalGraph
import com.mainnetwallet.polybot.ui.LocalUi
import com.mainnetwallet.polybot.ui.components.Dot
import com.mainnetwallet.polybot.ui.components.EmptyState
import com.mainnetwallet.polybot.ui.components.IconBadge
import com.mainnetwallet.polybot.ui.components.KeyValueRow
import com.mainnetwallet.polybot.ui.components.Label
import com.mainnetwallet.polybot.ui.components.ProCard
import com.mainnetwallet.polybot.ui.components.ProSectionHeader
import com.mainnetwallet.polybot.ui.components.SegmentedTabs
import com.mainnetwallet.polybot.ui.components.SkeletonCard
import com.mainnetwallet.polybot.ui.components.ToneChip
import com.mainnetwallet.polybot.ui.graphViewModel
import com.mainnetwallet.polybot.ui.theme.Mono
import com.mainnetwallet.polybot.ui.theme.Poly
import com.mainnetwallet.polybot.ui.theme.Space
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private val prettyJson = Json(from = AppJson) { prettyPrint = true }

private fun levelTone(level: String): Tone = when (level.uppercase()) {
    "ERROR", "CRITICAL" -> Tone.Down
    "WARNING", "WARN" -> Tone.Warn
    "INFO" -> Tone.Info
    else -> Tone.Neutral
}

private fun levelLabel(level: String): String = when (level.uppercase()) {
    "WARNING" -> "WARN"
    "CRITICAL" -> "ERROR"
    else -> level.uppercase().ifBlank { "INFO" }
}

private fun tabLabel(level: LevelFilter): String = when (level) {
    LevelFilter.All -> "All"
    LevelFilter.Info -> "Info"
    LevelFilter.Warn -> "Warn"
    LevelFilter.Error -> "Error"
}

/** Icon, colours and the optional accent strip of a log level. */
private class LevelLook(val icon: ImageVector, val fg: Color, val bg: Color, val accent: Color?)

@Composable
private fun levelLook(level: String): LevelLook {
    val c = Poly.colors
    val s = MaterialTheme.colorScheme
    return when (levelTone(level)) {
        Tone.Down -> LevelLook(Icons.Filled.Error, c.down, c.downContainer, c.down)
        Tone.Warn -> LevelLook(Icons.Filled.Warning, c.warn, c.warnContainer, c.warn)
        Tone.Info -> LevelLook(Icons.Filled.Info, s.onPrimaryContainer, s.primaryContainer, null)
        else -> LevelLook(Icons.Filled.Terminal, s.onSurfaceVariant, s.surfaceVariant, null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(modifier: Modifier = Modifier) {
    val vm: LogsViewModel = graphViewModel()
    val graph = LocalGraph.current
    val rows by vm.logs.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val feed by graph.repository.feedState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<LogRow?>(null) }

    // The text field keeps its own copy of the search text so typing never waits for the ViewModel.
    var text by rememberSaveable { mutableStateOf(filters.query) }
    LaunchedEffect(Unit) { vm.setQuery(text) }

    val categories = remember(rows) { LogsLogic.categories(rows) }
    // Category and search narrow the level counts too; the level tabs then pick from that.
    val scoped = remember(rows, filters.category, filters.query) {
        LogsLogic.filter(rows, LevelFilter.All, filters.category, filters.query)
    }
    val counts = remember(scoped) { LogsLogic.counts(scoped) }
    val visible = remember(scoped, filters.level) { scoped.filter { filters.level.matches(it.level) } }
    val items = remember(visible) { LogGrouping.group(visible, System.currentTimeMillis()) }

    PolyPullToRefresh(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            scope.launch {
                vm.reload()
                refreshing = false
            }
        },
        modifier = modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize()) {
            // Search and level tabs stay put; only the list below them scrolls.
            Column(
                Modifier.padding(start = Space.lg, end = Space.lg, top = Space.lg),
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                SearchField(value = text, onChange = {
                    text = it
                    vm.setQuery(it)
                })
                SegmentedTabs(
                    labels = LevelFilter.entries.map { tabLabel(it) },
                    selected = filters.level.ordinal,
                    onSelect = { vm.setLevel(LevelFilter.entries[it]) },
                    counts = LevelFilter.entries.map { counts.of(it) },
                )
            }
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(Space.lg),
                verticalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                item(key = "controls") {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                        if (categories.isNotEmpty()) {
                            CategoryChips(categories, filters.category) { vm.setCategory(it) }
                        }
                        ProSectionHeader(
                            "Activity log",
                            subtitle = "${visible.size} of ${rows.size} entries, newest first",
                            trailing = { StreamPill(feed) },
                        )
                    }
                }

                if (items.isEmpty()) {
                    item(key = "empty") {
                        when {
                            rows.isEmpty() && refreshing -> SkeletonCard(lines = 4)
                            rows.isEmpty() -> ProCard {
                                EmptyState(
                                    "No log entries yet",
                                    hint = "Entries show up here as the bot runs. Pull down to refresh.",
                                    icon = Icons.AutoMirrored.Filled.List,
                                )
                            }
                            else -> ProCard {
                                EmptyState(
                                    "Nothing matches",
                                    hint = "Try another level, category or search.",
                                    icon = Icons.Filled.Search,
                                )
                                if (filters.isActive || text.isNotBlank()) {
                                    TextButton(
                                        onClick = {
                                            text = ""
                                            vm.clearFilters()
                                        },
                                        modifier = Modifier.align(Alignment.CenterHorizontally),
                                    ) { Text("Clear filters") }
                                }
                            }
                        }
                    }
                } else {
                    itemsIndexed(
                        items = items,
                        key = { index, item ->
                            when (item) {
                                is LogListItem.DayHeader -> "day$index"
                                is LogListItem.Entry ->
                                    item.row.id?.let { "id$it" }
                                        ?: "n${item.row.createdAt}${item.row.message.hashCode()}$index"
                            }
                        },
                        contentType = { _, item -> if (item is LogListItem.DayHeader) "day" else "log" },
                    ) { _, item ->
                        when (item) {
                            is LogListItem.DayHeader -> LogDayHeader(item)
                            is LogListItem.Entry -> LogEntryCard(item.row, onClick = { detail = item.row })
                        }
                    }
                }
            }
        }
    }

    detail?.let { row -> LogDetailSheet(row, onDismiss = { detail = null }) }
}

// ---- controls ---------------------------------------------------------------------------------------------------

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("Search messages and details") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear search")
                }
            }
        },
        shape = MaterialTheme.shapes.large,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
    )
}

@Composable
private fun CategoryChips(categories: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text("All categories", maxLines = 1) },
        )
        categories.forEach { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onSelect(if (selected == category) null else category) },
                label = { Text(category, maxLines = 1) },
            )
        }
    }
}

/** Small pill that says whether new entries are arriving over the live connection. */
@Composable
private fun StreamPill(feed: FeedState) {
    val c = Poly.colors
    val scheme = MaterialTheme.colorScheme
    val (label, dot, bg) = when (feed) {
        FeedState.Live -> Triple("Streaming", c.up, c.upContainer)
        FeedState.Connecting, FeedState.Reconnecting -> Triple("Reconnecting", c.warn, c.warnContainer)
        else -> Triple("Offline", scheme.outline, scheme.surfaceVariant)
    }
    Surface(shape = RoundedCornerShape(50), color = bg) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Dot(dot, size = 6.dp)
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

// ---- list -------------------------------------------------------------------------------------------------------

@Composable
private fun LogDayHeader(item: LogListItem.DayHeader) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(item.label, Modifier.weight(1f))
        Text(
            item.count.toString(),
            style = Mono.small,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One entry: level icon, level and category, the message (3 lines at most), and the time. Tap for details. */
@Composable
private fun LogEntryCard(row: LogRow, onClick: () -> Unit) {
    val look = levelLook(row.level)
    val scheme = MaterialTheme.colorScheme
    val fields = row.payload?.size ?: 0
    ProCard(accent = look.accent, onClick = onClick, contentPadding = Space.md, spacing = Space.sm) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            IconBadge(look.icon, look.fg, look.bg, size = 36.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    Text(
                        levelLabel(row.level),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = look.fg,
                        maxLines = 1,
                    )
                    if (row.category.isNotBlank()) {
                        Text(
                            row.category,
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
                Text(
                    row.message,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (fields > 0) {
                    Text(
                        "$fields detail${if (fields == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            Text(Fmt.time(row.createdAt), style = Mono.small, color = scheme.onSurfaceVariant)
        }
    }
}

// ---- detail -----------------------------------------------------------------------------------------------------

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("Polybot log", text))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogDetailSheet(row: LogRow, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val look = levelLook(row.level)
    val context = LocalContext.current
    val ui = LocalUi.current
    val payloadText = remember(row) {
        row.payload?.takeIf { it.isNotEmpty() }?.let { prettyJson.encodeToString(JsonObject.serializer(), it) }
    }
    val mode = row.mode
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
                    Text(
                        row.category.ifBlank { "Log entry" },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(Fmt.dateTime(row.createdAt), style = Mono.small, color = scheme.onSurfaceVariant)
                }
                ToneChip(levelLabel(row.level), levelTone(row.level))
            }

            ProCard {
                Label("Message")
                SelectionContainer {
                    Text(row.message, style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (payloadText != null) {
                ProCard {
                    Label("Details")
                    SelectionContainer {
                        Text(
                            payloadText,
                            style = Mono.small,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = "Log payload" },
                        )
                    }
                }
            }

            if (row.id != null || !mode.isNullOrBlank()) {
                ProCard {
                    Label("Reference")
                    if (row.id != null) KeyValueRow("Entry", "#${row.id}")
                    if (!mode.isNullOrBlank()) KeyValueRow("Ledger", mode.uppercase())
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                OutlinedButton(
                    onClick = {
                        copyToClipboard(context, LogsLogic.entryText(row, payloadText))
                        ui.message("Log entry copied")
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(end = Space.sm)
                            .size(18.dp),
                    )
                    Text("Copy")
                }
                FilledTonalButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Close") }
            }
        }
    }
}
