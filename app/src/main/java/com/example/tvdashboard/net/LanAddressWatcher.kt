package com.example.tvdashboard.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address

/** Follow LAN address changes even if Internet connectivity remains true. */
class LanAddressWatcher(context: Context, private val onAddress: (String?) -> Unit) : AutoCloseable {
    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private val state = LanAddressState<Network>()
    private var last: String? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!running) return
            state.available(network)
            publish(null) // Never leave a QR advertising the previous Wi-Fi during DHCP.
            inspect(network, cm.getLinkProperties(network))
        }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            inspect(network, properties)
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            inspect(network, cm.getLinkProperties(network))
        }
        override fun onLost(network: Network) {
            state.lost(network)
            publish(state.currentAddress())
        }
    }
    private fun inspect(network: Network, properties: LinkProperties?) {
        if (!running) return
        val caps = cm.getNetworkCapabilities(network)
        val eligible = caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        val address = if (eligible) properties?.linkAddresses?.asSequence()?.map { it.address }
            ?.filterIsInstance<Inet4Address>()?.firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }?.hostAddress else null
        state.update(network, address)
        publish(state.currentAddress())
    }
    private fun publish(address: String?) {
        if (!running || address == last) return
        last = address
        onAddress(address)
    }
    fun start() {
        if (running) return
        running = true
        last = LocalNetwork.findLanIpv4(appContext)
        onAddress(last)
        val request = NetworkRequest.Builder().clearCapabilities()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build()
        // All callbacks are serialized on Main; no races with Activity lifecycle/UI.
        cm.registerNetworkCallback(request, callback, main)
    }
    override fun close() {
        if (!running) return
        running = false
        try { cm.unregisterNetworkCallback(callback) } catch (_: IllegalArgumentException) { }
        state.clear()
    }
}
