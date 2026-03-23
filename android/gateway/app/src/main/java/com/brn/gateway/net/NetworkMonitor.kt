package com.brn.gateway.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

class NetworkMonitor(
    context: Context,
    private val onNetworkChanged: (String) -> Unit
) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            onNetworkChanged(currentNetworkType())
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            onNetworkChanged(currentNetworkType())
        }
    }

    fun start() {
        connectivityManager.registerDefaultNetworkCallback(callback)
    }

    fun stop() {
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }

    fun currentNetworkType(): String {
        val network = connectivityManager.activeNetwork ?: return "UNKNOWN"
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return "UNKNOWN"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "UNKNOWN"
        }
    }
}
