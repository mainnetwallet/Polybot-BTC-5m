package com.mainnetwallet.polybot.data.net

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Android 17 (API 37) protects the local network behind a runtime permission for apps that target API 37.
 * Only the "PC address" of a LAN device (`http://192.168.x.x:8000`) needs it; a Gist link or an https tunnel
 * URL is plain internet traffic. Older Android versions do not have the permission, so nothing is asked there.
 */
object LocalNetworkAccess {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    private const val FIRST_API = 37

    fun isGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < FIRST_API ||
            ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** True when [pcAddress] is a LAN device and the permission is still missing. */
    fun isMissingFor(context: Context, pcAddress: String): Boolean =
        UrlUtils.isLocalNetworkTarget(pcAddress) && !isGranted(context)
}
