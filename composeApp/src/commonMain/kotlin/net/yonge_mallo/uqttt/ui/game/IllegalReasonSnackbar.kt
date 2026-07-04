/*
 * Copyright 2026 David Yonge-Mallo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.yonge_mallo.uqttt.ui.game

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * `LaunchedEffect` that, whenever the view-model surfaces an
 * illegal-move reason, shows it in the given `SnackbarHostState` and
 * fires the dismissal callback so the reason resets to `null`.
 * Generic over the reason type so quantum's `IllegalReason` and
 * classical's `ClassicalIllegalReason` share the same wiring; each
 * screen passes its own `displayMessage` mapper.
 */
@Composable
fun <T : Any> IllegalReasonSnackbar(
    reason: T?,
    message: (T) -> String,
    onDismiss: () -> Unit,
    host: SnackbarHostState,
) {
    LaunchedEffect(reason) {
        if (reason != null) {
            host.showSnackbar(message = message(reason))
            onDismiss()
        }
    }
}
