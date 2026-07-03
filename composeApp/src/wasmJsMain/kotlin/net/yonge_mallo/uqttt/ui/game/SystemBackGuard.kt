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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.browser.window
import org.w3c.dom.events.Event

/**
 * Browser back-button intercept. While mounted, the browser stays one
 * "sentinel" `history` entry above the entry that was current when the
 * guard was mounted. When the user presses browser back, the browser
 * pops us off the sentinel; we immediately push a fresh sentinel back
 * on so we're re-armed, then invoke `onBackRequested` (which the game
 * screens wire to their "Leave game?" confirmation dialog).
 *
 * On dispose the guard pops its own sentinel with `history.back()` so
 * a normal exit (in-app Back button, game-over "Main menu") leaves no
 * stray forward-history entry. If the exit was itself triggered by a
 * browser back that was confirmed via the dialog, we're already at the
 * pre-game entry -- so we suppress the pop in that case to avoid
 * accidentally navigating away from the app.
 */
@Composable
actual fun SystemBackGuard(onBackRequested: () -> Unit) {
    val currentOnBack = rememberUpdatedState(onBackRequested)
    DisposableEffect(Unit) {
        window.history.pushState(null, "", null)
        var atSentinel = true
        val listener: (Event) -> Unit = {
            // Browser popped our sentinel; we're now at the pre-game
            // entry. Push a fresh sentinel so a second press is also
            // caught, then ask the app to prompt the user.
            window.history.pushState(null, "", null)
            atSentinel = true
            currentOnBack.value()
        }
        window.addEventListener("popstate", listener)
        onDispose {
            window.removeEventListener("popstate", listener)
            if (atSentinel) {
                // Undo the pushState we did on mount so we don't leave
                // a dead entry in forward-history.
                window.history.back()
            }
        }
    }
}
