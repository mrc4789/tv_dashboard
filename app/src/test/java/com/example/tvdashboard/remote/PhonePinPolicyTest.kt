package com.example.tvdashboard.remote

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class PhonePinPolicyTest {
    private fun policy(pin: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val verifier = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, 100000, 256)).encoded
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        return JSONObject().put("mode", "custom").put("salt", hex(salt))
            .put("hash", hex(verifier)).put("iterations", 100000).toString()
    }

    @Test fun customRotationRejectsOldPin() {
        val oldPolicy = policy("123456")
        val newPolicy = policy("654321")
        assertTrue(PhonePinPolicy.matches(oldPolicy, "123456", "0000"))
        assertTrue(PhonePinPolicy.matches(newPolicy, "654321", "0000"))
        assertFalse(PhonePinPolicy.matches(newPolicy, "123456", "0000"))
    }

    @Test fun randomModeUsesOnlyCurrentSessionPin() {
        val randomPolicy = """{"mode":"random"}"""
        assertTrue(PhonePinPolicy.matches(randomPolicy, "4812", "4812"))
        assertFalse(PhonePinPolicy.matches(randomPolicy, "1234", "4812"))
        assertTrue(PhonePinPolicy.managed(policy("123456")))
        assertFalse(PhonePinPolicy.managed(randomPolicy))
    }

    @Test fun invalidPolicyNeverFallsBackToRandomPin() {
        assertTrue(PhonePinPolicy.managed("""{"mode":"custom"}"""))
        assertFalse(PhonePinPolicy.matches("""{"mode":"custom"}""", "1234", "1234"))
    }
}
