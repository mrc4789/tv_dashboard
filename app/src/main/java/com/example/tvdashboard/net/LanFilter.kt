package com.example.tvdashboard.net

import java.net.InetAddress

/** Chỉ cho phép client trong mạng nội bộ (và loopback ở bản debug để thử bằng adb forward). */
object LanFilter {

    private val IP_LITERAL = Regex("^[0-9a-fA-F:.]+$")

    fun isAllowed(ip: String?, allowLoopback: Boolean): Boolean {
        if (ip.isNullOrBlank() || !IP_LITERAL.matches(ip)) return false
        val address = try {
            InetAddress.getByName(ip)
        } catch (e: Exception) {
            return false
        }
        return address.isSiteLocalAddress ||
                address.isLinkLocalAddress ||
                (allowLoopback && address.isLoopbackAddress)
    }
}