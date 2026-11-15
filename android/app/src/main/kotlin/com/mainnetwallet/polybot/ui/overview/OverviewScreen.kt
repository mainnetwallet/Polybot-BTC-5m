package com.mainnetwallet.polybot.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mainnetwallet.polybot.ui.LocalGraph
import com.mainnetwallet.polybot.ui.LocalUi
import com.mainnetwallet.polybot.ui.components.SkeletonCard
import com.mainnetwallet.polybot.ui.theme.Space
import kotlinx.coroutines.launch

/** Overview: hero (countdown + BTC), UP/DOWN odds and account. The engine controls are in the top bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(modifier: Modifier = Modifier) {
    val graph = LocalGraph.current
    val ui = LocalUi.current
    val repo = graph.repository
    val snapshot by repo.snapshot.collectAsStateWithLifecycle()
    val offsetMs by repo.clockOffsetMs.collectAsStateWithLifecycle()
    val history by repo.history.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var refreshing by remember { mutableStateOf(false) }

    val snap = snapshot
    PullToRefreshBox(
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
                HeroCard(snap.market, snap.btc, history.btc, snap.strategy.inWindow, offsetMs)
                OddsCard(
                    up = snap.books.up,
                    down = snap.books.down,
                    upArmed = snap.strategy.upArmed,
                    downArmed = snap.strategy.downArmed,
                    upHistory = history.up,
                    downHistory = history.down,
                )
                AccountCard(snap.account)
            }
        }
    }
}
