package com.example.tvdashboard.remote

import com.example.tvdashboard.data.UrlValidator
import java.net.URI
import java.util.UUID
import java.util.Locale

/** Pure validation/cache policy; no Android or network dependency. */
data class RemoteSetup(
    val enabled: Boolean = false,
    val serverUrl: String = "",
    val apiKey: String = "",
    val department: String = "",
    val name: String = "",
    val deviceId: String = ""
) {
    override fun toString(): String = "RemoteSetup(enabled=$enabled, credentials=redacted)"
}
data class Department(val id: String, val name: String)
data class RemoteSettings(
    val department: Department,
    val dashboardUrl: String,
    val version: Long,
    val pollSeconds: Int = 300,
    val phonePinPolicy: String? = null,
    val phonePinPolicyVersion: Long = 0,
    val diagnosticsEnabled: Boolean = false,
    val diagnosticsPolicyVersion: Long = 0,
    val databaseEpoch: String = ""
)

object RemotePolicy {
    const val DEFAULT_POLL_SECONDS = 300
    fun serverUrl(raw: String, allowHttp: Boolean): String {
        val input = raw.trim()
        require(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(input)) {
            "Nhập đầy đủ địa chỉ API, ví dụ http://192.168.50.113:8765 hoặc https://api.example.com."
        }
        val colon = input.indexOf(':')
        val text = input.substring(0, colon).lowercase(Locale.ROOT) + input.substring(colon)
        require(text.length <= 2048) { "Địa chỉ server quá dài." }
        val uri = try { URI(text) } catch (_: Exception) { throw IllegalArgumentException("Địa chỉ server không hợp lệ.") }
        require(uri.scheme == "http" || uri.scheme == "https") { "Địa chỉ API chỉ hỗ trợ http:// hoặc https://." }
        require(allowHttp || uri.scheme == "https") { "Bản APK này chưa cho phép API HTTP. Dùng bản release LAN hoặc server HTTPS hợp lệ." }
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.orEmpty() in listOf("", "/")) { "Nhập địa chỉ gốc của server, không thêm /admin hoặc /api/v1 và không chứa tài khoản." }
        require(uri.port == -1 || uri.port in 1..65535) { "Cổng server không hợp lệ." }
        return text.removeSuffix("/")
    }
    fun validate(setup: RemoteSetup, allowHttp: Boolean): RemoteSetup {
        val url = serverUrl(setup.serverUrl, allowHttp)
        require(setup.apiKey.length in 1..256 && setup.apiKey.all { it.code in 33..126 }) { "API key không hợp lệ; dùng tvKey, không dùng adminToken." }
        require(Regex("[a-z0-9-]{1,32}").matches(setup.department)) { "Chưa chọn phòng ban hoặc mã phòng ban không hợp lệ." }
        val name = setup.name.trim()
        require(name.length in 1..64 && name.none { it.code < 32 || it.code in 127..159 }) { "Tên TV cần 1–64 ký tự." }
        return setup.copy(serverUrl = url, name = name)
    }
    fun dashboardUrl(raw: String, allowHttp: Boolean): String {
        val validated = UrlValidator.validate(raw)
        require(validated is UrlValidator.Result.Valid && raw.length <= 2048) { "API trả URL dashboard không hợp lệ." }
        val uri = URI(validated.url)
        require(uri.rawUserInfo == null && (uri.port == -1 || uri.port in 1..65535) && (allowHttp || uri.scheme == "https")) { "URL dashboard không phù hợp chính sách HTTP/HTTPS của bản APK hoặc có chứa tài khoản." }
        return validated.url
    }
    fun deviceId(existing: String?): String {
        return existing?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
    }
    fun requireDeviceId(value: String): String {
        val parsed = try { UUID.fromString(value) } catch (_: IllegalArgumentException) { null }
        require(parsed != null && parsed.version() == 4 && parsed.toString() == value) {
            "Device ID đã lưu không hợp lệ; không tạo ID mới để tránh đăng ký TV trùng. Cần hỗ trợ khôi phục cấu hình thiết bị."
        }
        return value
    }
    fun canApplyResponse(expected: RemoteSetup, current: RemoteSetup): Boolean = expected.enabled && expected == current
    fun sameSource(a: RemoteSetup, b: RemoteSetup): Boolean = a.serverUrl == b.serverUrl && a.department == b.department
    fun effectiveUrl(enabled: Boolean, cacheMatches: Boolean, cached: String?, local: String?): String? =
        if (enabled && cacheMatches && !cached.isNullOrBlank()) cached else local
    fun nextDelaySeconds(pollSeconds: Int, retryAfterSeconds: Int?): Int =
        maxOf(pollSeconds.coerceIn(60, 3600), (retryAfterSeconds ?: 0).coerceIn(0, 3600))
}
