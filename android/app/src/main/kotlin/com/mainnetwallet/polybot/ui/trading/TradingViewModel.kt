package com.mainnetwallet.polybot.ui.trading

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mainnetwallet.polybot.AppGraph
import com.mainnetwallet.polybot.data.model.OrderRow
import com.mainnetwallet.polybot.data.net.ApiException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Order history of the active mode. Refetches (at most about once a second) when orders or positions change. */
class TradingViewModel(private val graph: AppGraph) : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val orders: List<OrderRow> = emptyList(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    init {
        viewModelScope.launch {
            combine(
                graph.repository.snapshot.map { it?.bot?.mode }.distinctUntilChanged(),
                graph.repository.dataVersion,
            ) { mode, _ -> mode }
                .conflate()
                .collect { mode ->
                    if (mode != null) {
                        load(mode)
                        delay(800)
                    }
                }
        }
    }

    private suspend fun load(mode: String) {
        try {
            val orders = coroutineScope { async { graph.api.orders(mode, 100) }.await() }
            _state.update { State(loading = false, orders = orders, error = null) }
        } catch (e: ApiException) {
            _state.update { it.copy(loading = false, error = e.message) }
        }
    }

    suspend fun reload() {
        val mode = graph.repository.snapshot.value?.bot?.mode ?: return
        load(mode)
    }
}
