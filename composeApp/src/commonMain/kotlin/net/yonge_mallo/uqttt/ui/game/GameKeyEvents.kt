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

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Shared keyboard-shortcut handling for the game screens.  In normal
 * play Ctrl+Z / Ctrl+Y / Ctrl+Shift+Z drive undo / redo; in demo
 * (AI-vs-AI) mode those are swallowed instead -- a single undo click
 * would drain the whole history (the view-models chain past AI
 * states) -- and Space toggles pause / resume, matching the
 * universal video-playback convention.  Extracted so both
 * `GameScreen` and `ClassicalGameScreen` share one implementation
 * and a change (like the Space binding) only lands once.
 */
fun Modifier.gameKeyEvents(
    demoMode: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onTogglePause: () -> Unit,
): Modifier =
    onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        // Space toggles pause / resume in demo mode.  Checked before
        // the Ctrl gate below because it has no modifier requirement.
        if (demoMode && event.key == Key.Spacebar) {
            onTogglePause()
            return@onKeyEvent true
        }
        if (!event.isCtrlPressed) return@onKeyEvent false
        // Ctrl+... -- undo / redo path, swallowed in demo (see
        // top-bar behaviour).
        if (demoMode) return@onKeyEvent false
        when (event.key) {
            Key.Z -> {
                if (event.isShiftPressed) onRedo() else onUndo()
                true
            }
            Key.Y -> {
                onRedo()
                true
            }
            else -> false
        }
    }
