package com.example.tvdashboard.boot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.tvdashboard.ui.MainActivity

/** Opt-in fallback. No node tree, gestures, key filtering, shell or network access. */
class HomeAssistService : AccessibilityService() {
    private var targets = emptySet<String>()
    private val gate = HomeEventGate()
    private var lastScan = -30000L
    companion object {
        @Volatile var connected = false
            private set
        private var instance: HomeAssistService? = null
        fun stopSupport() { instance?.disableSelf() }
    }
    override fun onServiceConnected() {
        instance = this
        connected = true
        refreshTargets()
    }
    private fun refreshTargets() {
        // Discover HOME activities rather than assume a Xiaomi/Google package name.
        targets = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY
        ).mapNotNull { resolved ->
            val a = resolved.activityInfo ?: return@mapNotNull null
            if (a.packageName == packageName || !a.enabled || !a.exported ||
                a.name.contains("FallbackHome", ignoreCase = true) ||
                a.packageName == "com.android.settings" || a.packageName == "com.android.tv.settings") null
            else a.packageName + "/" + (a.targetActivity ?: a.name)
        }.toSet()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 150
            // Empty discoveries must not subscribe to every app.
            packageNames = targets.map { it.substringBefore("/") }.distinct().ifEmpty { listOf(packageName) }.toTypedArray()
        }
        lastScan = SystemClock.elapsedRealtime()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!HomeAssistSetup.enabled(this) || event == null ||
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastScan >= 30000) refreshTargets()
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        // Exact activity match prevents redirecting launcher settings/dialogs.
        if (!gate.accept(HomeAssistSetup.enabled(this),
                getSystemService(PowerManager::class.java).isInteractive,
                getSystemService(KeyguardManager::class.java).isKeyguardLocked,
                targets, pkg, cls, now)) return
        try {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (e: RuntimeException) {
            Log.w("HomeAssist", "System refused Home redirection", e)
        }
    }
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        if (instance === this) { instance = null; connected = false }
        super.onDestroy()
    }
}
