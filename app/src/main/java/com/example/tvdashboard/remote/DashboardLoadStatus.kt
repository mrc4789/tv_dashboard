package com.example.tvdashboard.remote

/** In-memory observation, sent only for the exact configured URL. Never stores URL on server. */
object DashboardLoadStatus {
    private data class Snapshot(val url: String, val state: String)
    @Volatile private var snapshot = Snapshot("", "unknown")
    fun currentState(): String = snapshot.state
    fun set(url: String, state: String) { snapshot = Snapshot(url, state) }
    fun forUrl(url: String): String { val value = snapshot; return if (value.url == url) value.state else "unknown" }
}
