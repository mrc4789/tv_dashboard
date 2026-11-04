package com.example.tvdashboard.web

/** Tính thời gian chờ giữa các lần thử lại: 10s, 20s, 40s, ... tối đa 5 phút. */
object Backoff {
    const val BASE_MS = 10_000L
    const val MAX_MS = 300_000L

    fun delayMillis(attempt: Int, baseMs: Long = BASE_MS, maxMs: Long = MAX_MS): Long {
        val exponent = attempt.coerceIn(0, 20)
        return (baseMs shl exponent).coerceAtMost(maxMs)
    }
}
