package com.mainnetwallet.polybot.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.mainnetwallet.polybot.data.ActionResult
import com.mainnetwallet.polybot.data.model.Modes
import com.mainnetwallet.polybot.ui.LocalGraph
import com.mainnetwallet.polybot.ui.LocalUi
import com.mainnetwallet.polybot.ui.components.SkeletonCard
import com.mainnetwallet.polybot.ui.safety.ConfirmDialog
import com.mainnetwallet.polybot.ui.safety.PhraseDialog
import com.mainnetwallet.polybot.ui.theme.Space
import kotlinx.coroutines.launch

private enum class OverviewDialog { StartLive, SwitchLive, ClearKill }

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
    var busy by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<OverviewDialog?>(null) }

    // Runs one request, keeps the buttons disabled while it is in flight and shows the server's answer.
    val perform: (suspend () -> ActionResult) -> Unit = { block ->
        if (!busy) {
            busy = true
            scope.launch {
                val result = block()
                busy = false
                ui.report(result)
            }
        }
    }

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
                .padding(Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            if (snap == null) {
                SkeletonCard(lines = 4)
                SkeletonCard(lines = 3)
                SkeletonCard(lines = 3)
            } else {
                MarketCard(snap.market, snap.strategy.inWindow, offsetMs)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    SideCard("UP", snap.books.up, snap.strategy.upArmed, history.up, Modifier.weight(1f))
                    SideCard("DOWN", snap.books.down, snap.strategy.downArmed, history.down, Modifier.weight(1f))
                }
                BtcCard(snap.btc, history.btc)
                BotCard(
                    bot = snap.bot,
                    live = snap.live,
                    busy = busy,
                    onStart = {
                        ui.tap()
                        if (snap.bot.isLive) dialog = OverviewDialog.StartLive else perform { repo.startBot("") }
                    },
                    onStop = {
                        ui.tap()
                        perform { repo.stopBot() }
                    },
                    onMode = { mode ->
                        ui.tap()
                        if (mode == Modes.LIVE) dialog = OverviewDialog.SwitchLive else perform { repo.switchMode(mode, "") }
                    },
                    onKill = { active ->
                        ui.tap()
                        // Engaging is one tap on purpose (it is the emergency stop); clearing asks first.
                        if (active) perform { repo.killSwitch(true) } else dialog = OverviewDialog.ClearKill
                    },
                )
                AccountCard(snap.account)
                LastOrderCard(snap.lastOrder)
            }
        }
    }

    when (dialog) {
        OverviewDialog.StartLive -> PhraseDialog(
            title = "Start LIVE engine",
            body = "The engine will send real orders with real money. Type the confirmation phrase (default: GO LIVE).",
            confirmLabel = "Start LIVE",
            hint = "Confirmation phrase",
            onSubmit = { phrase -> repo.startBot(phrase) },
            onDismiss = { dialog = null },
        )
        OverviewDialog.SwitchLive -> PhraseDialog(
            title = "Switch to LIVE",
            body = "LIVE mode trades real money. Type the confirmation phrase (default: GO LIVE).",
            confirmLabel = "Switch to LIVE",
            hint = "Confirmation phrase",
            onSubmit = { phrase -> repo.switchMode(Modes.LIVE, phrase) },
            onDismiss = { dialog = null },
        )
        OverviewDialog.ClearKill -> ConfirmDialog(
            title = "Clear the kill switch?",
            text = "The engine can be started again after this. It does not start by itself.",
            confirmLabel = "Clear",
            onConfirm = { perform { repo.killSwitch(false) } },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}
