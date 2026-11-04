package com.example.tvdashboard.web

import android.util.Log
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Quản lý thử lại (backoff), reload định kỳ và watchdog khi tải trang bị treo.
 * Mọi callback chạy trên dispatcher của [scope] (Main khi dùng lifecycleScope).
 */
class ReloadManager(
    private val scope: CoroutineScope,
    private val onReload: () -> Unit,
    private val onLoadTimeout: () -> Unit
) {

    private var attempt = 0
    private var retryJob: Job? = null
    private var retryDeadlineMs = 0L
    private var watchdogJob: Job? = null

    /** Lên lịch thử lại. Nếu đã có lịch thì giữ nguyên. Trả về thời gian chờ (ms). */
    fun scheduleRetry(): Long {
        if (retryJob?.isActive == true) return (retryDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        val delayMs = Backoff.delayMillis(attempt)
        attempt++
        retryDeadlineMs = SystemClock.elapsedRealtime() + delayMs
        Log.i(TAG, "Thử lại sau ${delayMs / 1000}s (lần $attempt)")
        retryJob = scope.launch {
            delay(delayMs)
            // Detach before callback: starting a new load must not cancel this job itself.
            retryJob = null
            retryDeadlineMs = 0L
            onReload()
        }
        return delayMs
    }

    fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
        retryDeadlineMs = 0L
    }

    fun retryRemainingSeconds(): Int =
        ((retryDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 999L).div(1000L).toInt()

    /** Đặt lại backoff về ban đầu và hủy lịch thử lại. */
    fun resetBackoff() {
        attempt = 0
        cancelRetry()
    }

    fun onLoadSuccess() {
        resetBackoff()
        cancelWatchdog()
    }

    /** Nếu sau [timeoutMs] trang chưa tải xong cũng chưa báo lỗi thì gọi onLoadTimeout. */
    fun armWatchdog(timeoutMs: Long) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(timeoutMs)
            watchdogJob = null
            Log.w(TAG, "Tải trang quá ${timeoutMs / 1000}s, coi như lỗi")
            onLoadTimeout()
        }
    }

    fun cancelWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    /** Reload định kỳ. Chạy cho đến khi coroutine bị hủy. 0 hoặc âm = tắt. */
    suspend fun runPeriodic(intervalMinutes: Int) {
        if (intervalMinutes <= 0) return
        while (true) {
            delay(intervalMinutes.toLong() * 60_000L)
            Log.i(TAG, "Reload định kỳ (mỗi $intervalMinutes phút)")
            onReload()
        }
    }

    private companion object {
        const val TAG = "ReloadManager"
    }
}
