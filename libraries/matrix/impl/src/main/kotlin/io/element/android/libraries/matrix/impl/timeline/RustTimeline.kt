/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.timeline

import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.ProgressCallback
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.media.AudioInfo
import io.element.android.libraries.matrix.api.media.FileInfo
import io.element.android.libraries.matrix.api.media.ImageInfo
import io.element.android.libraries.matrix.api.media.MediaUploadHandler
import io.element.android.libraries.matrix.api.media.VideoInfo
import io.element.android.libraries.matrix.api.poll.PollKind
import io.element.android.libraries.matrix.api.room.IntentionalMention
import io.element.android.libraries.matrix.api.room.MatrixRoom
import io.element.android.libraries.matrix.api.room.isDm
import io.element.android.libraries.matrix.api.room.location.AssetType
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.ReceiptType
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.TimelineException
import io.element.android.libraries.matrix.api.timeline.item.event.EventOrTransactionId
import io.element.android.libraries.matrix.api.timeline.item.event.InReplyTo
import io.element.android.libraries.matrix.impl.core.toProgressWatcher
import io.element.android.libraries.matrix.impl.media.MediaUploadHandlerImpl
import io.element.android.libraries.matrix.impl.media.map
import io.element.android.libraries.matrix.impl.media.toMSC3246range
import io.element.android.libraries.matrix.impl.poll.toInner
import io.element.android.libraries.matrix.impl.room.RoomContentForwarder
import io.element.android.libraries.matrix.impl.room.location.toInner
import io.element.android.libraries.matrix.impl.timeline.item.event.EventTimelineItemMapper
import io.element.android.libraries.matrix.impl.timeline.item.event.TimelineEventContentMapper
import io.element.android.libraries.matrix.impl.timeline.item.virtual.VirtualTimelineItemMapper
import io.element.android.libraries.matrix.impl.timeline.postprocessor.LastForwardIndicatorsPostProcessor
import io.element.android.libraries.matrix.impl.timeline.postprocessor.LoadingIndicatorsPostProcessor
import io.element.android.libraries.matrix.impl.timeline.postprocessor.RoomBeginningPostProcessor
import io.element.android.libraries.matrix.impl.timeline.postprocessor.TypingNotificationPostProcessor
import io.element.android.libraries.matrix.impl.timeline.reply.InReplyToMapper
import io.element.android.libraries.matrix.impl.util.MessageEventContent
import io.element.android.services.toolbox.api.systemclock.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.EditedContent
import org.matrix.rustcomponents.sdk.FormattedBody
import org.matrix.rustcomponents.sdk.MessageFormat
import org.matrix.rustcomponents.sdk.PollData
import org.matrix.rustcomponents.sdk.SendAttachmentJoinHandle
import org.matrix.rustcomponents.sdk.use
import timber.log.Timber
import uniffi.matrix_sdk_ui.LiveBackPaginationStatus
import java.io.File
import org.matrix.rustcomponents.sdk.EventOrTransactionId as RustEventOrTransactionId
import org.matrix.rustcomponents.sdk.Timeline as InnerTimeline

private const val PAGINATION_SIZE = 50

/**
 * RustTimeline handles the synchronization and interaction with the inner timeline.
 */
class RustTimeline(
    private val inner: InnerTimeline,
    mode: Timeline.Mode,
    systemClock: SystemClock,
    private val matrixRoom: MatrixRoom,
    private val coroutineScope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val roomContentForwarder: RoomContentForwarder,
    private val featureFlagsService: FeatureFlagService,
    onNewSyncedEvent: () -> Unit,
) : Timeline {
    private val initLatch = CompletableDeferred<Unit>()
    private val isTimelineInitialized = MutableStateFlow(false)

    private val _timelineItems: MutableSharedFlow<List<MatrixTimelineItem>> =
        MutableSharedFlow(replay = 1, extraBufferCapacity = Int.MAX_VALUE)

    private val timelineEventContentMapper = TimelineEventContentMapper()
    private val inReplyToMapper = InReplyToMapper(timelineEventContentMapper)
    private val timelineItemMapper = MatrixTimelineItemMapper(
        fetchDetailsForEvent = this::fetchDetailsForEvent,
        coroutineScope = coroutineScope,
        virtualTimelineItemMapper = VirtualTimelineItemMapper(),
        eventTimelineItemMapper = EventTimelineItemMapper(
            contentMapper = timelineEventContentMapper
        )
    )
    private val timelineDiffProcessor = MatrixTimelineDiffProcessor(
        timelineItems = _timelineItems,
        timelineItemFactory = timelineItemMapper,
    )
    private val timelineItemsSubscriber = TimelineItemsSubscriber(
        timeline = inner,
        timelineCoroutineScope = coroutineScope,
        timelineDiffProcessor = timelineDiffProcessor,
        initLatch = initLatch,
        isTimelineInitialized = isTimelineInitialized,
        dispatcher = dispatcher,
        onNewSyncedEvent = onNewSyncedEvent,
    )

    init {
        coroutineScope.fetchMembers()
        if (mode == Timeline.Mode.LIVE) {
            coroutineScope.registerBackPaginationStatusListener()
        }
    }

    private fun CoroutineScope.registerBackPaginationStatusListener() {
        inner.liveBackPaginationStatus()
            .onEach { backPaginationStatus ->
                updatePaginationStatus(Timeline.PaginationDirection.BACKWARDS) {
                    when (backPaginationStatus) {
                        is LiveBackPaginationStatus.Idle -> it.copy(isPaginating = false, hasMoreToLoad = !backPaginationStatus.hitStartOfTimeline)
                        is LiveBackPaginationStatus.Paginating -> it.copy(isPaginating = true, hasMoreToLoad = true)
                    }
                }
            }
            .launchIn(this)
    }

    override fun close() {
        coroutineScope.cancel()
        inner.close()
    }

    private fun CoroutineScope.fetchMembers() = launch(dispatcher) {
        initLatch.await()
        try {
            inner.fetchMembers()
        } catch (exception: Exception) {
            Timber.e(exception, "Error fetching members for room ${matrixRoom.roomId}")
        }
    }
}
