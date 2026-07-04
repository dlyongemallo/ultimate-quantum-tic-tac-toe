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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * "Leave game?" confirmation shown when the in-app Back button or a
 * platform back gesture is used mid-game.  The user's already-issued
 * Back is treated as the confirming action, so Leave sits on the
 * right (Material's primary-button slot) and Stay cancels on the
 * left.  Extracted so both game screens share one dialog.
 */
@Composable
fun BackConfirmDialog(
    onStay: () -> Unit,
    onLeave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onStay,
        title = { Text("Leave game?") },
        text = { Text("The current game will be lost.") },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onStay) { Text("Stay") }
                TextButton(onClick = onLeave) { Text("Leave") }
            }
        },
    )
}
