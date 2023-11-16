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

package io.element.android.features.messages.impl.timeline.components.receipt.bottomsheet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.element.android.features.messages.impl.timeline.model.receipts
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.ModalBottomSheet
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.libraries.matrix.ui.components.MatrixUserRow
import io.element.android.libraries.theme.ElementTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadReceiptBottomSheetView(
    state: ReadReceiptBottomSheetState,
    modifier: Modifier = Modifier,
) {
    val isVisible = state.selectedEvent != null

    val sheetState = rememberModalBottomSheetState()
    val coroutineScope = rememberCoroutineScope()
    if (isVisible) {
        ModalBottomSheet(
            modifier = modifier,
//            modifier = modifier.navigationBarsPadding() - FIXME after https://issuetracker.google.com/issues/275849044
//                    .imePadding()
            sheetState = sheetState,
            onDismissRequest = {
                coroutineScope.launch {
                    sheetState.hide()
                    state.eventSink(ReadReceiptBottomSheetEvents.Dismiss)
                }
            }
        ) {
            ReadReceiptBottomSheetContents(
                state = state,
            )
            // FIXME remove after https://issuetracker.google.com/issues/275849044
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ColumnScope.ReadReceiptBottomSheetContents(
    state: ReadReceiptBottomSheetState,
) {
    val receipts = state.selectedEvent?.readReceiptState?.receipts().orEmpty()
    receipts.forEach {
        MatrixUserRow(
            matrixUser = MatrixUser(
                UserId(it.avatarData.id),
                it.avatarData.name,
                it.avatarData.url,
            ),
            avatarSize = AvatarSize.ReadReceiptList,
            trailingContent = {
                Text(
                    text = it.formattedDate,
                    style = ElementTheme.typography.fontBodySmRegular,
                    color = ElementTheme.colors.textSecondary,
                )
            }
        )
    }
}

@PreviewsDayNight
@Composable
internal fun ReadReceiptBottomSheetViewPreview(@PreviewParameter(ReadReceiptBottomSheetStateProvider::class) state: ReadReceiptBottomSheetState) = ElementPreview {
    // TODO restore RetrySendMessageMenuBottomSheet once the issue with bottom sheet not being previewable is fixed
    Column {
        ReadReceiptBottomSheetContents(
            state = state
        )
    }
}
