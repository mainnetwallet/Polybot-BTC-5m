package com.mainnetwallet.polybot.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/**
 * Tells the app when the phone switches network (Wi-Fi <-> mobile data) so the live feed can reconnect at once
 * instead of waiting for the old socket to time out.
 */
class NetworkMonitor(context: Context, private val onNetworkChanged: () -> Unit) {
    private val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var current: Network? = null
    private var seenFirst = false
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val changed = seenFirst && network != current
            seenFirst = true
            current = network
            if (changed) onNetworkChanged()
        }

        override fun onLost(network: Network) {
            if (network == current) current = null
        }
    }

    fun start() {
        if (registered) return
        try {
            manager.registerDefaultNetworkCallback(callback)
            registered = true
        } catch (_: Exception) {
            // without the callback the backoff loop still reconnects, just a little later
        }
    }
}
