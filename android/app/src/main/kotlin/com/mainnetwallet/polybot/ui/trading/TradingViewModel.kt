package com.mainnetwallet.polybot.ui.trading

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mainnetwallet.polybot.AppGraph
import com.mainnetwallet.polybot.data.model.AccountState
import com.mainnetwallet.polybot.data.model.BotState
import com.mainnetwallet.polybot.data.model.OrderRow
import com.mainnetwallet.polybot.data.model.PositionRow
import com.mainnetwallet.polybot.data.model.SellableHolding
import com.mainnetwallet.polybot.data.model.Snapshot
import com.mainnetwallet.polybot.data.net.ApiException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The parts of a snapshot the Trading tab shows. The full snapshot is replaced on every order-book tick (several
 * times a second); this slice only changes when one of these fields changes, so the screen does not recompose
 * (and scrolling stays smooth) while the books move.
 */
data class TradingSlice(
    val bot: BotState,
    val account: AccountState,
    val positions: List<PositionRow>,
    val openOrders: List<OrderRow>,
    val sellable: List<SellableHolding>,
)

private fun Snapshot.toTradingSlice() = TradingSlice(bot, account, positions, openOrders, sellable)

/** Order history of the active mode. Refetches (at most about once a second) when orders or positions change. */
class TradingViewModel(private val graph: AppGraph) : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val orders: List<OrderRow> = emptyList(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    val slice: StateFlow<TradingSlice?> = graph.repository.snapshot
        .map { it?.toTradingSlice() }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            graph.repository.snapshot.value?.toTradingSlice(),
        )

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
