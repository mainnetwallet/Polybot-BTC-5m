package com.mainnetwallet.polybot.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mainnetwallet.polybot.AppGraph
import com.mainnetwallet.polybot.data.model.LogRow
import com.mainnetwallet.polybot.domain.LevelFilter
import com.mainnetwallet.polybot.domain.LogsLogic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Logs screen state. The list itself lives in the repository (REST history + live "log" events from the
 * WebSocket, newest first); this ViewModel only holds the active filters and the search text.
 */
class LogsViewModel(private val graph: AppGraph) : ViewModel() {

    data class Filters(
        val level: LevelFilter = LevelFilter.All,
        /** Null = every category. */
        val category: String? = null,
        /** Search text; blank = no search. */
        val query: String = "",
    ) {
        val isActive: Boolean get() = level != LevelFilter.All || category != null || query.isNotBlank()
    }

    private val _filters = MutableStateFlow(Filters())
    val filters: StateFlow<Filters> = _filters

    val logs: StateFlow<List<LogRow>> = graph.repository.logs

    init {
        viewModelScope.launch {
            if (graph.repository.logs.value.isEmpty()) graph.repository.loadLogs()
        }
    }

    fun setLevel(level: LevelFilter) = _filters.update { it.copy(level = level) }

    fun setCategory(category: String?) = _filters.update { it.copy(category = category) }

    fun setQuery(query: String) = _filters.update { it.copy(query = query) }

    fun clearFilters() {
        _filters.value = Filters()
    }

    suspend fun reload() = graph.repository.loadLogs()

    companion object {
        /** Applies [filters] to [rows] (already newest first). */
        fun filter(rows: List<LogRow>, filters: Filters): List<LogRow> =
            LogsLogic.filter(rows, filters.level, filters.category, filters.query)

        /** Distinct non-blank categories, most frequent first. */
        fun categories(rows: List<LogRow>): List<String> = LogsLogic.categories(rows)
    }
}
