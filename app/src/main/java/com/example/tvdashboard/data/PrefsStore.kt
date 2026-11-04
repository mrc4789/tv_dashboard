package com.example.tvdashboard.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.tvdashboard.BuildConfig
import com.example.tvdashboard.remote.RemoteSetup
import com.example.tvdashboard.remote.RemoteSettings
import com.example.tvdashboard.remote.RemotePolicy
import com.example.tvdashboard.remote.PhoneSetupDefaults
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class PrefsStore(context: Context) {
    private val appContext = context.applicationContext
    private val dataStore = appContext.settingsDataStore
    private val prefsFlow: Flow<Preferences> = dataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }
    private fun setup(p: Preferences) = RemoteSetup(p[ENABLED] ?: false, p[SERVER].orEmpty(), p[KEY].orEmpty(), p[DEPARTMENT].orEmpty(), p[NAME].orEmpty(), p[DEVICE].orEmpty())
    val remoteSetupFlow = prefsFlow.map { setup(it) }.distinctUntilChanged()
    val configuredUrlFlow: Flow<String?> = prefsFlow.map { p ->
        val local = p[KEY_URL] ?: BuildConfig.DEFAULT_URL.takeUnless { it == PLACEHOLDER_URL }
        RemotePolicy.effectiveUrl(p[ENABLED] ?: false, p[CACHE_SERVER] == p[SERVER] && p[CACHE_DEPARTMENT] == p[DEPARTMENT], p[CACHE_URL], local)
    }.distinctUntilChanged()
    val reloadIntervalMinutesFlow = prefsFlow.map { it[KEY_RELOAD_MINUTES] ?: DEFAULT_RELOAD_MINUTES }.distinctUntilChanged()
    val eventStatusFlow = prefsFlow.map { it[EVENT_STATUS] ?: "Cập nhật nhanh: chưa kết nối." }.distinctUntilChanged()
    val remoteStatusFlow = prefsFlow.map { it[STATUS] ?: "Chưa đồng bộ với server." }.distinctUntilChanged()
    suspend fun getConfiguredUrl(): String? = configuredUrlFlow.first()
    suspend fun refreshPairedServerAddress(): RemoteSetup {
        val current = getRemoteSetup()
        val provisioned = PhoneSetupDefaults.fromBridge(appContext, current)
        // Change only the address of the same, already-paired service. A local
        // configuration, rotated key or user edit must never be overwritten.
        if (current.enabled && provisioned != null &&
            current.apiKey == provisioned.apiKey && current.serverUrl != provisioned.serverUrl) {
            dataStore.edit { p ->
                if (RemotePolicy.canApplyResponse(current, setup(p))) {
                    p[SERVER] = provisioned.serverUrl
                    p.remove(CACHE_URL); p.remove(CACHE_SERVER); p.remove(CACHE_DEPARTMENT)
                    p.remove(VERSION); p.remove(SYNC_AT); p.remove(POLL)
                    p[STATUS] = "Đã cập nhật địa chỉ API từ bridge; đang lấy cấu hình."
                }
            }
        }
        return getRemoteSetup()
    }
    suspend fun getLocalUrl(): String? = prefsFlow.first()[KEY_URL]
    suspend fun getReloadIntervalMinutes(): Int = reloadIntervalMinutesFlow.first()
    suspend fun getRemoteSetup(): RemoteSetup {
        ensureDeviceId()
        return remoteSetupFlow.first()
    }
    suspend fun ensureDeviceId(): String {
        var id = ""
        dataStore.edit { p ->
            id = RemotePolicy.deviceId(p[DEVICE])
            if (id != p[DEVICE]) p[DEVICE] = id
        }
        return id
    }
    suspend fun saveFallbackUrl(url: String) { dataStore.edit { if (url.isEmpty()) it.remove(KEY_URL) else it[KEY_URL] = url } }
    suspend fun saveUrl(url: String) { dataStore.edit { it[KEY_URL] = url } }
    suspend fun saveLocalMode(url: String, minutes: Int) {
        dataStore.edit { it[KEY_URL] = url; it[ENABLED] = false; it[KEY_RELOAD_MINUTES] = minutes.coerceIn(0, MAX_RELOAD_MINUTES) }
    }
    suspend fun saveReloadIntervalMinutes(minutes: Int) { dataStore.edit { it[KEY_RELOAD_MINUTES] = minutes.coerceIn(0, MAX_RELOAD_MINUTES) } }
    /** Persist PIN-authorized connection discovery without requiring a department yet. */
    suspend fun savePairingConnection(value: RemoteSetup) {
        val normalized = com.example.tvdashboard.remote.PairingDefaultsPolicy.connection(value, BuildConfig.ALLOW_CLEARTEXT_HTTP)
            ?: throw IllegalArgumentException("Cấu hình ghép API không hợp lệ.")
        ensureDeviceId()
        dataStore.edit { p ->
            if (!RemotePolicy.sameSource(setup(p), normalized)) {
                p.remove(CACHE_URL); p.remove(CACHE_SERVER); p.remove(CACHE_DEPARTMENT)
                p.remove(VERSION); p.remove(SYNC_AT); p.remove(POLL)
            }
            p[SERVER] = normalized.serverUrl; p[KEY] = normalized.apiKey
            p[DEPARTMENT] = normalized.department; p[NAME] = normalized.name
            // Mode is preserved until the phone submits /pair or /save.
        }
    }

    suspend fun saveRemoteSetup(value: RemoteSetup, minutes: Int? = null) {
        val normalized = RemotePolicy.validate(value.copy(deviceId = ensureDeviceId()), BuildConfig.ALLOW_CLEARTEXT_HTTP)
        dataStore.edit { p ->
            val previous = setup(p)
            if (!RemotePolicy.sameSource(previous, normalized)) {
                p.remove(CACHE_URL); p.remove(CACHE_SERVER); p.remove(CACHE_DEPARTMENT); p.remove(VERSION); p.remove(SYNC_AT); p.remove(POLL)
            }
            p[ENABLED] = normalized.enabled; p[SERVER] = normalized.serverUrl; p[KEY] = normalized.apiKey
            p[DEPARTMENT] = normalized.department; p[NAME] = normalized.name
            if (previous != normalized.copy(deviceId = previous.deviceId)) p[STATUS] = "Đã lưu thiết lập; đang chờ đồng bộ."
            if (minutes != null) p[KEY_RELOAD_MINUTES] = minutes.coerceIn(0, MAX_RELOAD_MINUTES)
        }
    }
    suspend fun saveRemoteSetupIfCurrent(expected: RemoteSetup, value: RemoteSetup, minutes: Int? = null): Boolean {
        val normalized = RemotePolicy.validate(value.copy(deviceId = expected.deviceId), BuildConfig.ALLOW_CLEARTEXT_HTTP)
        var saved = false
        dataStore.edit { p ->
            if (setup(p) == expected) {
                val previous = setup(p)
                if (!RemotePolicy.sameSource(previous, normalized)) {
                    p.remove(CACHE_URL); p.remove(CACHE_SERVER); p.remove(CACHE_DEPARTMENT)
                    p.remove(VERSION); p.remove(SYNC_AT); p.remove(POLL)
                }
                p[ENABLED] = normalized.enabled; p[SERVER] = normalized.serverUrl; p[KEY] = normalized.apiKey
                p[DEPARTMENT] = normalized.department; p[NAME] = normalized.name
                if (previous != normalized.copy(deviceId = previous.deviceId)) p[STATUS] = "Đã lưu thiết lập; đang chờ đồng bộ."
                if (minutes != null) p[KEY_RELOAD_MINUTES] = minutes.coerceIn(0, MAX_RELOAD_MINUTES)
                saved = true
            }
        }
        return saved
    }
    val phonePinPolicyFlow = prefsFlow.map { it[PHONE_PIN_POLICY] ?: "{\"mode\":\"random\"}" }.distinctUntilChanged()
    suspend fun phonePinPolicy(): String = prefsFlow.first()[PHONE_PIN_POLICY] ?: "{\"mode\":\"random\"}"
    suspend fun cacheSettings(expected: RemoteSetup, value: RemoteSettings): Boolean {
        var applied = false
        dataStore.edit { p ->
            if (RemotePolicy.canApplyResponse(expected, setup(p))) {
                val sameCache = p[CACHE_SERVER] == expected.serverUrl && p[CACHE_DEPARTMENT] == expected.department
                if (sameCache && value.version < (p[VERSION] ?: 0L)) return@edit
                p[CACHE_URL] = value.dashboardUrl; p[CACHE_SERVER] = expected.serverUrl; p[CACHE_DEPARTMENT] = expected.department
                p[VERSION] = value.version; p[POLL] = value.pollSeconds; p[SYNC_AT] = System.currentTimeMillis()
                p[STATUS] = "Đồng bộ thành công: ${value.department.name}, v${value.version}. Lúc " + java.time.Instant.ofEpochMilli(p[SYNC_AT]!!).toString()
                applied = true
            }
        }
        return applied
    }
    // Device policy is independent of dashboard URL, department and local/remote display mode.
    suspend fun cacheDevicePolicy(expected: RemoteSetup, raw: String, version: Long): Boolean {
        com.example.tvdashboard.remote.PhonePinPolicy.validate(raw)
        var applied = false
        dataStore.edit { p ->
            if (setup(p) != expected) return@edit
            val source = expected.serverUrl + "|" + expected.deviceId
            if (p[PIN_POLICY_SOURCE] == source && version < (p[PIN_POLICY_VERSION] ?: 0L)) return@edit
            p[PHONE_PIN_POLICY] = raw
            p[PIN_POLICY_VERSION] = version
            p[PIN_POLICY_SOURCE] = source
            applied = true
        }
        return applied
    }
    suspend fun savePolicyStatus(expected: RemoteSetup, message: String) {
        dataStore.edit { p -> if (setup(p) == expected) p[STATUS] = message }
    }
    suspend fun saveRemoteFailure(expected: RemoteSetup, message: String) {
        dataStore.edit { p ->
            if (RemotePolicy.canApplyResponse(expected, setup(p))) p[STATUS] = message + if (p[CACHE_URL] != null) " Giữ cấu hình cuối đã nhận." else " Chưa có cache; dùng URL cục bộ nếu đã đặt."
        }
    }
    suspend fun saveEventStatus(expected: RemoteSetup, message: String) {
        dataStore.edit { p -> if (RemotePolicy.canApplyResponse(expected,setup(p))) p[EVENT_STATUS]=message }
    }
    suspend fun pollSeconds(): Int = (prefsFlow.first()[POLL] ?: RemotePolicy.DEFAULT_POLL_SECONDS).coerceIn(60, 3600)
    companion object {
        private val PHONE_PIN_POLICY = stringPreferencesKey("phone_pin_policy")
        private val PIN_POLICY_VERSION = longPreferencesKey("phone_pin_policy_version")
        private val PIN_POLICY_SOURCE = stringPreferencesKey("phone_pin_policy_source")
        const val PLACEHOLDER_URL = "https://example.com"
        const val DEFAULT_RELOAD_MINUTES = 360
        const val MAX_RELOAD_MINUTES = 10_080
        private val KEY_URL = stringPreferencesKey("dashboard_url")
        private val KEY_RELOAD_MINUTES = intPreferencesKey("reload_interval_minutes")
        private val ENABLED = booleanPreferencesKey("remote_enabled")
        private val SERVER = stringPreferencesKey("remote_server")
        private val KEY = stringPreferencesKey("remote_read_key")
        private val DEPARTMENT = stringPreferencesKey("remote_department")
        private val NAME = stringPreferencesKey("remote_tv_name")
        private val DEVICE = stringPreferencesKey("remote_device_id")
        private val CACHE_URL = stringPreferencesKey("remote_cached_url")
        private val CACHE_SERVER = stringPreferencesKey("remote_cache_server")
        private val CACHE_DEPARTMENT = stringPreferencesKey("remote_cache_department")
        private val VERSION = longPreferencesKey("remote_version")
        private val SYNC_AT = longPreferencesKey("remote_synced_at")
        private val POLL = intPreferencesKey("remote_poll_seconds")
        private val EVENT_STATUS = stringPreferencesKey("remote_event_status")
        private val STATUS = stringPreferencesKey("remote_status")
    }
}
