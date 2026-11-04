package com.example.tvdashboard.net

import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Kiểm tra nhanh xem URL có truy cập được không (dùng ở màn hình Cài đặt). */
object ConnectionTester {

    private const val TIMEOUT_MS = 8_000

    enum class Kind { DNS, TIMEOUT, SSL, REFUSED, OTHER }

    sealed interface Result {
        /** Máy chủ có phản hồi, kèm mã HTTP. */
        data class Reachable(val httpCode: Int) : Result

        data class Failed(val kind: Kind, val detail: String? = null) : Result
    }

    suspend fun test(url: String): Result = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            Result.Reachable(connection.responseCode)
        } catch (e: UnknownHostException) {
            Result.Failed(Kind.DNS)
        } catch (e: SocketTimeoutException) {
            Result.Failed(Kind.TIMEOUT)
        } catch (e: SSLException) {
            Result.Failed(Kind.SSL)
        } catch (e: ConnectException) {
            Result.Failed(Kind.REFUSED)
        } catch (e: IOException) {
            Result.Failed(Kind.OTHER, e.message)
        } finally {
            connection?.disconnect()
        }
    }
}
