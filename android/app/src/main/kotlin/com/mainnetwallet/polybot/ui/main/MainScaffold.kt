package com.mainnetwallet.polybot.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mainnetwallet.polybot.data.net.FeedState
import com.mainnetwallet.polybot.data.net.PcTarget
import com.mainnetwallet.polybot.data.net.TargetParse
import com.mainnetwallet.polybot.data.net.UrlUtils
import com.mainnetwallet.polybot.data.store.AppPrefs
import com.mainnetwallet.polybot.ui.LocalGraph
import com.mainnetwallet.polybot.ui.connect.ConnectScreen
import com.mainnetwallet.polybot.ui.analytics.AnalyticsScreen
import com.mainnetwallet.polybot.ui.logs.LogsScreen
import com.mainnetwallet.polybot.ui.overview.OverviewScreen
import com.mainnetwallet.polybot.ui.settings.SettingsScreen
import com.mainnetwallet.polybot.ui.strategy.StrategyScreen
import com.mainnetwallet.polybot.ui.trading.TradingScreen
import com.mainnetwallet.polybot.ui.theme.Poly
import com.mainnetwallet.polybot.ui.theme.Space

/** The five tabs of the bottom bar. Settings lives behind the gear in the top bar. */
enum class Dest(val route: String, val label: String, val icon: ImageVector) {
    Overview("overview", "Overview", Icons.Filled.Dashboard),
    Trading("trading", "Trading", Icons.Filled.SwapVert),
    Strategy("strategy", "Strategy", Icons.Filled.Tune),
    Analytics("analytics", "Analytics", Icons.Filled.BarChart),
    Logs("logs", "Logs", Icons.AutoMirrored.Filled.List),
}

const val ROUTE_SETTINGS = "settings"
const val ROUTE_CONNECTION = "connection"

@Composable
fun MainScaffold(prefs: AppPrefs, snackbar: SnackbarHostState) {
    val graph = LocalGraph.current
    val nav = rememberNavController()
    val backEntry by nav.currentBackStackEntryAsState()
    val route = backEntry?.destination?.route
    val snapshot by graph.repository.snapshot.collectAsStateWithLifecycle()
    val feed by graph.repository.feedState.collectAsStateWithLifecycle()
    val live = snapshot?.bot?.isLive == true
    val onTab = Dest.entries.any { it.route == route }

    Scaffold(
        topBar = {
            PolyTopBar(
                live = live,
                showModeBadge = snapshot != null,
                onTab = onTab || route == null,
                onBack = if (!onTab && route != null) ({ nav.popBackStack() }) else null,
                onSettings = { nav.navigate(ROUTE_SETTINGS) { launchSingleTop = true } },
                title = {
                    when {
                        onTab || route == null -> ConnectionPill(feed)
                        route == ROUTE_CONNECTION -> Text("Connection", style = MaterialTheme.typography.titleLarge)
                        else -> Text("Settings", style = MaterialTheme.typography.titleLarge)
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Dest.entries.forEach { dest ->
                    NavigationBarItem(
                        selected = route == dest.route,
                        onClick = {
                            nav.navigate(dest.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = null) },
                        label = { Text(dest.label, maxLines = 1) },
                        alwaysShowLabel = true,
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(Modifier.padding(inner)) {
            StatusBanners(prefs = prefs, feed = feed, onEditConnection = {
                nav.navigate(ROUTE_CONNECTION) { launchSingleTop = true }
            })
            NavHost(
                navController = nav,
                startDestination = Dest.Overview.route,
                modifier = Modifier.weight(1f),
            ) {
                composable(Dest.Overview.route) { OverviewScreen() }
                composable(Dest.Trading.route) { TradingScreen() }
                composable(Dest.Strategy.route) { StrategyScreen() }
                composable(Dest.Analytics.route) { AnalyticsScreen() }
                composable(Dest.Logs.route) { LogsScreen() }
                composable(ROUTE_SETTINGS) {
                    SettingsScreen(onEditConnection = { nav.navigate(ROUTE_CONNECTION) { launchSingleTop = true } })
                }
                composable(ROUTE_CONNECTION) {
                    ConnectScreen(
                        initialUrl = prefs.pcUrl,
                        initialToken = prefs.token,
                        firstRun = false,
                        embedded = true,
                        onDone = { nav.popBackStack() },
                    )
                }
            }
        }
    }
}

/**
 * Compact top bar: one 52dp row below the status bar on the normal surface colour (no coloured fill).
 * LIVE is shown by the solid red badge and a 3dp red line along the bottom edge; PAPER gets a hairline.
 */
@Composable
private fun PolyTopBar(
    live: Boolean,
    showModeBadge: Boolean,
    onTab: Boolean,
    onBack: (() -> Unit)?,
    onSettings: () -> Unit,
    title: @Composable () -> Unit,
) {
    val colors = Poly.colors
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(52.dp)
                    .padding(horizontal = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                } else {
                    Spacer(Modifier.width(Space.sm))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { title() }
                if (showModeBadge) ModeBadge(live)
                if (onTab) {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                } else {
                    Spacer(Modifier.width(Space.md))
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(if (live) 3.dp else 1.dp)
                    .background(if (live) colors.live else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            )
        }
    }
}

@Composable
private fun StatusBanners(prefs: AppPrefs, feed: FeedState, onEditConnection: () -> Unit) {
    val colors = Poly.colors
    val target = (UrlUtils.parseTarget(prefs.pcUrl) as? TargetParse.Ok)?.target
    val insecure = target is PcTarget.Direct && UrlUtils.isInsecureOutsidePrivate(target.baseUrl)
    if (insecure) {
        Banner(
            text = "Plain http outside your home network: the token and all data travel unencrypted. Use https or a Gist link.",
            container = colors.warnContainer,
            accent = colors.warn,
        )
    }
    if (feed == FeedState.AuthFailed) {
        Banner(
            text = "The PC rejected the API token. Check API_TOKEN in the PC's .env file.",
            container = colors.downContainer,
            accent = colors.down,
            actionLabel = "Edit",
            onAction = onEditConnection,
        )
    }
}

@Composable
private fun Banner(
    text: String,
    container: androidx.compose.ui.graphics.Color,
    accent: androidx.compose.ui.graphics.Color,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Surface(color = container, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = Space.lg, vertical = Space.sm),
            horizontalArrangement = Arrangement.spacedBy(Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = accent)
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (actionLabel != null) TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
