package com.tt.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Fire OS ignores third-party HOME launchers and keeps the Home key from accessibility services,
 * so this service watches for Amazon's home screen coming to the front and puts our launcher
 * back on top.
 */
class HomeButtonService : AccessibilityService() {
    private var lastLaunch = 0L
    private var lastSettingsWindow = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "connected")
        // Also covers boot: the system binds this service on startup.
        bringHome()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val now = SystemClock.elapsedRealtime()
        if (pkg in AMAZON_SETTINGS) lastSettingsWindow = now
        if (pkg != AMAZON_LAUNCHER || event.className?.toString()?.startsWith(AMAZON_HOME_ACTIVITY) != true) return
        // Opening Fire TV Settings passes through Amazon's home screen right after a window from
        // the settings app; leave that detour alone. Anything else is the Home button.
        if (now - lastSettingsWindow < SETTINGS_DETOUR_MS) return
        bringHome()
    }

    override fun onInterrupt() {}

    private fun bringHome() {
        val now = SystemClock.elapsedRealtime()
        // Amazon's home fires several window events per appearance.
        if (now - lastLaunch < 500) return
        lastLaunch = now
        try {
            bringLauncherToFront()
        } catch (e: Exception) {
            Log.w(TAG, "could not bring launcher to front", e)
        }
    }

    companion object {
        private const val TAG = "TvHomeA11y"
        private const val AMAZON_LAUNCHER = "com.amazon.tv.launcher"
        private const val AMAZON_HOME_ACTIVITY = "com.amazon.tv.launcher.ui.HomeActivity"
        private val AMAZON_SETTINGS = setOf("com.amazon.tv.settings.v2", "com.amazon.tv.quicksettings.ui")
        private const val SETTINGS_DETOUR_MS = 800L
    }
}
