package com.example.tvdashboard.web

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffTest {

    @Test
    fun firstAttemptUsesBaseDelay() = assertEquals(10_000L, Backoff.delayMillis(0))

    @Test
    fun delayDoublesEachAttempt() {
        assertEquals(20_000L, Backoff.delayMillis(1))
        assertEquals(40_000L, Backoff.delayMillis(2))
        assertEquals(80_000L, Backoff.delayMillis(3))
    }

    @Test
    fun delayIsCappedAtMax() {
        assertEquals(300_000L, Backoff.delayMillis(5))
        assertEquals(300_000L, Backoff.delayMillis(100))
    }

    @Test
    fun negativeAttemptIsTreatedAsZero() = assertEquals(10_000L, Backoff.delayMillis(-3))

    @Test
    fun customBaseAndMax() = assertEquals(3_000L, Backoff.delayMillis(10, baseMs = 1_000L, maxMs = 3_000L))
}
