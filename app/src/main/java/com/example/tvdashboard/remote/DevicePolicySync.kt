package com.example.tvdashboard.remote

import android.util.Log
import com.example.tvdashboard.data.PrefsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One policy request at a time; no dashboard URL read/validation or WebView reload. */
object DevicePolicySync {
    private val mutex = Mutex()
    private var acknowledgedSetup: RemoteSetup? = null
    private var acknowledgedVersion = -1L
    private var lastSuccessSetup: RemoteSetup? = null
    private var lastSuccessAt = 0L
    private var lastDiscoveryAt = -30_000L

    fun canSync(setup: RemoteSetup): Boolean = setup.serverUrl.isNotBlank() &&
        setup.apiKey.isNotBlank() && setup.deviceId.isNotBlank() && setup.department.isNotBlank()

    suspend fun now(prefs: PrefsStore, force: Boolean = false): Boolean = mutex.withLock {
        val expected = prefs.getRemoteSetup()
        if (!canSync(expected)) return@withLock false
        val elapsed = android.os.SystemClock.elapsedRealtime()
        if (!force && expected == lastSuccessSetup && elapsed - lastSuccessAt < 2000) return@withLock true
        when (val response = RemoteSettingsClient.devicePolicy(expected)) {
            is ApiResult.Failure -> {
                Log.w("DevicePolicySync", response.message)
                prefs.savePolicyStatus(expected, "Chưa nhận PIN/log từ admin: " + response.message)
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastDiscoveryAt >= 30_000L) {
                    lastDiscoveryAt = now
                    try {
                        val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            LanAutoPairing.connect(expected)
                        }.copy(enabled = expected.enabled)
                        // Discovery verifies possession of the existing key; never replace credentials.
                        if (found.apiKey == expected.apiKey && found.serverUrl != expected.serverUrl) {
                            prefs.saveRemoteSetupIfCurrent(expected, found)
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Keep saved server/key and surface the original API error. */ }
                }
                false
            }
            is ApiResult.Success -> try {
                val body = response.value
                require(body.getString("deviceId") == expected.deviceId)
                val raw = PhonePinPolicy.validate(body.getJSONObject("phonePinPolicy").toString())
                val pinVersion = body.get("phonePinPolicyVersion")
                val logVersion = body.get("diagnosticsPolicyVersion")
                require((pinVersion is Int || pinVersion is Long) && (logVersion is Int || logVersion is Long))
                val pv = (pinVersion as Number).toLong()
                val lv = (logVersion as Number).toLong()
                require(pv >= 0 && lv >= 0)
                val enabled = body.get("diagnosticsEnabled") as? Boolean
                    ?: throw IllegalArgumentException("Invalid policy")
                if (!prefs.cacheDevicePolicy(expected, raw, pv)) return@withLock false
                if (prefs.getRemoteSetup() != expected) return@withLock false
                RemoteDiagnostics.configure(expected, enabled, lv)
                if (acknowledgedSetup != expected || acknowledgedVersion != pv) {
                    if (RemoteSettingsClient.acknowledgePhonePinPolicy(expected, pv)) {
                        acknowledgedSetup = expected
                        acknowledgedVersion = pv
                    }
                }
                prefs.savePolicyStatus(expected, "Đã nhận chính sách thiết bị: PIN " +
                    (if (PhonePinPolicy.managed(raw)) "admin" else "ngẫu nhiên") + "; v$pv; gửi log=" + enabled)
                lastSuccessSetup = expected
                lastSuccessAt = android.os.SystemClock.elapsedRealtime()
                Log.i("DevicePolicySync", "Đã lưu chính sách PIN v$pv; mode=" + if (PhonePinPolicy.managed(raw)) "admin" else "random")
                true
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                prefs.savePolicyStatus(expected, "Chính sách PIN/log không hợp lệ; giữ PIN đã lưu.")
                Log.w("DevicePolicySync", "Chính sách thiết bị không hợp lệ; giữ PIN đã lưu")
                false
            }
        }
    }

    suspend fun poll(prefs: PrefsStore) {
        var retry = 30L
        while (true) {
            val ok = now(prefs)
            if (ok) retry = 30L
            delay(if (ok) 300_000L else retry * 1000)
            if (!ok) retry = (retry * 2).coerceAtMost(300)
        }
    }
}
