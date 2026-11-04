package com.example.tvdashboard.remote

import com.example.tvdashboard.BuildConfig
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Collections
import java.util.UUID
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Run only after the phone proves the current TV PIN. No credentials in discovery/QR. */
object LanAutoPairing {
    const val PORT = 8766
    private const val PROTOCOL = "tvdashboard-pairing-v1"
    data class Candidate(val serverUrl: String, val ticket: String, val open: Boolean, val verified: Boolean = false)
    private fun hash(key: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun privateIpv4(address: InetAddress): Boolean {
        if (address !is Inet4Address) return false
        val b = address.address.map { it.toInt() and 255 }
        return b[0] == 10 || b[0] == 172 && b[1] in 16..31 || b[0] == 192 && b[1] == 168
    }

    internal fun targets(): Set<InetAddress> {
        val result = mutableSetOf(InetAddress.getByName("255.255.255.255"))
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result
        Collections.list(interfaces).filter { it.isUp && !it.isLoopback }.forEach { network ->
            network.interfaceAddresses.filter { privateIpv4(it.address) }.forEach {
                it.broadcast?.let(result::add)
            }
        }
        return result
    }

    internal fun parse(raw: String, source: InetAddress, nonce: String, allowLoopback: Boolean = false, key: String = ""): Candidate? = try {
        if (!privateIpv4(source) && !(allowLoopback && source.isLoopbackAddress)) null else {
            val data = JSONObject(raw)
            require(data.getString("protocol") == PROTOCOL && data.getString("nonce") == nonce)
            val port = data.getInt("port"); require(port in 1..65535)
            val scheme = data.getString("scheme"); require(scheme in listOf("http", "https"))
            val url = RemotePolicy.serverUrl("$scheme://${source.hostAddress}:$port", BuildConfig.ALLOW_CLEARTEXT_HTTP)
            val open = data.getBoolean("open")
            val ticket = data.optString("ticket")
            if (open) require(Regex("[A-Za-z0-9_-]{32,128}").matches(ticket))
            var verified = false
            if (key.isNotBlank() && data.optString("host") == source.hostAddress) {
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(hash(key), "HmacSHA256"))
                val expected = hex(mac.doFinal("$nonce|${source.hostAddress}|$scheme|$port".toByteArray(Charsets.UTF_8)))
                verified = MessageDigest.isEqual(expected.toByteArray(), data.optString("proof").toByteArray())
            }
            Candidate(url, ticket, open, verified)
        }
    } catch (_: Exception) { null }

    internal fun discover(destinations: Set<InetAddress> = targets(), port: Int = PORT,
                          allowLoopback: Boolean = false, key: String = ""): List<Candidate> {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val request = JSONObject().put("protocol", PROTOCOL).put("nonce", nonce)
        if (key.isNotBlank()) request.put("fingerprint", hex(hash(key)).take(32))
        val bytes = request.toString().toByteArray(Charsets.UTF_8)
        val found = linkedMapOf<String, Candidate>()
        DatagramSocket().use { socket ->
            socket.broadcast = true; socket.soTimeout = 200
            val started = System.nanoTime()
            var nextSend = 0L
            while ((System.nanoTime() - started) / 1_000_000 < 2400) {
                val elapsed = (System.nanoTime() - started) / 1_000_000
                if (elapsed >= nextSend && nextSend < 2100) {
                    destinations.forEach { address ->
                        try { socket.send(DatagramPacket(bytes, bytes.size, address, port)) } catch (_: java.io.IOException) { }
                    }
                    nextSend += 700
                }
                val packet = DatagramPacket(ByteArray(2049), 2049)
                try {
                    socket.receive(packet)
                    if (packet.length > 2048) continue
                    parse(String(packet.data, 0, packet.length, Charsets.UTF_8), packet.address, nonce, allowLoopback, key)
                        ?.let { found[it.serverUrl] = it }
                } catch (_: SocketTimeoutException) { }
            }
        }
        return found.values.toList()
    }

    internal fun select(candidates: List<Candidate>): Candidate {
        require(candidates.isNotEmpty()) { "Chưa tìm thấy service trong LAN. Chạy Start-Service.cmd trên máy tính cùng Wi-Fi; cho phép Python qua firewall mạng Private (UDP 8766 và cổng API), rồi thử PIN lại." }
        require(candidates.size == 1) { "Có nhiều service trong LAN; app chưa tự chọn để tránh ghép nhầm. Giữ một service cần dùng đang chạy rồi thử lại." }
        return candidates.single().also {
            require(it.open) { "Service đã hết thời gian ghép mới. Khởi động lại Start-Service.cmd rồi nhập PIN lại trong 10 phút." }
        }
    }

    internal fun resolve(current: RemoteSetup, candidates: List<Candidate>): RemoteSetup {
        val saved = PairingDefaultsPolicy.connection(current, BuildConfig.ALLOW_CLEARTEXT_HTTP)
        if (saved != null) {
            val trusted = candidates.filter { it.verified }
            require(trusted.size <= 1) { "Nhiều service cùng giữ key của TV. Chưa đổi địa chỉ; chỉ giữ service cần dùng rồi thử lại." }
            // Never replace a saved key or send it to a server that cannot prove possession.
            return trusted.singleOrNull()?.let { saved.copy(serverUrl = it.serverUrl) } ?: saved
        }
        return enroll(select(candidates), current)
    }

    fun connect(current: RemoteSetup): RemoteSetup = try {
        resolve(current, discover(key = current.apiKey))
    } catch (_: java.io.IOException) {
        throw IllegalArgumentException("Chưa truy cập được mạng LAN. Kiểm tra Wi-Fi trên TV và service, rồi thử PIN lại.")
    }

    internal fun enroll(candidate: Candidate, current: RemoteSetup): RemoteSetup {
        val id = RemotePolicy.requireDeviceId(current.deviceId)
        val connection = URL(candidate.serverUrl + "/api/v1/pairing/enroll").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 4000; connection.readTimeout = 4000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val body = JSONObject().put("ticket", candidate.ticket).put("deviceId", id).toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            require(status == 200) { "Service từ chối ghép tự động (HTTP $status). Kiểm tra thời gian ghép LAN và pairingDepartment, rồi thử lại." }
            val text = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    require(out.size() + n <= 8192) { "Phản hồi ghép quá lớn." }
                    out.write(buffer, 0, n)
                }
                out.toString("UTF-8")
            }
            val data = JSONObject(text)
            return RemotePolicy.validate(current.copy(enabled = true, serverUrl = candidate.serverUrl,
                apiKey = data.getString("apiKey"), department = data.getString("department"),
                name = current.name.ifBlank { data.getString("name") }, deviceId = id), BuildConfig.ALLOW_CLEARTEXT_HTTP)
        } catch (e: IllegalArgumentException) { throw e
        } catch (_: javax.net.ssl.SSLException) {
            throw IllegalArgumentException("Chứng chỉ HTTPS của service chưa được thiết bị tin cậy. Không thể tự ghép.")
        } catch (_: Exception) {
            throw IllegalArgumentException("Không nhận được cấu hình ghép từ service. Kiểm tra service, Wi-Fi và firewall rồi thử PIN lại.")
        } finally { connection.disconnect() }
    }
}
