package com.example.tvdashboard.net

import android.content.Context
import com.example.tvdashboard.remote.PhoneConfiguration
import com.example.tvdashboard.remote.LanAutoPairing
import com.example.tvdashboard.remote.PhoneSetupDefaults
import com.example.tvdashboard.data.PrefsStore
import kotlinx.coroutines.runBlocking
import android.os.Build
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.security.SecureRandom

/**
 * Bật/tắt server cấu hình LAN có PIN, cho cả release và debug. Chỉ nên chạy khi màn hình Cài đặt đang mở.
 * Mỗi lần [start] sinh PIN mới. Trên thiết bị thật thử cổng 8080-8089;
 * khi bật bridge debug dùng đúng cổng đã cấu hình để ADB forward không bị lệch.
 */
class RemoteConfigController(
    private val context: Context,
    private val onConfigurationReceived: (PhoneConfiguration) -> String
) {

    data class Info(val address: String, val pin: String)

    sealed interface StartResult {
        data class Started(val info: Info) : StartResult
        object NoNetwork : StartResult
        object NeedsBridge : StartResult
        object NoPort : StartResult
    }

    private var monitorsLan = false
    private var observedLanAddress: String? = null
    fun updateLanAddress(address: String?) {
        monitorsLan = true
        observedLanAddress = address
    }
    private fun lanAddress(): String? = if (monitorsLan) observedLanAddress else LocalNetwork.findLanIpv4(context)
    private fun bridge(): DebugLanConfig.Bridge? = if (isEmulator()) DebugLanConfig.load(context) else null

    private var server: ConfigServer? = null
    private var currentInfo: Info? = null
    private var currentNetworkAddress: String? = null

    /** Preserve the PIN on resume; rotate only when the listening address changes. */
    fun ensureStarted(): StartResult {
        val bridge = bridge()
        val address = bridge?.address ?: lanAddress()
        val info = currentInfo
        if (server?.isAlive == true && info != null && address == currentNetworkAddress) {
            return StartResult.Started(visibleInfo(info))
        }
        return start()
    }
    fun rotatePinSession(): StartResult = start()
    @Volatile var managedPin = false
    private fun visibleInfo(info: Info): Info {
        val managed = managedPin
        return if (managed) info.copy(pin = "PIN do admin quản lý") else info
    }
    private val prefs by lazy { PrefsStore(context.applicationContext) }

    fun start(): StartResult {
        stop()
        val bridge = bridge()
        if (bridge == null && isEmulator()) return StartResult.NeedsBridge
        val ip = if (bridge == null) {
            lanAddress() ?: return StartResult.NoNetwork
        } else bridge.host
        val ports = bridge?.let { it.serverPort..it.serverPort } ?: PORTS
        val pin = SecureRandom().nextInt(10_000).toString().padStart(PIN_LENGTH, '0')

        for (port in ports) {
            val candidate = ConfigServer(
                port, pin, true, onConfigurationReceived,
                setupDefaults = { PhoneSetupDefaults.resolve(context, runBlocking { prefs.getRemoteSetup() }) },
                pinVerifier = { supplied -> runBlocking { com.example.tvdashboard.remote.PhonePinPolicy.matches(prefs.phonePinPolicy(), supplied, pin) } },
                autoSetup = {
                    runBlocking {
                        val current = prefs.getRemoteSetup()
                        LanAutoPairing.connect(PhoneSetupDefaults.resolve(context, current) ?: current).also {
                            // Save credentials before replying; enable remote mode when /pair synchronizes.
                            if (it != current.copy(enabled = true)) prefs.savePairingConnection(it)
                        }
                    }
                }
            )
            try {
                candidate.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
                server = candidate
                Log.i(TAG, "Server cấu hình chạy ở cổng $port")
                val info = Info(bridge?.address ?: "http://$ip:$port/", pin)
                currentInfo = info
                currentNetworkAddress = bridge?.address ?: ip
                return StartResult.Started(visibleInfo(info))
            } catch (e: IOException) {
                candidate.stop()
                Log.w(TAG, "Không mở được cổng $port")
            }
        }
        return StartResult.NoPort
    }

    fun stop() {
        server?.stop()
        server = null
        currentInfo = null
        currentNetworkAddress = null
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
                Build.HARDWARE.contains("goldfish") || Build.HARDWARE.contains("ranchu") ||
                Build.MODEL.contains("Android SDK") || Build.MODEL.contains("sdk_gphone") ||
                Build.MANUFACTURER.equals("Genymotion", ignoreCase = true)

    private companion object {
        const val TAG = "RemoteConfig"
        const val PIN_LENGTH = 4
        val PORTS = 8080..8089
    }
}