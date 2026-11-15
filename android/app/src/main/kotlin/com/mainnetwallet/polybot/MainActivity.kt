package com.mainnetwallet.polybot

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mainnetwallet.polybot.data.store.ThemeMode
import com.mainnetwallet.polybot.ui.AppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val graph = (application as PolybotApp).graph
        // Keep the splash screen up until the saved login has been read, so there is no flash of the login form.
        splash.setKeepOnScreenCondition { graph.prefs.value == null }
        setContent {
            val prefs by graph.prefs.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val dark = when (prefs?.themeMode ?: ThemeMode.SYSTEM) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            // Status and navigation bar icons follow the app theme, not only the system theme.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
                onDispose {}
            }
            AppRoot(graph)
        }
    }
}
