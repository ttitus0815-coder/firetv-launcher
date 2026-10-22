package com.tt.tvlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        try {
            context.ensureHomeServiceEnabled()
        } catch (e: Exception) {
            Log.w("TvHomeBoot", "could not re-enable accessibility service", e)
        }
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            context.bringLauncherToFront()
        } catch (e: Exception) {
            Log.w("TvHomeBoot", "could not start launcher on boot", e)
        }
    }

    companion object {
        private val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
