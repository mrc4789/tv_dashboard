package com.example.tvdashboard.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanFilterTest {

    @Test
    fun privateIpv4RangesAreAllowed() {
        assertTrue(LanFilter.isAllowed("192.168.1.5", false))
        assertTrue(LanFilter.isAllowed("10.0.0.2", false))
        assertTrue(LanFilter.isAllowed("172.16.5.1", false))
        assertTrue(LanFilter.isAllowed("172.31.255.1", false))
    }

    @Test
    fun linkLocalIsAllowed() {
        assertTrue(LanFilter.isAllowed("169.254.1.1", false))
        assertTrue(LanFilter.isAllowed("fe80::1", false))
    }

    @Test
    fun publicAddressesAreRejected() {
        assertFalse(LanFilter.isAllowed("8.8.8.8", false))
        assertFalse(LanFilter.isAllowed("172.32.0.1", false))
        assertFalse(LanFilter.isAllowed("2001:4860:4860::8888", false))
    }

    @Test
    fun loopbackOnlyWhenAllowed() {
        assertFalse(LanFilter.isAllowed("127.0.0.1", false))
        assertTrue(LanFilter.isAllowed("127.0.0.1", true))
        assertTrue(LanFilter.isAllowed("::1", true))
    }

    @Test
    fun invalidInputIsRejected() {
        assertFalse(LanFilter.isAllowed(null, true))
        assertFalse(LanFilter.isAllowed("", true))
        assertFalse(LanFilter.isAllowed("   ", true))
        assertFalse(LanFilter.isAllowed("example.com", true))
        assertFalse(LanFilter.isAllowed("192.168.1.5; DROP", true))
    }
}