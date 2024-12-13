/*
 * Copyright 2023, 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.sync

import io.element.android.libraries.matrix.api.sync.SyncService
import io.element.android.libraries.matrix.api.sync.SyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.SyncServiceState
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import org.matrix.rustcomponents.sdk.SyncService as InnerSyncService

class RustSyncService(
    private val innerSyncService: InnerSyncService,
    sessionCoroutineScope: CoroutineScope
) : SyncService {
    private val isServiceReady = AtomicBoolean(true)
    private lateinit var timelineItemsSubscriber: TimelineItemsSubscriber 

    init {
        // 初始化 TimelineItemsSubscriber 并传入 onNewSyncedEvent 回调
        timelineItemsSubscriber = TimelineItemsSubscriber(
            context = context,
            timelineCoroutineScope = sessionCoroutineScope,
            dispatcher = sessionCoroutineScope.coroutineContext[CoroutineDispatcher] ?: Dispatchers.Default,
            timeline = timeline,
            timelineDiffProcessor = timelineDiffProcessor,
            initLatch = CompletableDeferred(),
            isTimelineInitialized = MutableStateFlow(false),
            // 新同步事件时触发的回调逻辑
            onNewSyncedEvent = {
                Timber.i("New event synced!")
                // 在这里显示通知
                notificationService.getNotification("roomId", "eventId").onSuccess { notificationData ->
                    notificationData?.let {
                        showNotification(it)
                    }
                }
            },
            notificationService = notificationService
        )
    }

    override suspend fun startSync(): Result<Unit> = runCatching {
        if (!isServiceReady.get()) {
            Timber.d("Can't start sync: service is not ready")
            return@runCatching
        }
        Timber.i("Start sync")
        innerSyncService.start()

        // 启动同步时确保时间线订阅
        timelineItemsSubscriber.subscribeIfNeeded()
    }.onFailure {
        Timber.d("Start sync failed: $it")
    }

    override suspend fun stopSync(): Result<Unit> = runCatching {
        if (!isServiceReady.get()) {
            Timber.d("Can't stop sync: service is not ready")
            return@runCatching
        }
        Timber.i("Stop sync")
        innerSyncService.stop()

        // 停止同步时确保取消订阅
        timelineItemsSubscriber.unsubscribeIfNeeded()
    }.onFailure {
        Timber.d("Stop sync failed: $it")
    }

    suspend fun destroy() = withContext(NonCancellable) {
        // If the service was still running, stop it
        stopSync()
        Timber.d("Destroying sync service")
        isServiceReady.set(false)
    }

    override val syncState: StateFlow<SyncState> =
        innerSyncService.stateFlow()
            .map(SyncServiceState::toSyncState)
            .onEach { state ->
                Timber.i("Sync state=$state")
            }
            .distinctUntilChanged()
            .stateIn(sessionCoroutineScope, SharingStarted.Eagerly, SyncState.Idle)
}
