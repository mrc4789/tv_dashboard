package com.example.tvdashboard.remote

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Opt-in, bounded diagnostics. Only event categories and numeric codes leave the device. */
object RemoteDiagnostics {
    private data class Event(val id: String, val kind: String, val code: String, val occurredAt: String) {
        fun json() = JSONObject().put("id", id).put("kind", kind).put("code", code).put("occurredAt", occurredAt)
    }

    private const val TAG = "RemoteDiagnostics"
    private const val PREFS_NAME = "remote_diagnostics"
    private const val MAX_EVENTS = 100
    private const val BATCH_SIZE = 20
    private const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    private val allowedKinds = setOf("NETWORK", "HTTP_SERVER", "SSL", "TIMEOUT", "RENDERER", "LOADED", "LOAD_ERROR")
    private val lock = Any()
    private val persistMutex = Mutex()
    private var acknowledgedPolicy: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ready = CompletableDeferred<Unit>()
    private val queue = ArrayDeque<Event>()
    private var appContext: Context? = null
    private var preferences: SharedPreferences? = null
    private var epoch = ""
    private var owner = ""
    private var policyVersion = -1L
    private var activeSetup: RemoteSetup? = null
    @Volatile private var enabled = false
    private var flushJob: Job? = null

    fun initialize(context: Context) {
        synchronized(lock) {
            if (appContext != null) return
            appContext = context.applicationContext
            scope.launch {
                try {
                    preferences = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    loadQueue()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not load diagnostics queue; category=${e.javaClass.simpleName}")
                } finally {
                    ready.complete(Unit)
                }
            }
        }
    }

    fun record(kind: String, code: String? = null) {
        if (!enabled || kind !in allowedKinds) return
        val event = Event(
            UUID.randomUUID().toString(),
            kind,
            code?.takeIf { it.matches(Regex("[0-9]{1,3}")) }.orEmpty(),
            Instant.now().toString()
        )
        synchronized(lock) {
            if (!enabled) return
            pruneLocked()
            if (queue.size >= MAX_EVENTS) queue.removeFirst()
            queue.addLast(event)
        }
        scope.launch {
            ready.await()
            persistQueue()
            scheduleFlush()
        }
    }

    fun onSetupChanged(setup: RemoteSetup) {
        val nextOwner = identity(setup)
        val discard = synchronized(lock) {
            if (!ready.isCompleted) return@synchronized false
            if (!DevicePolicySync.canSync(setup) || owner.isNotEmpty() && owner != nextOwner) {
                if (owner != nextOwner) policyVersion = -1L
                owner = nextOwner
                activeSetup = null
                enabled = false
                queue.clear()
                true
            } else false
        }
        if (discard) {
            flushJob?.cancel()
            scope.launch {
                ready.await()
                persistQueue()
            }
        }
    }

    suspend fun configure(setup: RemoteSetup, allowed: Boolean, version: Long, newEpoch: String = "") = withContext(Dispatchers.IO) {
        ready.await()
        val identity = identity(setup)
        val changed = synchronized(lock) {
            if (identity == owner && newEpoch == epoch && version < policyVersion) return@withContext
            val changed = identity != owner || newEpoch != epoch || policyVersion != version || enabled != allowed
            if (owner.isNotEmpty() && (identity != owner || epoch != newEpoch)) queue.clear()
            epoch = newEpoch
            owner = identity
            policyVersion = version
            activeSetup = setup
            enabled = allowed
            if (!allowed) queue.clear()
            changed
        }
        if (changed) persistQueue()
        // Report the CURRENT observed state after opt-in, never replay pre-consent history.
        if (changed && allowed) {
            when (DashboardLoadStatus.currentState()) {
                "loaded" -> record("LOADED")
                "error" -> record("LOAD_ERROR")
            }
        }
        val ack = "$identity|$newEpoch|$version|$allowed"
        if (synchronized(lock) { acknowledgedPolicy != ack }) {
            if (acknowledgePolicy(setup, allowed, version)) synchronized(lock) { acknowledgedPolicy = ack }
        }
        if (allowed) scheduleFlush() else flushJob?.cancel()
    }

    private suspend fun loadQueue() = withContext(Dispatchers.IO) {
        val prefs = preferences ?: return@withContext
        val savedOwner = prefs.getString("owner", "").orEmpty()
        val savedVersion = prefs.getLong("policy_version", -1L)
        val saved = try { JSONArray(prefs.getString("events", "[]")) } catch (e: Exception) {
            Log.w(TAG, "Stored queue is invalid; discarding local diagnostics")
            JSONArray()
        }
        synchronized(lock) {
            epoch = prefs.getString("db_epoch", "").orEmpty()
            owner = savedOwner
            policyVersion = savedVersion
            for (index in 0 until saved.length().coerceAtMost(MAX_EVENTS)) {
                val value = saved.optJSONObject(index) ?: continue
                val id = value.optString("id")
                val kind = value.optString("kind")
                val code = value.optString("code")
                val occurredAt = value.optString("occurredAt")
                if (!validEvent(id, kind, code, occurredAt)) continue
                queue.addLast(Event(id, kind, code, occurredAt))
            }
            pruneLocked()
        }
    }

