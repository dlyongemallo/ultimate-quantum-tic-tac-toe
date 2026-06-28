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

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import net.yonge_mallo.uqttt.engine.GameState
import net.yonge_mallo.uqttt.engine.Square

/**
 * Centres a square `BoardCanvas` inside whatever area the caller hands
 * us, sized to the smaller of the available width and height.
 * Extracted so the same wrapper works in both the full-screen and the
 * split (board + collapse picker) layouts in `GameScreen`.
 */
@Composable
fun BoardArea(
    state: GameState,
    selection: Square?,
    onSquareTap: (Square) -> Unit,
    modifier: Modifier = Modifier,
    illegalEndpoints: Set<Square> = emptySet(),
    highlightedSquares: Set<Square> = emptySet(),
    indicatorSquare: Square? = null,
    sentBoards: Pair<Int, Int>? = null,
) {
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val side = minOf(maxWidth, maxHeight)
        BoardCanvas(
            state = state,
            selection = selection,
            onSquareTap = onSquareTap,
            modifier = Modifier.size(side),
            illegalEndpoints = illegalEndpoints,
            highlightedSquares = highlightedSquares,
            indicatorSquare = indicatorSquare,
            sentBoards = sentBoards,
        )
    }
}
