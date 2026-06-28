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
import net.yonge_mallo.uqttt.engine.GameState
import net.yonge_mallo.uqttt.engine.Player

/**
 * The dialog title for a terminal `state`, exposed (rather than
 * inlined into the composable) so headless tests can pin down the
 * mapping without spinning up a Compose UI host.
 */
internal fun gameOverOutcomeText(state: GameState): String =
    when {
        state.isDraw -> "Draw"
        state.isSharedWin -> "Shared win"
        state.winner == Player.X -> "X wins"
        state.winner == Player.O -> "O wins"
        else -> "Game over"
    }

/**
 * Final modal. Outcome falls out of `GameState`'s computed properties.
 * Buttons: Undo rewinds the move that ended the game (always available
 * here -- you cannot reach game-over without having committed at
 * least one move, which the undo stack records); Play again resets
 * the board state; Main menu pops to the opening screen. After Undo
 * the game is no longer over and the dialog auto-dismisses.
 */
@Composable
fun GameOverDialog(
    state: GameState,
    onUndo: () -> Unit,
    onPlayAgain: () -> Unit,
    onMainMenu: () -> Unit,
) {
    val outcome = gameOverOutcomeText(state)
    AlertDialog(
        onDismissRequest = {},
        title = { Text(outcome) },
        text = { Text("The game is over.") },
        // Same Row trick as `CollapsePicker`: keep declaration order.
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onUndo) { Text("Undo") }
                TextButton(onClick = onPlayAgain) { Text("Play again") }
                TextButton(onClick = onMainMenu) { Text("Main menu") }
            }
        },
    )
}
