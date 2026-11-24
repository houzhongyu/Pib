package com.houxinzhe.pib.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.houxinzhe.pib.PibApp
import com.houxinzhe.pib.data.ButlerParser

/**
 * Strictly opt-in local notification parser.
 * - Does nothing unless the user turns on "自动识别取件码与行程通知" AND grants Notification Access.
 * - Hard-ignores WeChat, QQ, Alipay, banking apps, and OTP messages.
 * - Runs 100% locally via regex ([ButlerParser]); never sends notification text to any server.
 */
class PibNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        val app = applicationContext as? PibApp ?: return
        if (!app.settings.autoParseNotifications) return
        val pkg = n.packageName?.lowercase() ?: return
        if (pkg in IGNORED_PACKAGES || IGNORED_KEYWORDS.any { pkg.contains(it) }) return
        if (!ALLOWED_SOURCES.any { pkg.contains(it) }) return

        val extras = n.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val combined = "$title $body".trim()
        if (combined.length < 6) return

        ButlerParser.parseParcel(combined)?.let { app.butler.addParcel(it); return }
        ButlerParser.parseTrip(combined)?.let { app.butler.addTrip(it) }
    }

    companion object {
        private val IGNORED_PACKAGES = setOf(
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.tencent.wework",
            "com.eg.android.alipaygphone",
            "com.unionpay",
        )
        private val IGNORED_KEYWORDS = listOf("bank", "wallet", "pay", "pib")
        /** Only inspect SMS/MMS apps and logistics/travel apps. */
        private val ALLOWED_SOURCES = listOf(
            "mms", "messaging", "message", "sms",
            "cainiao", "sf.activity", "hivebox", "fengchao", "12306", "MobileTicket".lowercase(), "umetrip", "ctrip", "qunar",
        )
    }
}
