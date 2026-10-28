package com.example.glasslauncher

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Lets the launcher's shade show and dismiss real notifications (user must grant access once). */
class NotifService : NotificationListenerService() {

    companion object {
        var instance: NotifService? = null
        var onChange: (() -> Unit)? = null
    }

    override fun onListenerConnected() {
        instance = this
        onChange?.invoke()
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        onChange?.invoke()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        onChange?.invoke()
    }
}
