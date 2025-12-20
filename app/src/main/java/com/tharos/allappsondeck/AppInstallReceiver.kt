package com.tharos.allappsondeck

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AppInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Send a broadcast to tell MainActivity to refresh
        val refreshIntent = Intent("com.tharos.allappsondeck.REFRESH_APPS")
        refreshIntent.setPackage(context.packageName)
        context.sendBroadcast(refreshIntent)
    }
}
