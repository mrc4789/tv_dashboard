package com.example.tvdashboard.remote

import org.junit.Assert.*
import org.junit.Test

class RemotePolicyTest {
    private fun setup() = RemoteSetup(true, "https://example.com", "read-key", "qa-qc", "TV QA", "")
    @Test fun deviceIdSurvivesSubsequentReads() { val id=RemotePolicy.deviceId(null); assertEquals(4,java.util.UUID.fromString(id).version());assertEquals(id,RemotePolicy.deviceId(id)) }
    @Test fun existingDeviceIdIsNeverReplaced() { assertEquals("bad-id",RemotePolicy.deviceId("bad-id"));assertEquals("00000000-0000-4000-8000-000000000001",RemotePolicy.deviceId("00000000-0000-4000-8000-000000000001")) }
    @Test fun invalidExistingDeviceIdIsRejectedWithoutRegeneration() { assertThrows(IllegalArgumentException::class.java) { RemotePolicy.requireDeviceId(RemotePolicy.deviceId("bad-id")) } }
    @Test fun lateResponseForOldSetupIsDiscarded() { assertTrue(RemotePolicy.canApplyResponse(setup(),setup()));assertFalse(RemotePolicy.canApplyResponse(setup(),setup().copy(department="dev")));assertFalse(RemotePolicy.canApplyResponse(setup(),setup().copy(apiKey="new-key")));assertFalse(RemotePolicy.canApplyResponse(setup().copy(enabled=false),setup().copy(enabled=false))) }
    @Test fun serverNormalizesTrailingSlash() { assertEquals("https://example.com", RemotePolicy.serverUrl(" https://example.com/ ", false)) }
    @Test fun debugAllowsHttp() { assertEquals("http://192.168.50.113:8765", RemotePolicy.serverUrl("http://192.168.50.113:8765", true)) }
    @Test fun releaseRejectsHttp() { assertThrows(IllegalArgumentException::class.java) { RemotePolicy.serverUrl("http://example.com", false) } }
    @Test fun rejectsAdminPathAndCredentials() { for (url in listOf("https://example.com/admin", "https://a:b@example.com", "https://example.com?key=x", "https://example.com#x", "javascript:alert(1)", "https://example.com:70000")) assertThrows(IllegalArgumentException::class.java) { RemotePolicy.serverUrl(url, true) } }
    @Test fun setupTrimsName() { assertEquals("TV QA", RemotePolicy.validate(setup().copy(name=" TV QA "), false).name) }
    @Test fun invalidKeyIsRejected() { for (key in listOf("", "x\ny", "x y", "x".repeat(257))) assertThrows(IllegalArgumentException::class.java) { RemotePolicy.validate(setup().copy(apiKey=key), false) } }
    @Test fun invalidDepartmentIsRejected() { for (id in listOf("", "QA", "a/b", "a".repeat(33))) assertThrows(IllegalArgumentException::class.java) { RemotePolicy.validate(setup().copy(department=id), false) } }
    @Test fun invalidTvNameIsRejected() { for (name in listOf("", "x\ny", "x".repeat(65))) assertThrows(IllegalArgumentException::class.java) { RemotePolicy.validate(setup().copy(name=name), false) } }
    @Test fun cacheWinsInRemoteMode() { assertEquals("https://cached", RemotePolicy.effectiveUrl(true,true,"https://cached","https://local")) }
    @Test fun localModeIgnoresCache() { assertEquals("https://local", RemotePolicy.effectiveUrl(false,true,"https://cached","https://local")) }
    @Test fun wrongSourceNeverUsesCache() { assertEquals("https://local", RemotePolicy.effectiveUrl(true,false,"https://other-department","https://local")) }
    @Test fun missingCacheFallsBackToLocal() { assertEquals("https://local", RemotePolicy.effectiveUrl(true,true,null,"https://local")) }
    @Test fun noUrlStaysUnconfigured() { assertNull(RemotePolicy.effectiveUrl(true,true,null,null)) }
    @Test fun keyRotationPreservesSameSource() { assertTrue(RemotePolicy.sameSource(setup(),setup().copy(apiKey="new-key"))) }
    @Test fun serverOrDepartmentChangeIsDifferentSource() { assertFalse(RemotePolicy.sameSource(setup(),setup().copy(department="dev"))); assertFalse(RemotePolicy.sameSource(setup(),setup().copy(serverUrl="https://new.example.com"))) }
    @Test fun releaseRejectsHttpDashboard() { assertThrows(IllegalArgumentException::class.java) { RemotePolicy.dashboardUrl("http://example.com",false) } }
    @Test fun rejectsDashboardCredentialsAndInvalidPort() { for (url in listOf("https://user:pass@example.com", "https://example.com:70000", "javascript:alert(1)")) assertThrows(IllegalArgumentException::class.java) { RemotePolicy.dashboardUrl(url,true) } }
    @Test fun retryCannotCreateTightLoop() { assertEquals(300,RemotePolicy.nextDelaySeconds(300,1));assertEquals(600,RemotePolicy.nextDelaySeconds(300,600));assertEquals(60,RemotePolicy.nextDelaySeconds(1,null));assertEquals(3600,RemotePolicy.nextDelaySeconds(99999,99999)) }
    @Test fun setupToStringRedactsKeyAndUrl() { val printed=setup().copy(apiKey="super-secret").toString(); assertFalse(printed.contains("super-secret"));assertFalse(printed.contains("example.com")) }
    @Test fun phoneRemoteConfigurationValidatesFields() { val fields=mapOf("mode" to "remote","server" to "https://example.com","key" to "tv-key","department" to "dev","name" to "TV Dev","reloadMinutes" to "10"); val result=PhoneConfigurationParser.parse(fields,false) as PhoneConfiguration.Remote;assertEquals("dev",result.setup.department);assertEquals(10,result.reloadMinutes) }
    @Test fun phoneLocalWithoutReloadKeepsExistingInterval() { val result=PhoneConfigurationParser.parse(mapOf("url" to "https://example.com"),true) as PhoneConfiguration.Local;assertNull(result.reloadMinutes) }
    @Test fun phoneRejectsInvalidReloadOrMode() { assertThrows(IllegalArgumentException::class.java) { PhoneConfigurationParser.parse(mapOf("url" to "https://example.com","reloadMinutes" to "-1"),true) };assertThrows(IllegalArgumentException::class.java) { PhoneConfigurationParser.parse(mapOf("mode" to "unknown"),true) } }
}
