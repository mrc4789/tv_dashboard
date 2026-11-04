package com.example.tvdashboard.remote

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.net.InetAddress
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class LanAutoPairingTest {
    private val nonce = "a".repeat(32)
    private val source = InetAddress.getByName("192.168.50.10")
    private fun ad() = JSONObject().put("protocol", "tvdashboard-pairing-v1").put("nonce", nonce)
        .put("scheme", "http").put("port", 8765).put("open", true).put("ticket", "x".repeat(43))
    private fun saved() = RemoteSetup(true,"http://192.168.1.10:8765","saved-key","dev","TV-001","030826da-3825-46e1-a338-7f3fb641e201")
    private fun signed(): JSONObject {
        val key = MessageDigest.getInstance("SHA-256").digest(saved().apiKey.toByteArray())
        val mac = Mac.getInstance("HmacSHA256");mac.init(SecretKeySpec(key,"HmacSHA256"))
        val proof = mac.doFinal("$nonce|192.168.50.10|http|8765".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return ad().put("host","192.168.50.10").put("proof",proof)
    }
    @Test fun publicPeerRejected() { assertNull(LanAutoPairing.parse(ad().toString(),InetAddress.getByName("8.8.8.8"),nonce)) }
    @Test fun wrongNonceRejected() { assertNull(LanAutoPairing.parse(ad().put("nonce","b".repeat(32)).toString(),source,nonce)) }
    @Test fun malformedReplyRejected() { assertNull(LanAutoPairing.parse("not json",source,nonce)) }
    @Test fun invalidPortRejected() { assertNull(LanAutoPairing.parse(ad().put("port",99999).toString(),source,nonce)) }
    @Test fun endpointComesFromPacketNotArbitraryJsonUrl() {
        val value = LanAutoPairing.parse(ad().put("serverUrl","http://evil.example").toString(),source,nonce)!!
        assertEquals("http://192.168.50.10:8765",value.serverUrl)
    }
    @Test fun noServiceProducesUsefulError() { assertThrows(IllegalArgumentException::class.java) { LanAutoPairing.select(emptyList()) } }
    @Test fun enrollmentNeverInventsReplacementForAnExistingId() {
        val candidate = LanAutoPairing.Candidate("http://192.168.1.2:8765", "ticket", true)
        assertThrows(IllegalArgumentException::class.java) {
            LanAutoPairing.enroll(candidate, saved().copy(deviceId = "bad-id"))
        }
    }
    @Test fun multipleServicesFailClosed() {
        val c = LanAutoPairing.Candidate("http://192.168.1.2:8765","x",true)
        assertThrows(IllegalArgumentException::class.java) { LanAutoPairing.select(listOf(c,c.copy(serverUrl="http://192.168.1.3:8765"))) }
    }
    @Test fun closedWindowCannotProvisionFreshDevice() {
        assertThrows(IllegalArgumentException::class.java) { LanAutoPairing.select(listOf(LanAutoPairing.Candidate("http://192.168.1.2:8765","",false))) }
    }
    @Test fun existingCredentialsSurviveMissingDiscovery() { assertEquals(saved(),LanAutoPairing.resolve(saved(),emptyList())) }
    @Test fun unverifiedServiceCannotReplaceSavedEndpointOrKey() {
        val c=LanAutoPairing.Candidate("http://192.168.50.10:8765","x",true)
        assertEquals(saved(),LanAutoPairing.resolve(saved(),listOf(c)))
    }
    @Test fun validProofUpdatesOnlyEndpoint() {
        val c=LanAutoPairing.parse(signed().toString(),source,nonce,key=saved().apiKey)!!
        assertTrue(c.verified)
        assertEquals(saved().copy(serverUrl=c.serverUrl),LanAutoPairing.resolve(saved(),listOf(c)))
    }
    @Test fun forgedProofRejected() {
        assertFalse(LanAutoPairing.parse(signed().put("proof","0".repeat(64)).toString(),source,nonce,key=saved().apiKey)!!.verified)
    }
    @Test fun proofCannotBeRelayedToDifferentIp() {
        assertFalse(LanAutoPairing.parse(signed().toString(),InetAddress.getByName("192.168.50.99"),nonce,key=saved().apiKey)!!.verified)
    }
    @Test fun proofCannotBeReusedForDifferentPort() {
        assertFalse(LanAutoPairing.parse(signed().put("port",9999).toString(),source,nonce,key=saved().apiKey)!!.verified)
    }
}
