/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.timeline

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import io.element.android.libraries.core.coroutine.childScope
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

/**
 * This class is responsible for subscribing to a timeline and posting the items/diffs to the timelineDiffProcessor.
 * It will also trigger a callback when a new synced event is received.
 * It will also handle the initial items and make sure they are posted before any diff.
 */
internal class TimelineItemsSubscriber(
    // 添加 Context 参数
    private val context: Context, 
    timelineCoroutineScope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    private val timeline: Timeline,
    private val timelineDiffProcessor: MatrixTimelineDiffProcessor,
    private val initLatch: CompletableDeferred<Unit>,
    private val isTimelineInitialized: MutableStateFlow<Boolean>,
    private val onNewSyncedEvent: () -> Unit,
    // 添加 NotificationService 参数
    private val notificationService: NotificationService, 
) {
    private var subscriptionCount = 0
    private val mutex = Mutex()

    private val coroutineScope = timelineCoroutineScope.childScope(dispatcher, "TimelineItemsSubscriber")

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
            // Makes sure to post empty list if there is no item, so you can handle empty state.
            timelineDiffProcessor.postItems(emptyList())
        } else {
            // Split the initial items in multiple lists as there is no pagination in the cached data, so we can post timelineItems asap.
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
                // Keep using the postItems logic so we can post the timelineItems asap.
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
        val notification = NotificationCompat.Builder(context, "default_channel_id")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(data.roomDisplayName ?: "New Message")
            .setContentText(data.content.toString()) // 格式化通知内容
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(data.eventId.hashCode(), notification)
    }
}

