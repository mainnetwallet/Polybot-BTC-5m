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
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.mainnetwallet.polybot.data.store.AppPrefs
import com.mainnetwallet.polybot.domain.SettingsSpec
import com.mainnetwallet.polybot.ui.LocalGraph
import com.mainnetwallet.polybot.ui.collectSelectedAsState
import com.mainnetwallet.polybot.ui.connect.ConnectScreen
import com.mainnetwallet.polybot.ui.analytics.AnalyticsScreen
import com.mainnetwallet.polybot.ui.logs.LogsScreen
import com.mainnetwallet.polybot.ui.overview.EngineControls
import com.mainnetwallet.polybot.ui.overview.OverviewScreen
import com.mainnetwallet.polybot.ui.settings.SettingsDangerPage
import com.mainnetwallet.polybot.ui.settings.SettingsHome
import com.mainnetwallet.polybot.ui.settings.SettingsSectionPage
import com.mainnetwallet.polybot.ui.strategy.StrategyScreen
import com.mainnetwallet.polybot.ui.trading.TradingScreen
import com.mainnetwallet.polybot.ui.theme.Poly
import com.mainnetwallet.polybot.ui.theme.Space

/** The five tabs of the bottom bar. Settings lives behind the gear in the top bar. */
enum class Dest(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    Overview("overview", "Overview", Icons.Outlined.Dashboard, Icons.Filled.Dashboard),
    Trading("trading", "Trading", Icons.Outlined.SwapVert, Icons.Filled.SwapVert),
    Strategy("strategy", "Strategy", Icons.Outlined.Tune, Icons.Filled.Tune),
    Analytics("analytics", "Analytics", Icons.Outlined.BarChart, Icons.Filled.BarChart),
    Logs("logs", "Logs", Icons.Outlined.Description, Icons.Filled.Description),
}

const val ROUTE_SETTINGS = "settings"
const val ROUTE_SETTINGS_DANGER = "settings/danger"
const val ROUTE_CONNECTION = "connection"

/** Route of the detail page of one trading section (`entry`, `stage0` ... `paper`). */
fun settingsSectionRoute(sectionId: String): String = "settings/section/$sectionId"

/** Title of the top bar on the pages that are not one of the five tabs. */
private fun routeTitle(route: String?): String = when {
    route == ROUTE_CONNECTION -> "Connection"
    route == ROUTE_SETTINGS_DANGER -> "Danger zone"
    else -> SettingsSpec.sections.firstOrNull { settingsSectionRoute(it.id) == route }?.title ?: "Settings"
}

@Composable
fun MainScaffold(prefs: AppPrefs, snackbar: SnackbarHostState) {
    val graph = LocalGraph.current
    val nav = rememberNavController()
    val backEntry by nav.currentBackStackEntryAsState()
    val route = backEntry?.destination?.route
    // Only the few values the bars need: reading the whole snapshot here recomposed the scaffold on every tick.
    val hasSnapshot by graph.repository.snapshot.collectSelectedAsState { it != null }
    val feed by graph.repository.feedState.collectAsStateWithLifecycle()
    val live by graph.repository.snapshot.collectSelectedAsState { it?.bot?.isLive == true }
    val strategyArmed by graph.repository.snapshot.collectSelectedAsState { s ->
        s?.strategy?.let { it.upArmed || it.downArmed } == true
    }
    val onTab = Dest.entries.any { it.route == route }

    Scaffold(
        topBar = {
            PolyTopBar(
                live = live,
                onTab = onTab || route == null,
                onBack = if (!onTab && route != null) ({ nav.popBackStack() }) else null,
                onSettings = { nav.navigate(ROUTE_SETTINGS) { launchSingleTop = true } },
                title = {
                    when {
                        // Start/Stop, PAPER | LIVE and kill switch replace the old feed/mode badges.
                        onTab || route == null -> if (hasSnapshot) EngineControls() else ConnectionPill(feed)
                        else -> Text(routeTitle(route), style = MaterialTheme.typography.titleLarge)
                    }
                },
            )
        },
        bottomBar = {
            PolyBottomBar(
                route = route,
                attention = if (strategyArmed) setOf(Dest.Strategy) else emptySet(),
                onSelect = { dest ->
                    nav.navigate(dest.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(Modifier.padding(inner)) {
            StatusBanners(feed = feed, onEditConnection = {
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
                    SettingsHome(
                        onEditConnection = { nav.navigate(ROUTE_CONNECTION) { launchSingleTop = true } },
                        onOpenSection = { id -> nav.navigate(settingsSectionRoute(id)) { launchSingleTop = true } },
                        onOpenDanger = { nav.navigate(ROUTE_SETTINGS_DANGER) { launchSingleTop = true } },
                    )
                }
                // The detail pages share the hub's ViewModel, so edits survive moving between pages.
                SettingsSpec.sections.forEach { section ->
                    composable(settingsSectionRoute(section.id)) { entry ->
                        val hub = remember(entry) { nav.getBackStackEntry(ROUTE_SETTINGS) }
                        SettingsSectionPage(sectionId = section.id, owner = hub)
                    }
                }
                composable(ROUTE_SETTINGS_DANGER) { entry ->
                    val hub = remember(entry) { nav.getBackStackEntry(ROUTE_SETTINGS) }
                    SettingsDangerPage(owner = hub)
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
                if (onTab) {
                    FilledTonalIconButton(
                        onClick = onSettings,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
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
private fun StatusBanners(feed: FeedState, onEditConnection: () -> Unit) {
    val colors = Poly.colors
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
