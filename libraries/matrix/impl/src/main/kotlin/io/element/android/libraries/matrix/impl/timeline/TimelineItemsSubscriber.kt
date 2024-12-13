/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.timeline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import io.element.android.libraries.core.coroutine.childScope
import io.element.android.libraries.matrix.api.notification.NotificationData
import io.element.android.libraries.matrix.api.notification.NotificationService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineChange
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import uniffi.matrix_sdk_ui.EventItemOrigin

private const val INITIAL_MAX_SIZE = 50
private const val NOTIFICATION_CHANNEL_ID = "default_channel_id"
private const val NOTIFICATION_CHANNEL_NAME = "Default Notifications"

/**
 * This class is responsible for subscribing to a timeline and posting the items/diffs to the timelineDiffProcessor.
 * It will also trigger a callback when a new synced event is received.
 * It will also handle the initial items and make sure they are posted before any diff.
 */
internal class TimelineItemsSubscriber(
    // Added Context parameter
    private val context: Context,
    timelineCoroutineScope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    private val timeline: Timeline,
    private val timelineDiffProcessor: MatrixTimelineDiffProcessor,
    private val initLatch: CompletableDeferred<Unit>,
    private val isTimelineInitialized: MutableStateFlow<Boolean>,
    private val onNewSyncedEvent: () -> Unit,
    // Added NotificationService parameter
    private val notificationService: NotificationService,
) {
    private var subscriptionCount = 0
    private val mutex = Mutex()

    private val coroutineScope = timelineCoroutineScope.childScope(dispatcher, "TimelineItemsSubscriber")

    init {
        createNotificationChannel()
    }

    /**
     * Add a subscription to the timeline and start posting items/diffs to the timelineDiffProcessor.
     * It will also trigger a callback when a new synced event is received.
     */
    suspend fun subscribeIfNeeded() = mutex.withLock {
        if (subscriptionCount == 0) {
            timeline.timelineDiffFlow()
                .onEach { diffs ->
                    if (diffs.any { diff -> diff.eventOrigin() == EventItemOrigin.SYNC }) {
                        onNewSyncedEvent()
                        handleNewMessages(diffs)
                    }
                    postDiffs(diffs)
                }
                .launchIn(coroutineScope)
        }
        subscriptionCount++
    }

    /**
     * Remove a subscription to the timeline and unsubscribe if needed.
     * The timeline will be unsubscribed when the last subscription is removed.
     * If the timelineCoroutineScope is cancelled, the timeline will be unsubscribed automatically.
     */
    suspend fun unsubscribeIfNeeded() = mutex.withLock {
        when (subscriptionCount) {
            0 -> return@withLock
            1 -> {
                coroutineScope.coroutineContext.cancelChildren()
            }
        }
        subscriptionCount--
    }

    private suspend fun postItems(items: List<TimelineItem>) = coroutineScope {
        if (items.isEmpty()) {
            timelineDiffProcessor.postItems(emptyList())
        } else {
            items.chunked(INITIAL_MAX_SIZE).reversed().forEach {
                ensureActive()
                timelineDiffProcessor.postItems(it)
            }
        }
        isTimelineInitialized.value = true
        initLatch.complete(Unit)
    }

    private suspend fun postDiffs(diffs: List<TimelineDiff>) {
        val diffsToProcess = diffs.toMutableList()
        if (!isTimelineInitialized.value) {
            val resetDiff = diffsToProcess.firstOrNull { it.change() == TimelineChange.RESET }
            if (resetDiff != null) {
                postItems(resetDiff.reset() ?: emptyList())
                diffsToProcess.remove(resetDiff)
            }
        }
        initLatch.await()
        if (diffsToProcess.isNotEmpty()) {
            timelineDiffProcessor.postDiffs(diffsToProcess)
        }
    }

    /**
     * Handle new messages and trigger notifications.
     */
    private fun handleNewMessages(diffs: List<TimelineDiff>) {
        val newEvents = diffs.filter { it.eventOrigin() == EventItemOrigin.SYNC }
        newEvents.forEach { diff ->
            val eventId = diff.eventId()
            val roomId = diff.roomId()
            if (eventId != null && roomId != null) {
                notificationService.getNotification(roomId, eventId).onSuccess { notificationData ->
                    notificationData?.let {
                        showNotification(it)
                    }
                }
            }
        }
    }

    /**
     * Display a notification for the given NotificationData.
     */
    private fun showNotification(data: NotificationData) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(data.roomDisplayName ?: "New Message")
            .setContentText(data.content.toString())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(data.eventId.hashCode(), notification)
    }

    /**
     * Create a notification channel for Android O and above.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            )
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
