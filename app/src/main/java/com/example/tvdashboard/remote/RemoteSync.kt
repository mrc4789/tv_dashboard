package com.example.tvdashboard.remote

import com.example.tvdashboard.data.PrefsStore
import android.util.Log
import com.example.tvdashboard.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object RemoteSync {
    // MainActivity, SettingsActivity and QR cannot make overlapping poll requests.
    private val mutex = Mutex()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun configure(prefs: PrefsStore, value: RemoteSetup, minutes: Int?): ApiResult<RemoteSettings> = mutex.withLock {
        val current = prefs.getRemoteSetup()
        val candidate = try {
            RemotePolicy.validate(value.copy(deviceId = current.deviceId), BuildConfig.ALLOW_CLEARTEXT_HTTP)
        } catch (e: IllegalArgumentException) {
            return@withLock ApiResult.Failure(e.message.orEmpty())
        }
        val departmentMove = current.deviceId.isNotEmpty() && current.apiKey.isNotEmpty() &&
            current.serverUrl == candidate.serverUrl && current.department != candidate.department
        if (departmentMove) {
            when (val result = RemoteSettingsClient.settings(candidate)) {
                is ApiResult.Failure -> return@withLock result
                is ApiResult.Success -> {
                    if (!prefs.saveRemoteSetupIfCurrent(current, candidate, minutes)) {
                        return@withLock ApiResult.Failure("Thiết lập đã đổi trong lúc xác thực phòng ban; giữ nguyên cấu hình hiện tại.")
                    }
                    RemoteDiagnostics.onSetupChanged(candidate)
                    return@withLock applySettings(prefs, candidate, result.value)
                }
            }
        }
        prefs.saveRemoteSetup(candidate, minutes)
        RemoteDiagnostics.onSetupChanged(prefs.getRemoteSetup())
        syncExpected(prefs, prefs.getRemoteSetup())
    }

    suspend fun now(prefs: PrefsStore): ApiResult<RemoteSettings> = mutex.withLock {
        val expected = prefs.refreshPairedServerAddress()
        if (!expected.enabled) {
            Log.i("RemoteSync", "Chế độ URL cục bộ: không gọi API cấu hình")
            return@withLock ApiResult.Failure("Đang ở chế độ URL cục bộ.")
        }
        syncExpected(prefs, expected)
    }

    private suspend fun syncExpected(prefs: PrefsStore, expected: RemoteSetup): ApiResult<RemoteSettings> {
        if (!expected.enabled) return ApiResult.Failure("Đang ở chế độ URL cục bộ.")
        Log.i("RemoteSync", "Lấy cấu hình phòng ban ${expected.department}")
        return when (val result = RemoteSettingsClient.settings(expected)) {
            is ApiResult.Success -> applySettings(prefs, expected, result.value)
            is ApiResult.Failure -> {
                Log.w("RemoteSync", result.message)
                prefs.saveRemoteFailure(expected, result.message)
                result
            }
        }
    }

    private suspend fun applySettings(
        prefs: PrefsStore,
        expected: RemoteSetup,
        value: RemoteSettings
    ): ApiResult<RemoteSettings> {
        if (!prefs.cacheSettings(expected, value)) {
            return ApiResult.Failure("Thiết lập đã đổi hoặc phản hồi cũ; không áp dụng cấu hình.")
        }
        background.launch { DevicePolicySync.now(prefs) }
        Log.i("RemoteSync", "Đã áp dụng cấu hình ${expected.department}, v${value.version}")
        return ApiResult.Success(value)
    }

    suspend fun poll(prefs: PrefsStore) {
        var failureDelaySeconds = 2

        while (true) {
            val result = now(prefs)

            val waitSeconds = when (result) {
                is ApiResult.Success -> {
                    failureDelaySeconds = 2
                    prefs.pollSeconds()
                }

                is ApiResult.Failure -> {
                    val wait = result.retryAfterSeconds
                        ?.coerceIn(1, 3600)
                        ?: failureDelaySeconds

                    failureDelaySeconds =
                        (failureDelaySeconds * 2).coerceAtMost(30)

                    wait
                }
            }

            delay(waitSeconds.toLong() * 1000L + kotlin.random.Random.nextLong(0L, 1000L))
        }
    }
}
