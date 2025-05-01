package io.element.android.x.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object NotificationUtils {
    // 系统通知渠道
    const val SYSTEM_CHANNEL_ID = "element_system_priority"
    const val PERSISTENT_NOTIFICATION_ID = 1001

    fun initSystemChannel(context: Context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SYSTEM_CHANNEL_ID,
                "System Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical notifications for messages and calls"
            }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                .createNotificationChannel(channel)
        }
    }

    fun showSystemNotification(context: Context, title: String, message: String) {
        NotificationCompat.Builder(context, SYSTEM_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_element_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
            .let { notification ->
                NotificationManagerCompat.from(context)
                    .notify(System.currentTimeMillis().toInt(), notification)
            }
    }
}