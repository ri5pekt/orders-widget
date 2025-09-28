package com.particleformen.orderswidget

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

private const val CHANNEL_ID = "orders_widget_alerts"
private const val CHANNEL_NAME = "Orders Widget Alerts"
private const val NOTIF_ID_LOW_ORDERS = 1001

fun ensureAlertChannel(ctx: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Alerts when the last-hour order count looks abnormally low."
            }
            mgr.createNotificationChannel(ch)
        }
    }
}

fun notifyLowOrders(ctx: Context, count: Int, threshold: Int) {
    // Android 13+ requires runtime POST_NOTIFICATIONS permission
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return // silently skip if not granted
    }

    // Also respect user-level notification disable
    val nm = NotificationManagerCompat.from(ctx)
    if (!nm.areNotificationsEnabled()) return

    val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_alert) // built-in safe icon
        .setContentTitle("Low order volume")
        .setContentText("Only $count orders in the last hour (threshold $threshold).")
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)
        .build()

    try {
        nm.notify(NOTIF_ID_LOW_ORDERS, notif)
    } catch (_: SecurityException) {
        // Permission may have been revoked mid-flight; ignore gracefully.
    }
}
