package com.example.tvdashboard.remote

import com.example.tvdashboard.data.UrlValidator

sealed interface PhoneConfiguration {
    data class Local(val url: String, val reloadMinutes: Int?) : PhoneConfiguration
    data class Remote(val setup: RemoteSetup, val reloadMinutes: Int?) : PhoneConfiguration
}
object PhoneConfigurationParser {
    fun parse(fields: Map<String, String>, allowHttp: Boolean): PhoneConfiguration {
        val rawMinutes = fields["reloadMinutes"].orEmpty().trim()
        val minutes = if (rawMinutes.isEmpty()) null else rawMinutes.toIntOrNull().also { require(it != null && it in 0..10080) { "Chu kỳ reload cần 0–10080 phút." } }
        return when (fields["mode"] ?: "local") {
            "local" -> {
                val url = UrlValidator.validate(fields["url"])
                require(url is UrlValidator.Result.Valid) { "URL cục bộ không hợp lệ." }
                PhoneConfiguration.Local(url.url, minutes)
            }
            "remote" -> PhoneConfiguration.Remote(RemotePolicy.validate(RemoteSetup(true, fields["server"].orEmpty(), fields["key"].orEmpty(), fields["department"].orEmpty(), fields["name"].orEmpty()), allowHttp), minutes)
            else -> throw IllegalArgumentException("Chế độ cấu hình không hợp lệ.")
        }
    }
}
