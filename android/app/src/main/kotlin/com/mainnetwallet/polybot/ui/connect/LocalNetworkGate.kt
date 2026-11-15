package com.mainnetwallet.polybot.ui.connect

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.mainnetwallet.polybot.data.net.LocalNetworkAccess

/**
 * Returns a function `gate(pcAddress) { ... }` that runs the block right away, or, on Android 17+ when the
 * address is a LAN device and the local network permission is missing, asks for it first. The block runs after
 * the answer either way (a denied permission then shows up as a failed connection test), and [onDenied] tells
 * the user why.
 */
@Composable
fun rememberLocalNetworkGate(onDenied: () -> Unit): (String, () -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val next = pending
        pending = null
        if (!granted) onDenied()
        next?.invoke()
    }
    return remember(context, launcher) {
        { pcAddress: String, then: () -> Unit ->
            if (LocalNetworkAccess.isMissingFor(context, pcAddress)) {
                pending = then
                launcher.launch(LocalNetworkAccess.PERMISSION)
            } else {
                then()
            }
        }
    }
}

const val LOCAL_NETWORK_DENIED_MESSAGE =
    "Local network access was denied, so Android blocks http://192.168.x.x addresses. " +
        "Allow \"Nearby devices\" for Polybot in the app settings, or use a Gist link / https address."
