package com.example.tvdashboard.net

import android.content.Context
import com.example.tvdashboard.BuildConfig
import java.util.Properties

/** Được tools/phone_bridge.py cập nhật; hoàn toàn bỏ qua ở bản release. */
object DebugLanConfig {
    data class Bridge(val host: String, val phonePort: Int, val serverPort: Int) {
        val address: String get() = "http://$host:$phonePort/"
    }

    fun load(context: Context): Bridge? {
        if (!BuildConfig.DEBUG) return null
        return try {
            val properties = Properties().apply {
                context.openFileInput("lan-debug.properties").use { load(it) }
            }
            val host = properties.getProperty("host")?.trim() ?: return null
            // Chỉ chấp nhận IPv4 LAN, không nhận hostname hay URL tùy ý.
            val parts = host.split('.')
            if (parts.size != 4 || parts.any {
                    it.isEmpty() || it.any { char -> char !in '0'..'9' } ||
                        (it.toIntOrNull() ?: -1) !in 0..255
                } || !LanFilter.isAllowed(host, false)) return null
            val phonePort = properties.getProperty("phonePort")?.toIntOrNull() ?: return null
            val serverPort = properties.getProperty("serverPort")?.toIntOrNull() ?: return null
            if (phonePort !in 1024..65535 || serverPort !in 1024..65535) return null
            Bridge(host, phonePort, serverPort)
        } catch (_: Exception) {
            null
        }
    }
}
