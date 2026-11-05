package com.example.tvdashboard.remote

import android.content.Context
import com.example.tvdashboard.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import org.json.JSONObject

/** Called on NanoHTTPD's worker. The browser never receives the device key. */
object CloudEnrollment {
    @Synchronized
    fun claim(context: Context, current: RemoteSetup, input: String): RemoteSetup {
        val code = input.trim().uppercase(java.util.Locale.ROOT)
        require(Regex("[A-F0-9]{12}").matches(code)) { "Nhập đúng mã ghép 12 ký tự từ admin." }
        val server = RemotePolicy.serverUrl(BuildConfig.CLOUD_API_URL, false)
        val device = RemotePolicy.requireDeviceId(current.deviceId)
        val store = context.getSharedPreferences("cloud_enrollment", Context.MODE_PRIVATE)
        val slot = "$server/$device"
        val key = store.getString(slot, null) ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }.also {
                require(store.edit().putString(slot, it).commit()) { "Không lưu được khóa trên TV." }
            }
        val conn = URL("$server/api/v1/cloud/enroll").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"; conn.instanceFollowRedirects = false
            conn.connectTimeout = 10000; conn.readTimeout = 45000; conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (current.serverUrl == server && current.apiKey.isNotEmpty()) conn.setRequestProperty("x-api-key", current.apiKey)
            val body = JSONObject().put("deviceId", device).put("code", code).put("deviceKey", key).toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            require(status == 200) { when(status) {
                403 -> "Mã ghép không còn hiệu lực hoặc TV đã thuộc khóa khác. Kiểm tra với admin."
                429 -> "Quá nhiều yêu cầu. Đợi một phút rồi thử lại."
                else -> "Cloud chưa thể ghép TV (HTTP $status). Kiểm tra backend đã cập nhật."
            } }
            val data = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val out=StringBuilder(); var c=reader.read()
                while(c >= 0) { require(out.length < 4096); out.append(c.toChar()); c=reader.read() }; out.toString()
            })
            require(data.getString("deviceId") == device)
            return RemotePolicy.validate(current.copy(enabled=true, serverUrl=server, apiKey=key,
                department=data.getString("department"), name=current.name.ifBlank { "TV-"+device.take(8) }), false)
        } catch (e: java.io.IOException) {
            throw IllegalArgumentException("Chưa kết nối được Render. Kiểm tra Internet rồi thử lại cùng mã.", e)
        } finally { conn.disconnect() }
    }
}
