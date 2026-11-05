package com.example.tvdashboard.remote

import com.example.tvdashboard.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.json.JSONObject
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val message: String, val retryAfterSeconds: Int? = null) : ApiResult<Nothing>
}
object RemoteSettingsClient {
    @Volatile private var lastDashboardUrl = ""
    private fun query(value: String) = URLEncoder.encode(value, "UTF-8")
    suspend fun departments(server: String, key: String): ApiResult<List<Department>> {
        val normalized = try { RemotePolicy.serverUrl(server, BuildConfig.ALLOW_CLEARTEXT_HTTP) } catch (e: IllegalArgumentException) { return ApiResult.Failure(e.message.orEmpty()) }
        if (key.length !in 1..256 || key.any { it.code !in 33..126 }) return ApiResult.Failure("API key không hợp lệ.")
        return when (val result = request(normalized + "/api/v1/departments", key)) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> try {
                val items = result.value.getJSONArray("departments")
                require(items.length() <= 1000)
                val seen = HashSet<String>()
                val departments = (0 until items.length()).map { index ->
                    val row = items.getJSONObject(index)
                    val id = row.getString("id"); val name = row.getString("name")
                    require(Regex("[a-z0-9-]{1,32}").matches(id) && seen.add(id) && name.length in 1..128)
                    Department(id, name)
                }
                ApiResult.Success(departments)
            } catch (_: Exception) { ApiResult.Failure("Danh sách phòng ban từ API không hợp lệ.") }
        }
    }
    suspend fun settings(setup: RemoteSetup): ApiResult<RemoteSettings> {
        val valid = try { RemotePolicy.validate(setup, BuildConfig.ALLOW_CLEARTEXT_HTTP) } catch (e: IllegalArgumentException) { return ApiResult.Failure(e.message.orEmpty()) }
        val deviceId = try { RemotePolicy.requireDeviceId(valid.deviceId) } catch (e: IllegalArgumentException) { return ApiResult.Failure(e.message.orEmpty()) }
        val url = valid.serverUrl + "/api/v1/settings?department=" + query(valid.department) + "&deviceId=" + query(deviceId) + "&name=" + query(valid.name) + "&appVersion=" + query(BuildConfig.VERSION_NAME) + "&loadState=" + query(DashboardLoadStatus.forUrl(lastDashboardUrl)) + "&loadUrlHash=" + query(java.security.MessageDigest.getInstance("SHA-256").digest(lastDashboardUrl.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) })
        return when (val result = request(url, valid.apiKey)) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> try {
                val body = result.value
                val dep = body.getJSONObject("department")
                require(dep.getString("id") == valid.department)
                val name = dep.getString("name"); require(name.length in 1..128)
                val rawVersion = body.get("version")
                require(rawVersion is Int || rawVersion is Long)
                val version = (rawVersion as Number).toLong(); require(version > 0)
                val rawPoll = if (body.has("pollSeconds")) body.get("pollSeconds") else RemotePolicy.DEFAULT_POLL_SECONDS
                require(rawPoll is Int || rawPoll is Long)
                val poll = (rawPoll as Number).toLong(); require(poll in 1..Int.MAX_VALUE.toLong())
                val dashboard = RemotePolicy.dashboardUrl(body.getString("dashboardUrl"), BuildConfig.ALLOW_CLEARTEXT_HTTP)
                lastDashboardUrl = dashboard
                // Device policy is fetched and validated independently; bad policy must not block a valid URL.
                ApiResult.Success(RemoteSettings(Department(valid.department, name), dashboard,
                    version, poll.toInt().coerceIn(60, 3600), databaseEpoch = body.optString("databaseEpoch")))
            } catch (_: Exception) { ApiResult.Failure("API trả cấu hình không hợp lệ hoặc sai phòng ban; không áp dụng URL mới.") }
        }
    }
    suspend fun devicePolicy(setup: RemoteSetup): ApiResult<JSONObject> {
        val valid = try {
            RemotePolicy.validate(setup, BuildConfig.ALLOW_CLEARTEXT_HTTP).also { RemotePolicy.requireDeviceId(it.deviceId) }
        } catch (e: IllegalArgumentException) { return ApiResult.Failure(e.message.orEmpty()) }
        return request(valid.serverUrl + "/api/v1/devices/" + valid.deviceId + "/policy", valid.apiKey)
    }
    suspend fun acknowledgePhonePinPolicy(setup: RemoteSetup, version: Long) = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val id = RemotePolicy.requireDeviceId(setup.deviceId)
            val body = JSONObject().put("version", version).toString().toByteArray(Charsets.UTF_8)
            val c = (URL(setup.serverUrl + "/api/v1/devices/" + id + "/phone-pin/ack").openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000; readTimeout = 3000; requestMethod = "POST"
                instanceFollowRedirects = false; doOutput = true
                setRequestProperty("x-api-key", setup.apiKey)
                setRequestProperty("Content-Type", "application/json")
            }
            connection = c
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val status = c.responseCode
            if (status != 200) throw IOException("HTTP $status")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("RemoteSync", "PIN policy acknowledgement failed; category=${e.javaClass.simpleName}")
            false
        } finally {
            connection?.disconnect()
        }
    }
    private suspend fun request(url: String, key: String): ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = if (url.toString().startsWith(BuildConfig.CLOUD_API_URL + "/")) 45000 else 8000; requestMethod = "GET"
                instanceFollowRedirects = false; useCaches = false
                setRequestProperty("x-api-key", key); setRequestProperty("Accept", "application/json")
            }
            val code = connection.responseCode
            if (code != 200) {
                val message = when (code) {
                    401 -> "401: API key sai hoặc đã bị thu hồi."
                    403 -> "403: API key không có quyền với phòng ban này."
                    404 -> "404: Phòng ban hoặc endpoint không tồn tại."
                    409 -> "409: Phòng ban chưa có cấu hình URL hợp lệ."
                    429 -> "429: Gọi quá nhiều; sẽ thử lại sau."
                    in 300..399 -> "API chuyển hướng. Nhập địa chỉ server cuối cùng; app không chuyển tiếp API key."
                    in 500..599 -> "Server cấu hình đang lỗi ($code)."
                    else -> "API trả HTTP $code. Kiểm tra địa chỉ server và thiết lập TV."
                }
                ApiResult.Failure(message, if (code == 429) connection.getHeaderField("Retry-After")?.toIntOrNull()?.coerceIn(1, 3600) else null)
            } else {
                val output = ByteArrayOutputStream()
                connection.inputStream.use { stream ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val read = stream.read(buffer); if (read < 0) break
                        require(output.size() + read <= 65536) { "Oversized response" }
                        output.write(buffer, 0, read)
                    }
                }
                ApiResult.Success(JSONObject(output.toString("UTF-8")))
            }
        } catch (e: CancellationException) { throw e }
        catch (_: UnknownHostException) { ApiResult.Failure("Không tìm được server (DNS).") }
        catch (_: SocketTimeoutException) { ApiResult.Failure("Hết thời gian chờ API. Kiểm tra IP/mạng/server.") }
        catch (_: SSLException) { ApiResult.Failure("Chứng chỉ HTTPS không hợp lệ hoặc TV chưa tin chứng chỉ.") }
        catch (_: ConnectException) { ApiResult.Failure("Không kết nối được API. Kiểm tra server đang chạy và cổng 8765.") }
        catch (_: IOException) { ApiResult.Failure("Không gọi được API. Kiểm tra mạng và HTTP/HTTPS của bản app.") }
        catch (_: Exception) { ApiResult.Failure("Phản hồi API không phải JSON hợp lệ hoặc quá lớn.") }
        finally { connection?.disconnect() }
    }
}
