package com.fuzzymistborn.cubby

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

private fun NetworkCapabilities?.isWifi() = this != null && hasTransport(NetworkCapabilities.TRANSPORT_WIFI)

/** True while some Wi-Fi network is connected (it needn't have working internet). */
fun wifiConnected(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
    return cm.allNetworks.any { cm.getNetworkCapabilities(it).isWifi() }
}

/**
 * Calls [onChange] with the new Wi-Fi state whenever it flips. Returns a stop function.
 * If the connectivity service is unavailable, nothing is registered and the state stays "connected".
 */
fun watchWifi(context: Context, onChange: (Boolean) -> Unit): () -> Unit {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return {}
    val up = mutableSetOf<Network>()
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { synchronized(up) { up += network; onChange(true) } }
        override fun onLost(network: Network) { synchronized(up) { up -= network; onChange(up.isNotEmpty()) } }
    }
    val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
    try {
        cm.registerNetworkCallback(request, callback)
    } catch (e: SecurityException) {
        return {}
    }
    return { runCatching { cm.unregisterNetworkCallback(callback) } }
}
