package com.example.tvdashboard.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Tracks the default network, without requiring public Internet validation for LAN URLs. */
class NetworkMonitor(context: Context) {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    fun isOnlineNow(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    val isOnline: Flow<Boolean> = callbackFlow {
        val stateLock = Any()
        var currentNetwork: Network? = null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(stateLock) {
                    if (currentNetwork != null && currentNetwork != network) trySend(false)
                    currentNetwork = network
                    // Wait for capabilities instead of assuming that the new network works.
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                synchronized(stateLock) {
                    if (currentNetwork == network) {
                        trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                    }
                }
            }

            override fun onLost(network: Network) {
                synchronized(stateLock) {
                    // A late notification from an old network must not mark a new one offline.
                    if (currentNetwork == network) {
                        currentNetwork = null
                        trySend(false)
                    }
                }
            }
        }
        synchronized(stateLock) {
            currentNetwork = connectivityManager.activeNetwork
            trySend(isOnlineNow())
            connectivityManager.registerDefaultNetworkCallback(callback)
        }
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
