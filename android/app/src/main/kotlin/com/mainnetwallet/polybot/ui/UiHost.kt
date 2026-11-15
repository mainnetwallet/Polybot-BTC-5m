package com.mainnetwallet.polybot.ui

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import com.mainnetwallet.polybot.AppGraph
import com.mainnetwallet.polybot.PolybotApp
import com.mainnetwallet.polybot.data.ActionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

val LocalGraph = staticCompositionLocalOf<AppGraph> { error("AppGraph not provided") }

/** Snackbars and haptics for user actions. One instance for the whole UI. */
class UiHost(
    val snackbar: SnackbarHostState,
    private val scope: CoroutineScope,
    private val feedback: HapticFeedback,
    var hapticsEnabled: Boolean,
) {
    fun message(text: String, long: Boolean = false) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(
                message = text,
                withDismissAction = true,
                duration = if (long) SnackbarDuration.Long else SnackbarDuration.Short,
            )
        }
    }

    fun tap() {
        if (hapticsEnabled) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun strong() {
        if (hapticsEnabled) feedback.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    /** Shows the server's answer; errors stay on screen longer. */
    fun report(result: ActionResult) {
        if (result.ok) strong()
        message(result.message, long = !result.ok)
    }
}

val LocalUi = compositionLocalOf<UiHost> { error("UiHost not provided") }

fun Context.polybotGraph(): AppGraph = (applicationContext as PolybotApp).graph

