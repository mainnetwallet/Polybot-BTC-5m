package com.mainnetwallet.polybot.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How far the list must be pulled before a refresh starts. The Material default (80 dp) fired on a light,
 * accidental pull while scrolling back to the top; this makes it a deliberate gesture. The finger travels about
 * twice this distance (the pull is damped by half), so raise it for a harder pull and lower it for an easier one.
 */
val PullRefreshThreshold: Dp = 112.dp

/** Pull-to-refresh with the app's own, larger trigger distance. Same shape as Material's `PullToRefreshBox`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PolyPullToRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    Box(
        modifier = modifier.pullToRefresh(
            isRefreshing = isRefreshing,
            state = state,
            threshold = PullRefreshThreshold,
            onRefresh = onRefresh,
        ),
    ) {
        content()
        PullToRefreshDefaults.Indicator(
            state = state,
            isRefreshing = isRefreshing,
            threshold = PullRefreshThreshold,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}
