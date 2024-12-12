/*
 * Copyright 2023, 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.notification

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
                notificationMapper.map(eventId, roomId, it)
            }
        }
    }
    // 更新 NotificationMapper 映射逻辑
    private val notificationMapper: NotificationMapper = NotificationMapper(clock)

    fun map(eventId: EventId, roomId: RoomId, notificationItem: NotificationItem): NotificationData {
        return NotificationData(
            eventId = eventId,
            roomId = roomId,
            senderAvatarUrl = notificationItem.senderAvatarUrl,
            senderDisplayName = notificationItem.senderDisplayName,
            senderIsNameAmbiguous = notificationItem.senderIsNameAmbiguous,
            roomAvatarUrl = notificationItem.roomAvatarUrl,
            roomDisplayName = notificationItem.roomDisplayName,
            isDirect = notificationItem.isDirect,
            isDm = notificationItem.isDm,
            isEncrypted = notificationItem.isEncrypted,
            isNoisy = notificationItem.isNoisy,
            timestamp = notificationItem.timestamp,
            content = mapContent(notificationItem),
            hasMention = notificationItem.hasMention
        )
    }
}
