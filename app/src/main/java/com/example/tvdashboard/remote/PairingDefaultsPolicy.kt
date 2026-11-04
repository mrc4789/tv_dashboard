package com.example.tvdashboard.remote

/** Saved connection credentials can be reused before a department has been selected. */
object PairingDefaultsPolicy {
    fun connection(current: RemoteSetup, allowHttp: Boolean): RemoteSetup? = try {
        val server = RemotePolicy.serverUrl(current.serverUrl, allowHttp)
        require(current.apiKey.length in 1..256 && current.apiKey.all { it.code in 33..126 })
        val cleanName = current.name.trim().takeIf {
            it.length in 1..64 && it.none { ch -> ch.code < 32 || ch.code in 127..159 }
        } ?: "TV-" + current.deviceId.take(8).ifBlank { "Setup" }
        current.copy(
            enabled = true, serverUrl = server, name = cleanName,
            department = current.department.takeIf { Regex("[a-z0-9-]{1,32}").matches(it) }.orEmpty()
        )
    } catch (_: IllegalArgumentException) { null }

    fun canPair(value: RemoteSetup?, allowHttp: Boolean): Boolean = value != null && try {
        RemotePolicy.validate(value, allowHttp)
        true
    } catch (_: IllegalArgumentException) { false }
}
