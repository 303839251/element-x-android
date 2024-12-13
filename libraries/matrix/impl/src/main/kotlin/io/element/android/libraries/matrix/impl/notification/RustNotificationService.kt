/*
 * Copyright 2023, 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.notification

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.notification.NotificationData
import io.element.android.libraries.matrix.api.notification.NotificationService
import io.element.android.services.toolbox.api.systemclock.SystemClock
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.NotificationClient
import org.matrix.rustcomponents.sdk.use

class RustNotificationService(
    private val notificationClient: NotificationClient,
    private val dispatchers: CoroutineDispatchers,
    private val context: Context,
    clock: SystemClock,
) : NotificationService {
    private val notificationMapper: NotificationMapper = NotificationMapper(clock)

    override suspend fun getNotification(
        roomId: RoomId,
        eventId: EventId,
    ): Result<NotificationData?> = withContext(dispatchers.io) {
        runCatching {
            val item = notificationClient.getNotification(roomId.value, eventId.value)
            item?.use {
                val notificationData = notificationMapper.map(eventId, roomId, it)
                showNotification(notificationData)
                notificationData
            }
        }
    }

    /**
     * 显示通知
     */
    private fun showNotification(notificationData: NotificationData) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notification = NotificationCompat.Builder(context, "default_channel_id")
            .setSmallIcon(android.R.drawable.ic_notification_overlay)
            .setContentTitle(notificationData.roomDisplayName ?: "New Message")
            .setContentText(notificationData.content.toString())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(notificationData.eventId.hashCode(), notification)
    }
}

