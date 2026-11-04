package com.example.tvdashboard.remote

import org.junit.Assert.*
import org.junit.Test

class PairingDefaultsPolicyTest {
    private fun saved() = RemoteSetup(true, "http://192.168.50.113:8765", "saved-tv-key", "dev", "TV-001", "e1ec1954-c40c-4a62-8a29-8e1630ae0a28")
    @Test fun completeSavedConnectionSupportsPinOnlyPairing() {
        val value = PairingDefaultsPolicy.connection(saved(), true)
        assertEquals(saved(), value)
        assertTrue(PairingDefaultsPolicy.canPair(value, true))
    }
    @Test fun missingDepartmentDoesNotDiscardServerAndKey() {
        val value = PairingDefaultsPolicy.connection(saved().copy(department = ""), true)
        assertNotNull(value)
        assertEquals(saved().apiKey, value!!.apiKey)
        assertEquals(saved().serverUrl, value.serverUrl)
        assertFalse(PairingDefaultsPolicy.canPair(value, true))
    }
    @Test fun missingNameGetsDeviceDefault() {
        val value = PairingDefaultsPolicy.connection(saved().copy(name = ""), true)!!
        assertEquals("TV-e1ec1954", value.name)
        assertTrue(PairingDefaultsPolicy.canPair(value, true))
    }
    @Test fun freshInstallHasNoInventedCredentials() {
        assertNull(PairingDefaultsPolicy.connection(RemoteSetup(), true))
        assertFalse(PairingDefaultsPolicy.canPair(null, true))
    }
    @Test fun invalidKeysAreNotReused() {
        for (key in listOf("", "bad key", "bad\nkey", "x".repeat(257))) {
            assertNull(PairingDefaultsPolicy.connection(saved().copy(apiKey = key), true))
        }
    }
    @Test fun httpsOnlyBuildPolicyStillRejectsHttp() {
        assertNull(PairingDefaultsPolicy.connection(saved(), false))
        assertNotNull(PairingDefaultsPolicy.connection(saved().copy(serverUrl = "https://api.example.com"), false))
    }
    @Test fun localModeKeepsPreviouslyPairedCredentials() {
        val value = PairingDefaultsPolicy.connection(saved().copy(enabled = false), true)!!
        assertTrue(value.enabled)
        assertEquals(saved().apiKey, value.apiKey)
    }
    @Test fun invalidDepartmentRequiresExplicitChoice() {
        val value = PairingDefaultsPolicy.connection(saved().copy(department = "BAD"), true)!!
        assertEquals("", value.department)
        assertFalse(PairingDefaultsPolicy.canPair(value, true))
    }
    @Test fun unknownSourceIsNotAcceptedAsAnApiBase() {
        assertNull(PairingDefaultsPolicy.connection(saved().copy(serverUrl = "https://example.com/admin/"), true))
    }
    @Test fun urlSchemeIsCaseInsensitive() {
        assertEquals("http://192.168.50.113:8765", RemotePolicy.serverUrl("HTTP://192.168.50.113:8765/", true))
    }
    @Test fun missingProtocolHasASeparateError() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            RemotePolicy.serverUrl("192.168.50.113:8765", true)
        }
        assertTrue(error.message.orEmpty().contains("Nhập đầy đủ"))
    }
    @Test fun debugPrintDoesNotContainSavedSecrets() {
        assertFalse(PairingDefaultsPolicy.connection(saved(), true).toString().contains("saved-tv-key"))
    }
}
