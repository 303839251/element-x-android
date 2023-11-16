/*
 * Copyright (c) 2023 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.element.android.features.messages.impl.timeline.model

import io.element.android.libraries.designsystem.components.avatar.AvatarData
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

sealed interface TimelineItemReadReceipts {
    /** Value when the feature is disabled */
    data object Hidden : TimelineItemReadReceipts

    data class ReadReceipts(
        val receipts: ImmutableList<ReadReceiptData>,
    ) : TimelineItemReadReceipts
}

data class ReadReceiptData(
    val avatarData: AvatarData,
    val formattedDate: String,
)

fun TimelineItemReadReceipts.receipts(): ImmutableList<ReadReceiptData> = when (this) {
    TimelineItemReadReceipts.Hidden -> persistentListOf()
    is TimelineItemReadReceipts.ReadReceipts -> receipts
}
