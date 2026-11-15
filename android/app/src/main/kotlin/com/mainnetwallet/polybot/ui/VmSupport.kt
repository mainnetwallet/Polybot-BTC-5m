package com.mainnetwallet.polybot.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mainnetwallet.polybot.AppGraph
import com.mainnetwallet.polybot.ui.analytics.AnalyticsViewModel
import com.mainnetwallet.polybot.ui.logs.LogsViewModel
import com.mainnetwallet.polybot.ui.settings.SettingsViewModel
import com.mainnetwallet.polybot.ui.strategy.StrategyViewModel
import com.mainnetwallet.polybot.ui.trading.TradingViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Builds the screen ViewModels from the hand-wired [AppGraph]. */
class GraphVmFactory(private val graph: AppGraph) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val vm: ViewModel = when {
            modelClass.isAssignableFrom(TradingViewModel::class.java) -> TradingViewModel(graph)
            modelClass.isAssignableFrom(StrategyViewModel::class.java) -> StrategyViewModel(graph)
            modelClass.isAssignableFrom(AnalyticsViewModel::class.java) -> AnalyticsViewModel(graph)
            modelClass.isAssignableFrom(LogsViewModel::class.java) -> LogsViewModel(graph)
            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(graph)
            else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
        return modelClass.cast(vm)!!
    }
}

@Composable
inline fun <reified T : ViewModel> graphViewModel(): T {
    val graph = LocalGraph.current
    return viewModel<T>(factory = GraphVmFactory(graph))
}

/**
 * Same as [graphViewModel] but scoped to [owner], so several navigation destinations (for example the Settings
 * hub and its detail pages) share one ViewModel instead of each getting its own.
 */
@Composable
inline fun <reified T : ViewModel> graphViewModel(owner: ViewModelStoreOwner): T {
    val graph = LocalGraph.current
    return viewModel<T>(viewModelStoreOwner = owner, factory = GraphVmFactory(graph))
}

/**
 * Collects only what [select] picks out of a fast-changing flow. The snapshot is replaced several times a second
 * (every order-book tick); a composable that reads it whole recomposes that often, which makes scrolling stutter.
 * This one recomposes only when the picked value really changes. [select] must be a pure function of its argument.
 */
@Composable
fun <T, R> StateFlow<T>.collectSelectedAsState(select: (T) -> R): State<R> {
    val source = this
    val selected = remember(source) { source.map(select).distinctUntilChanged() }
    return selected.collectAsStateWithLifecycle(initialValue = select(source.value))
}
