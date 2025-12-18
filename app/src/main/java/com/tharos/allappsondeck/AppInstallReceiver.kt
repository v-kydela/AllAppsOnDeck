package com.tharos.allappsondeck

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AppInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_PACKAGE_ADDED ||
            intent.action == Intent.ACTION_PACKAGE_REMOVED ||
            intent.action == Intent.ACTION_PACKAGE_REPLACED) {
            
            // Check if the activity is already running and refresh its list
            if (context is MainActivity) {
                context.refreshApps()
            } else {
                // If context is not MainActivity (it usually isn't for manifest-registered receivers),
                // we might need a different way to notify the activity if it's running.
                // For a simple launcher, sending a local broadcast or using a shared ViewModel/Repository is better.
                // But for now, we can just let it refresh next time it starts, or use a more robust notification.
            }
        }
    }
}
