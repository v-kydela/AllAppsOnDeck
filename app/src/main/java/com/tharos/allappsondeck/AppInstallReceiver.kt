package com.tharos.allappsondeck

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AppInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_PACKAGE_ADDED ||
            action == Intent.ACTION_PACKAGE_REMOVED ||
            action == Intent.ACTION_PACKAGE_REPLACED ||
            action == Intent.ACTION_PACKAGE_CHANGED) {
            
            // Send a broadcast to tell MainActivity to refresh
            val refreshIntent = Intent("com.tharos.allappsondeck.REFRESH_APPS")
            refreshIntent.setPackage(context.packageName)
            context.sendBroadcast(refreshIntent)
        }
    }
}
