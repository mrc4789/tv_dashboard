package com.example.tvdashboard.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

object LocalNetwork {
    /** Use a real Wi-Fi/Ethernet interface, not an active VPN or cellular address. */
    fun findLanIpv4(context: Context): String? {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return null
        val active = cm.activeNetwork
        return cm.allNetworks.sortedBy { if (it == active) 0 else 1 }.asSequence()
            .filter { network ->
                val caps = cm.getNetworkCapabilities(network)
                caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                     caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            }
            .mapNotNull { cm.getLinkProperties(it) }
            .flatMap { it.linkAddresses.asSequence() }
            .map { it.address }.filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }?.hostAddress
    }
}
