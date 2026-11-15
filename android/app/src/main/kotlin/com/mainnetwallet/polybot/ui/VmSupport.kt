package com.mainnetwallet.polybot.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mainnetwallet.polybot.AppGraph
import com.mainnetwallet.polybot.ui.analytics.AnalyticsViewModel
import com.mainnetwallet.polybot.ui.logs.LogsViewModel
import com.mainnetwallet.polybot.ui.strategy.StrategyViewModel
import com.mainnetwallet.polybot.ui.trading.TradingViewModel

/** Builds the screen ViewModels from the hand-wired [AppGraph]. */
class GraphVmFactory(private val graph: AppGraph) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val vm: ViewModel = when {
            modelClass.isAssignableFrom(TradingViewModel::class.java) -> TradingViewModel(graph)
            modelClass.isAssignableFrom(StrategyViewModel::class.java) -> StrategyViewModel(graph)
            modelClass.isAssignableFrom(AnalyticsViewModel::class.java) -> AnalyticsViewModel(graph)
            modelClass.isAssignableFrom(LogsViewModel::class.java) -> LogsViewModel(graph)
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
