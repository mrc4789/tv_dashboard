package com.example.tvdashboard.remote

import android.content.Context
import com.example.tvdashboard.BuildConfig
import org.json.JSONObject

/** Debug bridge defaults stay in app-private storage; they are never sent to the browser. */
object PhoneSetupDefaults {
    fun fromBridge(context: Context, current: RemoteSetup): RemoteSetup? {
        if (!BuildConfig.DEBUG) return null
        return try {
            val file = java.io.File(context.filesDir, "phone-setup.json")
            if (!file.isFile || file.length() !in 1L..8192L) return null
            val value = JSONObject(file.readText(Charsets.UTF_8))
            val department = current.department.ifBlank { value.getString("department") }
            val name = current.name.ifBlank { "TV-" + current.deviceId.take(8) }
            RemotePolicy.validate(
                current.copy(
                    enabled = true,
                    serverUrl = value.getString("serverUrl"),
                    apiKey = value.getString("tvKey"),
                    department = department,
                    name = name
                ), BuildConfig.ALLOW_CLEARTEXT_HTTP
            )
        } catch (_: Exception) {
            null
        }
    }

    fun resolve(context: Context, current: RemoteSetup): RemoteSetup? =
        PairingDefaultsPolicy.connection(
            fromBridge(context, current) ?: current,
            BuildConfig.ALLOW_CLEARTEXT_HTTP
        )
}
