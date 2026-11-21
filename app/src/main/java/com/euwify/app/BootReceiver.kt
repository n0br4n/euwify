package com.euwify.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Starts the service after the phone restarts or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                ActivityLog.add(
                    context,
                    if (intent.action == Intent.ACTION_BOOT_COMPLETED) "Phone restarted" else "App updated",
                )
                if (CredentialStore(context).hasLogin()) {
                    Log.i("EuWiFy", "starting service after ${intent.action}")
                    try {
                        GuardService.start(context)
                    } catch (e: Exception) {
                        Log.e("EuWiFy", "could not start service", e)
                    }
                }
            }
        }
    }
}
