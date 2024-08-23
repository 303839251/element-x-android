/*
 * Copyright (c) 2024 New Vector Ltd
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

package io.element.android.features.call.impl.utils

import android.webkit.WebView
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class WebViewWebPipApi(
    private val webView: WebView,
) : WebPipApi {
    override suspend fun canEnterPip(): Boolean {
        return suspendCoroutine { continuation ->
            webView.evaluateJavascript("controls.canEnterPip()") { result ->
                // Note if the method is not available, it will return "null"
                continuation.resume(result == "true" || result == "null")
            }
        }
    }

    override fun enterPip() {
        webView.evaluateJavascript("controls.enablePip()", null)
    }

    override fun exitPip() {
        webView.evaluateJavascript("controls.disablePip()", null)
    }
}
