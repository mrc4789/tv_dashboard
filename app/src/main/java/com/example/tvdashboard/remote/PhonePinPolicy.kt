package com.example.tvdashboard.remote

import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.json.JSONObject

/** Only a salted verifier is stored or transported, never the admin PIN. */
object PhonePinPolicy {
    fun validate(raw: String): String {
        val p = JSONObject(raw)
        require(p.getString("mode") in listOf("random", "custom"))
        if (p.getString("mode") == "custom") {
            require(Regex("[0-9a-f]{32}").matches(p.getString("salt")))
            require(Regex("[0-9a-f]{64}").matches(p.getString("hash")))
            require(p.getInt("iterations") == 100000)
        }
        return raw
    }
    fun managed(raw: String): Boolean = try {
        JSONObject(validate(raw)).getString("mode") == "custom"
    } catch (_: Exception) {
        true
    }
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    fun matches(raw: String, supplied: String, randomPin: String): Boolean {
        return try {
            val p = JSONObject(validate(raw))
            if (!managed(raw)) MessageDigest.isEqual(supplied.toByteArray(), randomPin.toByteArray())
            else if (!Regex("[0-9]{6,8}").matches(supplied)) false
            else {
                val spec = PBEKeySpec(supplied.toCharArray(), bytes(p.getString("salt")), 100000, 256)
                try { MessageDigest.isEqual(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, bytes(p.getString("hash"))) }
                finally { spec.clearPassword() }
            }
        } catch (_: Exception) { false } // Invalid policy never enables random-PIN fallback.
    }
}
