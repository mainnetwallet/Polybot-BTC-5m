package com.mainnetwallet.polybot.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mainnetwallet.polybot.ui.theme.Poly

/**
 * Bottom navigation: a hairline on top, a flat surface, filled icons for the open tab and outlined icons for
 * the others, a tonal pill behind the open tab and an amber dot on a tab that needs attention.
 */
@Composable
fun PolyBottomBar(
    route: String?,
    attention: Set<Dest>,
    onSelect: (Dest) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = scheme.onPrimaryContainer,
        selectedTextColor = scheme.onSurface,
        indicatorColor = scheme.primaryContainer,
        unselectedIconColor = scheme.onSurfaceVariant,
        unselectedTextColor = scheme.onSurfaceVariant,
    )
    Surface(modifier = modifier, color = scheme.surfaceContainerLow) {
        Column {
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.6f))
            NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
                Dest.entries.forEach { dest ->
                    val selected = route == dest.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = { onSelect(dest) },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (dest in attention) Badge(containerColor = Poly.colors.warn)
                                },
                            ) {
                                Icon(if (selected) dest.selectedIcon else dest.icon, contentDescription = null)
                            }
                        },
                        label = {
                            Text(
                                dest.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                            )
                        },
                        alwaysShowLabel = true,
                        colors = colors,
                    )
                }
            }
        }
    }
}
