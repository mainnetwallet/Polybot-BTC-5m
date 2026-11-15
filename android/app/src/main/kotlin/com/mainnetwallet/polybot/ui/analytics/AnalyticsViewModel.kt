package com.mainnetwallet.polybot.ui.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mainnetwallet.polybot.AppGraph
import com.mainnetwallet.polybot.data.model.Analytics
import com.mainnetwallet.polybot.data.model.Modes
import com.mainnetwallet.polybot.data.net.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Analytics of the paper or the live ledger. Looking at the other mode never switches the bot. */
class AnalyticsViewModel(private val graph: AppGraph) : ViewModel() {
    data class State(
        val mode: String = Modes.PAPER,
        val loading: Boolean = true,
        val analytics: Analytics? = null,
        val error: String? = null,
    )

    private val selected = MutableStateFlow<String?>(null)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    init {
        viewModelScope.launch {
            combine(
                selected,
                graph.repository.snapshot.map { it?.bot?.mode }.distinctUntilChanged(),
                graph.repository.dataVersion,
            ) { chosen, activeMode, _ -> chosen ?: activeMode }
                .conflate()
                .collect { mode ->
                    if (mode != null) {
                        _state.update { it.copy(mode = mode) }
                        load(mode)
                    }
                }
        }
    }

    fun select(mode: String) {
        selected.value = mode
        _state.update { State(mode = mode, loading = true) }
    }

    private suspend fun load(mode: String) {
        try {
            val result = graph.api.analytics(mode)
            _state.update { if (it.mode == mode) State(mode = mode, loading = false, analytics = result) else it }
        } catch (e: ApiException) {
            _state.update { it.copy(loading = false, error = e.message) }
        }
    }

    suspend fun reload() = load(_state.value.mode)
}
