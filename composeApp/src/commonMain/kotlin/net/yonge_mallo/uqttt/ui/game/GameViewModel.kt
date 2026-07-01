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

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.yonge_mallo.uqttt.engine.CollapseChoice
import net.yonge_mallo.uqttt.engine.GameState
import net.yonge_mallo.uqttt.engine.IllegalReason
import net.yonge_mallo.uqttt.engine.Move
import net.yonge_mallo.uqttt.engine.MoveResult
import net.yonge_mallo.uqttt.engine.Rules
import net.yonge_mallo.uqttt.engine.Square
import net.yonge_mallo.uqttt.engine.Variant

/**
 * Backing state for the game screen. Holds the current `GameState`, the
 * in-progress first-endpoint `selection`, and the most recent
 * `illegalReason` (consumed by the snackbar).
 */
class GameViewModel(initial: GameState) {
    var current: GameState by mutableStateOf(initial)
        private set

    var selection: Square? by mutableStateOf(null)
        private set

    var illegalReason: IllegalReason? by mutableStateOf(null)
        private set

    /**
     * Non-classical squares that the user should be visually warned away
     * from on the *current* tap. When a first endpoint is already
     * selected the warning covers squares that wouldn't form a legal
     * pair with it; before any selection the warning covers squares
     * that couldn't be the first endpoint of any legal pair (currently
     * only the sending variant produces a non-empty pre-selection set
     * -- the previous move's send constraint locks the first endpoint
     * into one or two specific mini-boards). Classical squares are
     * excluded throughout because they're already visually distinct
     * via their X / O glyph. Empty during a pending collapse or once
     * the game is over. `derivedStateOf` caches across recomposition.
     */
    val illegalEndpoints: Set<Square> by derivedStateOf {
        if (current.pendingCollapse != null || current.isGameOver) {
            return@derivedStateOf emptySet()
        }
        val first = selection
        if (first == null) {
            // Pre-selection warning: only meaningful when the sending
            // variant is constraining the first endpoint to specific
            // mini-boards. Squares outside those mini-boards can't be
            // a first endpoint of any legal pair, so hashing them is
            // the same kind of "don't tap here" hint the post-selection
            // pass already gives.
            if (current.variant != Variant.ULTIMATE_QUANTUM_TIC_TAC_TOE) {
                return@derivedStateOf emptySet()
            }
            val required = current.requiredBoards ?: return@derivedStateOf emptySet()
            if (!Rules.hasLegalMoveWithin(current, required)) return@derivedStateOf emptySet()
            val (b, d) = required
            candidateSquares(current.variant)
                .asSequence()
                .filter { it !in current.classical }
                .filter { it.board != b && it.board != d }
                .toSet()
        } else {
            candidateSquares(current.variant)
                .asSequence()
                .filter { it != first && it !in current.classical }
                .filter {
                    Rules.apply(current, Move(current.nextMoveNumber, first, it)) is MoveResult.Illegal
                }
                .toSet()
        }
    }

    /**
     * Two-stage selection state machine. Tapping the already-selected
     * square deselects it; tapping a classical square or tapping while a
     * collapse is pending / the game is over is a silent no-op. A
     * successful second tap submits the move via `Rules.apply`.
     */
    fun onSquareTap(square: Square) {
        if (current.pendingCollapse != null || current.isGameOver) return
        if (square in current.classical) return

        val first = selection
        if (first == null) {
            // Pre-selection legality check. When the sending rule is
            // active, any square outside the required mini-boards is
            // already hashed as illegal -- ignore taps on those so the
            // user can't enter a state where every second endpoint is
            // illegal too. `illegalEndpoints` returns the pre-selection
            // set when `selection` is null, so a single membership test
            // covers it.
            if (square in illegalEndpoints) return
            selection = square
            return
        }
        if (first == square) {
            selection = null
            return
        }

        val move = Move(current.nextMoveNumber, first, square)
        when (val result = Rules.apply(current, move)) {
            is MoveResult.Legal -> commit(result.nextState)
            is MoveResult.TriggersCollapse -> commit(result.pendingState)
            is MoveResult.Illegal -> {
                illegalReason = result.reason
                selection = null
            }
        }
    }

    fun resolveCollapse(choice: CollapseChoice) {
        if (current.pendingCollapse == null) return
        commit(Rules.resolve(current, choice))
    }

    fun dismissIllegalReason() {
        illegalReason = null
    }

    /** Used by "Play again": fresh game state. */
    fun reset(state: GameState) {
        current = state
        selection = null
        illegalReason = null
    }

    private fun commit(newState: GameState) {
        // When both collapse choices lead to the same classical map there
        // is nothing for the chooser to decide; skip the dialog and jump
        // straight to the resolved state.
        val pending = newState.pendingCollapse
        current =
            if (pending != null && hasIdenticalOutcomes(pending.choices)) {
                Rules.resolve(newState, pending.choices.first())
            } else {
                newState
            }
        selection = null
    }

    private fun hasIdenticalOutcomes(choices: List<CollapseChoice>): Boolean {
        if (choices.size < 2) return true
        val reference = choices.first().outcomes()
        return choices.drop(1).all { it.outcomes() == reference }
    }

    private fun candidateSquares(variant: Variant): List<Square> =
        when (variant) {
            Variant.QUANTUM_TIC_TAC_TOE -> (1..9).map { Square(1, it) }
            Variant.QUANTUM_TIC_TAC_TOE_SQUARED,
            Variant.ULTIMATE_QUANTUM_TIC_TAC_TOE,
            ->
                (1..9).flatMap { b -> (1..9).map { p -> Square(b, p) } }
        }
}
