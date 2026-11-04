package com.example.tvdashboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlValidatorTest {

    private fun assertValid(input: String?, expected: String) {
        assertEquals(UrlValidator.Result.Valid(expected), UrlValidator.validate(input))
    }

    private fun assertInvalid(input: String?, error: UrlValidator.Error? = null) {
        val result = UrlValidator.validate(input)
        assertTrue("Kỳ vọng Invalid nhưng nhận $result", result is UrlValidator.Result.Invalid)
        if (error != null) {
            assertEquals(UrlValidator.Result.Invalid(error), result)
        }
    }

    @Test
    fun validHttps() = assertValid("https://dashboard.local/path?x=1", "https://dashboard.local/path?x=1")

    @Test
    fun validHttpWithPortAndIp() = assertValid("http://192.168.1.10:8080/d", "http://192.168.1.10:8080/d")

    @Test
    fun trimsWhitespace() = assertValid("  https://example.com  ", "https://example.com")

    @Test
    fun schemeIsCaseInsensitive() = assertValid("HTTPS://Example.com", "HTTPS://Example.com")

    @Test
    fun emptyIsInvalid() {
        assertInvalid("", UrlValidator.Error.EMPTY)
        assertInvalid("   ", UrlValidator.Error.EMPTY)
        assertInvalid(null, UrlValidator.Error.EMPTY)
    }

    @Test
    fun missingSchemeIsInvalid() = assertInvalid("example.com", UrlValidator.Error.BAD_SCHEME)

    @Test
    fun javascriptSchemeIsInvalid() = assertInvalid("javascript:alert(1)", UrlValidator.Error.BAD_SCHEME)

    @Test
    fun ftpSchemeIsInvalid() = assertInvalid("ftp://host/file", UrlValidator.Error.BAD_SCHEME)

    @Test
    fun missingHostIsInvalid() = assertInvalid("https://")

    @Test
    fun emptyAuthorityIsInvalid() = assertInvalid("https:///path")

    @Test
    fun spaceInsideIsInvalid() = assertInvalid("http://exa mple.com")
}
