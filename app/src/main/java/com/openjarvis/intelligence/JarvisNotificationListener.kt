package com.openjarvis.intelligence

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.openjarvis.accessibility.JarvisAccessibilityService

class JarvisNotificationListener : NotificationListenerService() {
    private val notifications = mutableListOf<JarvisNotification>()

    companion object {
        private val PRIVACY_PROTECTED_APPS = setOf(
            "com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm",
            "com.bankofamerica.cashpromobile", "com.chase", "com.wellsfargo", "com.usbank",
            "com.citi", "com.barclays", "com.hsbc", "com.db", "com.americanexpress",
            "com.discover", "com.paypal", "com.squareup", "com.stripe", "com.razorpay"
        )
        private val messagingApps = setOf(
            "com.whatsapp", "com.google.android.apps.messaging", "com.samsung.android.messaging",
            "com.instagram.android", "com.facebook.orca", "org.telegram.messenger",
            "com.slack", "com.discord"
        )
        fun shouldProcessNotification(packageName: String): Boolean =
            packageName !in PRIVACY_PROTECTED_APPS
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (shouldProcessNotification(sbn.packageName)) notifications.add(parseNotification(sbn))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        notifications.removeAll { it.id == sbn.id && it.packageName == sbn.packageName }
    }

    private fun parseNotification(sbn: StatusBarNotification): JarvisNotification {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val appLabel = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        } catch (_: Exception) {
            sbn.packageName
        }
        val isMessaging = sbn.packageName in messagingApps
        val sender = if (isMessaging) {
            title.takeIf { isMessaging && it.isNotBlank() }
        } else null
        return JarvisNotification(
            id = sbn.id,
            packageName = sbn.packageName,
            appLabel = appLabel,
            title = title,
            body = body,
            timestamp = sbn.postTime,
            isMessaging = isMessaging,
            sender = sender
        )
    }

    suspend fun replyToNotification(sender: String, packageName: String, reply: String): Boolean {
        if (notifications.none {
            it.packageName == packageName && it.sender?.contains(sender, ignoreCase = true) == true
        }) return false
        val service = JarvisAccessibilityService.instance ?: return false
        if (!service.tapByText("Reply")) return false
        if (!service.typeText(reply)) return false
        return service.tapByText("Send")
    }

    fun getUnread(packageName: String? = null): List<JarvisNotification> =
        if (packageName == null) notifications.toList()
        else notifications.filter { it.packageName == packageName }

    fun getSummary(): String = notifications
        .groupBy { it.packageName }
        .entries
        .sortedByDescending { it.value.size }
        .take(5)
        .joinToString(", ") { (_, messages) ->
            val first = messages.first()
            val sender = messages.lastOrNull()?.sender
            if (sender != null) "${messages.size} ${first.appLabel} from $sender"
            else "${messages.size} ${first.appLabel}"
        }

    fun clearAll(packageName: String? = null) {
        if (packageName == null) notifications.clear()
        else notifications.removeAll { it.packageName == packageName }
    }

    data class JarvisNotification(
        val id: Int,
        val packageName: String,
        val appLabel: String,
        val title: String,
        val body: String,
        val timestamp: Long,
        val isMessaging: Boolean,
        val sender: String?
    )
}
