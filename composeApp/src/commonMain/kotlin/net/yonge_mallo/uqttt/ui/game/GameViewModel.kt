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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.yonge_mallo.uqttt.engine.CollapseChoice
import net.yonge_mallo.uqttt.engine.GameState
import net.yonge_mallo.uqttt.engine.IllegalReason
import net.yonge_mallo.uqttt.engine.Move
import net.yonge_mallo.uqttt.engine.MoveResult
import net.yonge_mallo.uqttt.engine.Player
import net.yonge_mallo.uqttt.engine.Rules
import net.yonge_mallo.uqttt.engine.Square
import net.yonge_mallo.uqttt.engine.Variant

/**
 * Backing state for the game screen. Holds the current `GameState`, the
 * in-progress first-endpoint `selection`, the most recent `illegalReason`
 * (consumed by the snackbar), and undo / redo stacks.
 *
 * Each mutation of `current` (a Legal move, a collapse-triggering move,
 * and a collapse resolution) pushes the pre-mutation state to the undo
 * stack. Collapse-and-resolve is therefore two undo steps: the user
 * can undo just the resolution and pick the other choice without
 * rewinding the move itself. Any new commit clears the redo stack
 * (history forks).
 */
class GameViewModel(
    initial: GameState,
    private val aiPlayers: Set<Player> = emptySet(),
) {
    var current: GameState by mutableStateOf(initial)
        private set

    /**
     * Monotonically increasing counter bumped on every mutation of
     * `current` (commit, reset, undo, redo). Used by `GameScreen`'s AI
     * `LaunchedEffect` as a freshness token: the coroutine captures
     * this at launch and refuses to apply its result if it no longer
     * matches. Structural equality on `current` isn't enough on its
     * own -- a reset to a structurally-identical initial state, or an
     * undo/redo cycle that lands on a structurally-identical position,
     * would otherwise let a stale AI move slip through.
     */
    var generation: Int by mutableStateOf(0)
        private set

    var selection: Square? by mutableStateOf(null)
        private set

    var illegalReason: IllegalReason? by mutableStateOf(null)
        private set

    /**
     * Set true while the AI is computing a move or collapse choice.
     * Public setter so the GameScreen's AI `LaunchedEffect` can flip it
     * around its try / finally without an extra wrapper method.
     */
    var thinking: Boolean by mutableStateOf(false)

    // SnapshotStateList drives `canUndo` / `canRedo` recomposition.
    private val undoStack = mutableStateListOf<GameState>()
    private val redoStack = mutableStateListOf<GameState>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

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
        if (thinking) return
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

    /**
     * Apply a move chosen by the AI. Bypasses the selection state
     * machine and trusts that the AI proposed something legal; an
     * illegal proposal is a programmer error worth crashing on.
     */
    fun applyAiMove(move: Move) {
        when (val result = Rules.apply(current, move)) {
            is MoveResult.Legal -> commit(result.nextState)
            is MoveResult.TriggersCollapse -> commit(result.pendingState)
            is MoveResult.Illegal -> error("AI proposed an illegal move: ${result.reason}")
        }
    }

    /**
     * Pop one state off the undo stack -- and keep popping while the
     * resulting state's active player is an AI, since the
     * `LaunchedEffect` in `GameScreen` would otherwise just re-run the
     * AI and put the same move back. Stops at any human-controllable
     * state, or when the stack is empty.
     */
    fun undo() {
        if (undoStack.isEmpty()) return
        do {
            redoStack.add(current)
            current = undoStack.removeAt(undoStack.lastIndex)
        } while (undoStack.isNotEmpty() && activePlayer(current) in aiPlayers)
        selection = null
        generation++
    }

    /** Mirror of `undo`: chains through recorded AI states so a single redo lands on a human-controllable state. */
    fun redo() {
        if (redoStack.isEmpty()) return
        do {
            undoStack.add(current)
            current = redoStack.removeAt(redoStack.lastIndex)
        } while (redoStack.isNotEmpty() && activePlayer(current) in aiPlayers)
        selection = null
        generation++
    }

    private fun activePlayer(state: GameState): Player? {
        val pending = state.pendingCollapse
        return when {
            state.isGameOver -> null
            pending != null -> pending.chooser
            else -> state.nextPlayer
        }
    }

    fun dismissIllegalReason() {
        illegalReason = null
    }

    /** Used by "Play again": fresh game state, no history. */
    fun reset(state: GameState) {
        current = state
        selection = null
        illegalReason = null
        thinking = false
        undoStack.clear()
        redoStack.clear()
        generation++
    }

    private fun commit(newState: GameState) {
        // When both collapse choices lead to the same classical map there
        // is nothing for the chooser to decide; skip the dialog and jump
        // straight to the resolved state. The intermediate pending
        // state never enters the undo stack, so a single undo rewinds
        // past both the move and the auto-resolution (otherwise a
        // second undo would re-enter the pending state and immediately
        // auto-resolve again, looping).
        val pending = newState.pendingCollapse
        val nextCurrent =
            if (pending != null && hasIdenticalOutcomes(pending.choices)) {
                Rules.resolve(newState, pending.choices.first())
            } else {
                newState
            }
        undoStack.add(current)
        redoStack.clear()
        current = nextCurrent
        selection = null
        generation++
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
