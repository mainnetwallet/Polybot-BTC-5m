package com.mainnetwallet.polybot.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mainnetwallet.polybot.AppGraph
import com.mainnetwallet.polybot.data.store.ThemeMode
import com.mainnetwallet.polybot.ui.connect.ConnectScreen
import com.mainnetwallet.polybot.ui.connect.LOCAL_NETWORK_DENIED_MESSAGE
import com.mainnetwallet.polybot.ui.connect.rememberLocalNetworkGate
import com.mainnetwallet.polybot.ui.main.MainScaffold
import com.mainnetwallet.polybot.ui.theme.PolybotTheme

/** Root of the UI: first-run login, or the main tabs once an address and token are saved. */
@Composable
fun AppRoot(graph: AppGraph) {
    val prefs by graph.prefs.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val ui = remember(snackbar, scope, haptic) { UiHost(snackbar, scope, haptic, true) }
    val p = prefs
    ui.hapticsEnabled = p?.haptics ?: true

    // Android 17+: an address saved before the update may be a LAN device; ask for the permission it now needs.
    val localNetworkGate = rememberLocalNetworkGate { ui.message(LOCAL_NETWORK_DENIED_MESSAGE, long = true) }
    val savedAddress = p?.takeIf { it.isConfigured }?.pcUrl
    LaunchedEffect(savedAddress) {
        if (savedAddress != null) localNetworkGate(savedAddress) {}
    }

    PolybotTheme(mode = p?.themeMode ?: ThemeMode.SYSTEM) {
        CompositionLocalProvider(LocalGraph provides graph, LocalUi provides ui) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                if (p == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (!p.isConfigured) {
                    Scaffold(
                        snackbarHost = { SnackbarHost(snackbar) },
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    ) { inner ->
                        ConnectScreen(
                            initialUrl = p.pcUrl,
                            initialToken = "",
                            modifier = Modifier.padding(inner),
                        )
                    }
                } else {
                    MainScaffold(prefs = p, snackbar = snackbar)
                }
            }
        }
    }
}
