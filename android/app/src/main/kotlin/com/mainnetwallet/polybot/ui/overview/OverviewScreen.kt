package com.mainnetwallet.polybot.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import com.mainnetwallet.polybot.ui.components.PolyPullToRefresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mainnetwallet.polybot.data.ActionResult
import com.mainnetwallet.polybot.ui.LocalGraph
import com.mainnetwallet.polybot.ui.LocalUi
import com.mainnetwallet.polybot.ui.components.SkeletonCard
import com.mainnetwallet.polybot.ui.theme.Space
import kotlinx.coroutines.launch

/**
 * Overview: hero (countdown, BTC and account numbers), UP/DOWN odds and the position box with instant Sell
 * buttons. The engine controls are in the top bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(modifier: Modifier = Modifier) {
    val graph = LocalGraph.current
    val ui = LocalUi.current
    val repo = graph.repository
    val snapshot by repo.snapshot.collectAsStateWithLifecycle()
    val marketEndMs by repo.marketEndLocalMs.collectAsStateWithLifecycle()
    val history by repo.history.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var refreshing by remember { mutableStateOf(false) }
    var selling by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Instant sell: the request goes out the moment the button is tapped (no confirm dialog, no sheet).
    // `selling` only stops a second tap on the same button while its request is still on the way.
    fun sell(key: String, call: suspend () -> ActionResult) {
        if (key in selling) return
        selling = selling + key
        scope.launch {
            val result = call()
            selling = selling - key
            ui.report(result)
        }
    }

    val snap = snapshot
    PolyPullToRefresh(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            scope.launch {
                val result = repo.refresh()
                refreshing = false
                if (!result.ok) ui.message(result.message, long = true)
            }
        },
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.md, vertical = Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            if (snap == null) {
                SkeletonCard(lines = 3)
                SkeletonCard(lines = 2)
                SkeletonCard(lines = 2)
            } else {
                EngineNotices(snap.bot, snap.live)
                HeroCard(snap.market, snap.btc, history.btc, snap.account, snap.strategy.inWindow, marketEndMs)
                OddsCard(
                    up = snap.books.up,
                    down = snap.books.down,
                    upArmed = snap.strategy.upArmed,
                    downArmed = snap.strategy.downArmed,
                    upHistory = history.up,
                    downHistory = history.down,
                )
                PositionBoxCard(
                    marketId = snap.market?.marketId,
                    positions = snap.positions,
                    sellable = snap.sellable,
                    upBook = snap.books.up,
                    downBook = snap.books.down,
                    selling = selling,
                    onSell = { h -> sell(sellKey(h)) { repo.sellNow(h.marketId, h.side, h.stage) } },
                    onSellAll = { marketId -> sell(SELL_ALL_KEY) { repo.sellAll(marketId) } },
                )
            }
        }
    }
}
