package com.remotphone.agent

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.google.gson.JsonObject

class NotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val app = sbn.packageName

        // Get app label
        val appLabel = try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(app, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            app
        }

        val msg = JsonObject().apply {
            addProperty("type", "notification")
            addProperty("app", appLabel)
            addProperty("packageName", app)
            addProperty("title", title)
            addProperty("text", text)
            addProperty("timestamp", sbn.postTime)
        }

        // Forward to RemoteService which sends to PC
        RemoteService.instance?.sendToPC(msg.toString())
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Optional: notify PC that notification was dismissed
    }
}