    private fun validEvent(id: String, kind: String, code: String, occurredAt: String): Boolean {
        val validId = try { UUID.fromString(id).toString() == id } catch (_: IllegalArgumentException) { false }
        val timestamp = try { Instant.parse(occurredAt).toEpochMilli() } catch (_: Exception) { return false }
        return validId && kind in allowedKinds && code.matches(Regex("[0-9]{0,3}")) &&
            System.currentTimeMillis() - timestamp <= RETENTION_MS
    }

    private fun pruneLocked() {
        val now = System.currentTimeMillis()
        queue.removeAll { event ->
            val time = try { Instant.parse(event.occurredAt).toEpochMilli() } catch (_: Exception) { 0L }
            now - time > RETENTION_MS || time > now + 60_000
        }
        while (queue.size > MAX_EVENTS) queue.removeFirst()
    }

    // Serialize writers, snapshot under the short state lock, write on IO OUTSIDE it.
    // UI record()/onSetupChanged() never wait on a disk commit holding this lock.
    private suspend fun persistQueue() = withContext(Dispatchers.IO) {
        persistMutex.withLock {
            val prefs = preferences ?: return@withLock
            val snapshot = synchronized(lock) { Triple(owner to epoch, policyVersion, queue.toList()) }
            try {
                val events = JSONArray()
                snapshot.third.forEach { events.put(it.json()) }
                if (!prefs.edit().putString("owner", snapshot.first.first).putString("db_epoch",snapshot.first.second).putLong("policy_version", snapshot.second)
                        .putString("events", events.toString()).commit()) {
                    Log.w(TAG, "Could not persist diagnostics queue")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not persist diagnostics queue; category=${e.javaClass.simpleName}")
            }
        }
    }

    private fun scheduleFlush() {
        synchronized(lock) {
            if (flushJob?.isActive == true || !enabled) return
            val job = scope.launch { flushLoop() }
            flushJob = job
            job.invokeOnCompletion {
                synchronized(lock) {
                    if (flushJob === job) {
                        flushJob = null
                        if (enabled && activeSetup != null && queue.isNotEmpty()) scheduleFlush()
                    }
                }
            }
        }
    }

    private suspend fun flushLoop() {
        var retryMs = 2_000L
        while (true) {
            val (setup, identity, batch) = synchronized(lock) {
                pruneLocked()
                val current = activeSetup
                if (!enabled || current == null || queue.isEmpty()) return
                Triple(current, owner, queue.take(BATCH_SIZE))
            }
            persistQueue()
            try {
                post(setup, "/api/v1/devices/${setup.deviceId}/diagnostics",
                    JSONObject().put("events", JSONArray().also { array -> batch.forEach { array.put(it.json()) } }))
                synchronized(lock) {
                    if (owner == identity) {
                        val ids = batch.map { it.id }.toSet()
                        queue.removeAll { it.id in ids }
                    }
                }
                persistQueue()
                retryMs = 2_000L
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Upload failed; category=${e.javaClass.simpleName}; pending=${batch.size}")
                delay(retryMs)
                retryMs = (retryMs * 2).coerceAtMost(300_000L)
            }
        }
    }

    private suspend fun acknowledgePolicy(setup: RemoteSetup, allowed: Boolean, version: Long): Boolean {
        return try {
            post(setup, "/api/v1/devices/${setup.deviceId}/diagnostics/ack",
                JSONObject().put("enabled", allowed).put("version", version))
            true
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            Log.w(TAG, "Policy acknowledgement failed; category=${e.javaClass.simpleName}")
            false
        }
    }

    private suspend fun post(setup: RemoteSetup, path: String, body: JSONObject) = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val id = RemotePolicy.requireDeviceId(setup.deviceId)
            val c = (URL(setup.serverUrl + path.replace(setup.deviceId, id)).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3_000
                readTimeout = 3_000
                instanceFollowRedirects = false
                doOutput = true
                setRequestProperty("x-api-key", setup.apiKey)
                setRequestProperty("Content-Type", "application/json")
            }
            connection = c
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val status = c.responseCode
            if (status != 200) throw IOException("HTTP $status")
        } finally {
            connection?.disconnect()
        }
    }

    private fun identity(setup: RemoteSetup): String {
        val raw = "${setup.serverUrl}|${setup.deviceId}|${setup.department}|${setup.apiKey}".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
