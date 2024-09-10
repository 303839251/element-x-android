/*
 * Copyright 2023, 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE in the repository root for full details.
 */

package io.element.android.libraries.oidc.api

sealed interface OidcAction {
    data object GoBack : OidcAction
    data class Success(val url: String) : OidcAction
}
