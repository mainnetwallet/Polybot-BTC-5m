package com.mainnetwallet.polybot.ui.logs

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mainnetwallet.polybot.core.AppJson
import com.mainnetwallet.polybot.data.model.LogRow
import com.mainnetwallet.polybot.domain.LevelFilter
import com.mainnetwallet.polybot.domain.Tone
import com.mainnetwallet.polybot.ui.Fmt
import com.mainnetwallet.polybot.ui.components.EmptyState
import com.mainnetwallet.polybot.ui.components.PolyCard
import com.mainnetwallet.polybot.ui.components.SkeletonCard
import com.mainnetwallet.polybot.ui.components.ToneChip
import com.mainnetwallet.polybot.ui.graphViewModel
import com.mainnetwallet.polybot.ui.theme.Mono
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(modifier: Modifier = Modifier) {
    val vm: LogsViewModel = graphViewModel()
    val rows by vm.logs.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }

    val visible = remember(rows, filters) { LogsViewModel.filter(rows, filters) }
    val categories = remember(rows) { LogsViewModel.categories(rows) }

    PullToRefreshBox(
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
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            item(key = "filters") {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    ) {
                        LevelFilter.entries.forEach { level ->
                            FilterChip(
                                selected = filters.level == level,
                                onClick = { vm.setLevel(level) },
                                label = { Text(level.label) },
                            )
                        }
                    }
                    if (categories.isNotEmpty()) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(Space.sm),
                        ) {
                            FilterChip(
                                selected = filters.category == null,
                                onClick = { vm.setCategory(null) },
                                label = { Text("All categories") },
                            )
                            categories.forEach { category ->
                                FilterChip(
                                    selected = filters.category == category,
                                    onClick = { vm.setCategory(if (filters.category == category) null else category) },
                                    label = { Text(category) },
                                )
                            }
                        }
                    }
                    Text(
                        "${visible.size} of ${rows.size} entries, newest first. New entries appear live.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (visible.isEmpty()) {
                item(key = "empty") {
                    if (rows.isEmpty() && !refreshing) {
                        PolyCard {
                            EmptyState(
                                "No log entries yet",
                                "Entries show up here as the bot runs. Pull down to refresh.",
                                icon = Icons.AutoMirrored.Filled.List,
                            )
                        }
                    } else if (rows.isEmpty()) {
                        SkeletonCard(lines = 4)
                    } else {
                        PolyCard {
                            EmptyState(
                                "Nothing matches these filters",
                                "Pick another level or category.",
                                icon = Icons.AutoMirrored.Filled.List,
                            )
                        }
                    }
                }
            } else {
                itemsIndexed(
                    items = visible,
                    key = { index, row -> row.id?.let { "id$it" } ?: "n${row.createdAt}${row.message.hashCode()}$index" },
                ) { _, row ->
                    LogCard(row)
                }
            }
        }
    }
}

@Composable
private fun LogCard(row: LogRow) {
    var expanded by remember { mutableStateOf(false) }
    val payload: JsonObject? = row.payload?.takeIf { it.isNotEmpty() }
    PolyCard(
        modifier = Modifier
            .animateContentSize()
            .then(if (payload != null) Modifier.clickable { expanded = !expanded } else Modifier),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            ToneChip(levelLabel(row.level), levelTone(row.level))
            if (row.category.isNotBlank()) {
                Text(
                    row.category,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            } else {
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            }
            Text(Fmt.time(row.createdAt), style = Mono.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (payload != null) {
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Hide details" else "Show details",
                )
            }
        }
        Text(row.message, style = MaterialTheme.typography.bodyMedium)
        if (expanded && payload != null) {
            SelectionContainer {
                Text(
                    prettyJson.encodeToString(JsonObject.serializer(), payload),
                    style = Mono.small,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Space.xs)
                        .semantics { contentDescription = "Log payload" },
                )
            }
        }
    }
}
