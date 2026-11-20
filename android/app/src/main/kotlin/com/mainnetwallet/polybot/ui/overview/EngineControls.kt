package com.mainnetwallet.polybot.ui.overview

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mainnetwallet.polybot.data.ActionResult
import com.mainnetwallet.polybot.data.model.Modes
import com.mainnetwallet.polybot.ui.LocalGraph
import com.mainnetwallet.polybot.ui.collectSelectedAsState
import com.mainnetwallet.polybot.ui.LocalUi
import com.mainnetwallet.polybot.ui.safety.ConfirmDialog
import com.mainnetwallet.polybot.ui.safety.PhraseDialog
import kotlinx.coroutines.launch

private enum class EngineDialog { StartLive, SwitchLive, ClearKill }

/**
 * Start/Stop, PAPER | LIVE and kill switch with their safety dialogs. Used in the top bar so the engine can
 * be controlled from every tab. Draws nothing until the first snapshot has arrived.
 */
@Composable
fun EngineControls(modifier: Modifier = Modifier) {
    val graph = LocalGraph.current
    val ui = LocalUi.current
    val repo = graph.repository
    val botState by repo.snapshot.collectSelectedAsState { it?.bot }
    val buysOn by repo.snapshot.collectSelectedAsState { it?.buyGuard?.buysEnabled ?: true }
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<EngineDialog?>(null) }

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

    val bot = botState ?: return
    EngineControlsRow(
        bot = bot,
        busy = busy,
        onStart = {
            ui.tap()
            if (bot.isLive) dialog = EngineDialog.StartLive else perform { repo.startBot("") }
        },
        onStop = {
            ui.tap()
            perform { repo.stopBot() }
        },
        onMode = { mode ->
            ui.tap()
            if (mode == Modes.LIVE) dialog = EngineDialog.SwitchLive else perform { repo.switchMode(mode, "") }
        },
        onKill = { active ->
            ui.tap()
            // Engaging is one tap on purpose (it is the emergency stop); clearing asks first.
            if (active) perform { repo.killSwitch(true) } else dialog = EngineDialog.ClearKill
        },
        buysOn = buysOn,
        // One tap either way: switching buying off is a safety action, and switching it on is the operator's call.
        onBuys = { enabled ->
            ui.tap()
            perform { repo.setBuys(enabled) }
        },
        modifier = modifier.fillMaxWidth(),
    )

    when (dialog) {
        EngineDialog.StartLive -> PhraseDialog(
            title = "Start LIVE engine",
            body = "The engine will send real orders with real money. Type the confirmation phrase (default: GO LIVE).",
            confirmLabel = "Start LIVE",
            hint = "Confirmation phrase",
            onSubmit = { phrase -> repo.startBot(phrase) },
            onDismiss = { dialog = null },
        )
        EngineDialog.SwitchLive -> PhraseDialog(
            title = "Switch to LIVE",
            body = "LIVE mode trades real money. Type the confirmation phrase (default: GO LIVE).",
            confirmLabel = "Switch to LIVE",
            hint = "Confirmation phrase",
            onSubmit = { phrase -> repo.switchMode(Modes.LIVE, phrase) },
            onDismiss = { dialog = null },
        )
        EngineDialog.ClearKill -> ConfirmDialog(
            title = "Clear the kill switch?",
            text = "The engine can be started again after this. It does not start by itself.",
            confirmLabel = "Clear",
            onConfirm = { perform { repo.killSwitch(false) } },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}
