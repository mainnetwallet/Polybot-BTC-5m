package com.mainnetwallet.polybot.ui.strategy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mainnetwallet.polybot.AppGraph
import com.mainnetwallet.polybot.data.model.TriggerRow
import com.mainnetwallet.polybot.data.net.ApiException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Recent trigger events (threshold hits, including blocked and skipped decisions). */
class StrategyViewModel(private val graph: AppGraph) : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val triggers: List<TriggerRow> = emptyList(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    init {
        viewModelScope.launch {
            graph.repository.dataVersion.conflate().collect {
                load()
                delay(1000)
            }
        }
    }

    private suspend fun load() {
        try {
            val rows = graph.api.triggers(100)
            _state.update { State(loading = false, triggers = rows, error = null) }
        } catch (e: ApiException) {
            _state.update { it.copy(loading = false, error = e.message) }
        }
    }

    suspend fun reload() = load()
}
