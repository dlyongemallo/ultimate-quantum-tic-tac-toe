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
import net.yonge_mallo.uqttt.engine.ClassicalGameState
import net.yonge_mallo.uqttt.engine.ClassicalIllegalReason
import net.yonge_mallo.uqttt.engine.ClassicalMove
import net.yonge_mallo.uqttt.engine.ClassicalMoveResult
import net.yonge_mallo.uqttt.engine.ClassicalRules
import net.yonge_mallo.uqttt.engine.Player
import net.yonge_mallo.uqttt.engine.Square
import net.yonge_mallo.uqttt.engine.isBoardClosed

/**
 * Backing state for the classical game screen. Sibling to the quantum
 * `GameViewModel` -- simpler because classical play has no pair, no
 * pending collapse, and no auto-resolve: a single tap places a mark.
 *
 * Each `commit` of `current` pushes the pre-mutation state onto the
 * undo stack. A new commit clears the redo stack (history forks).
 */
class ClassicalGameViewModel(
    initial: ClassicalGameState,
    private val aiPlayers: Set<Player> = emptySet(),
) {
    var current: ClassicalGameState by mutableStateOf(initial)
        private set

    var illegalReason: ClassicalIllegalReason? by mutableStateOf(null)
        private set

    /**
     * Set true while the AI is computing a move. Public setter so the
     * screen's AI `LaunchedEffect` can flip it around its try / finally
     * without an extra wrapper method.
     */
    var thinking: Boolean by mutableStateOf(false)

    private val undoStack = mutableStateListOf<ClassicalGameState>()
    private val redoStack = mutableStateListOf<ClassicalGameState>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * Non-classical squares the user should be visually warned away
     * from. In classical play there is no first-endpoint selection to
     * distinguish, so the warning always reflects the sending-rule
     * constraint: with a live send, all squares outside the sent
     * mini-board are illegal; under free play, only squares inside a
     * closed mini-board are illegal.
     */
    val illegalEndpoints: Set<Square> by derivedStateOf {
        if (current.isGameOver) return@derivedStateOf emptySet()
        val target = current.effectiveRequiredBoard
        val result = mutableSetOf<Square>()
        for (board in 1..9) {
            val boardClosed = isBoardClosed(board, current.classical, current.wonBoards)
            val boardIsAllowed = target == null || target == board
            if (boardClosed || !boardIsAllowed) {
                for (position in 1..9) {
                    val sq = Square(board, position)
                    if (sq !in current.classical) result.add(sq)
                }
            }
        }
        result
    }

    /**
     * Single-tap placement. Tapping while the AI is thinking, a
     * classical square, after game over, or while it is an
     * AI-controlled player's turn is a silent no-op. The
     * AI-turn check backstops the `thinking` flag against a
     * small window between a state change committing (a human
     * move that hands control to the AI, undo/redo) and the
     * AI `LaunchedEffect` flipping `thinking = true`; in that
     * window a fast tap would otherwise play a move as the AI.
     */
    fun onSquareTap(square: Square) {
        if (thinking) return
        if (current.isGameOver) return
        if (activePlayer(current) in aiPlayers) return
        if (square in current.classical) return
        val move = ClassicalMove(current.nextMoveNumber, square)
        when (val result = ClassicalRules.apply(current, move)) {
            is ClassicalMoveResult.Legal -> commit(result.nextState)
            is ClassicalMoveResult.Illegal -> illegalReason = result.reason
        }
    }

    /**
     * Apply a move chosen by the AI. Trusts the AI proposed
     * something legal; an illegal proposal is a programmer error worth
     * crashing on.
     */
    fun applyAiMove(move: ClassicalMove) {
        when (val result = ClassicalRules.apply(current, move)) {
            is ClassicalMoveResult.Legal -> commit(result.nextState)
            is ClassicalMoveResult.Illegal -> error("AI proposed an illegal move: ${result.reason}")
        }
    }

    /** Pop one state off the undo stack, chaining past AI-controlled states. */
    fun undo() {
        if (undoStack.isEmpty()) return
        do {
            redoStack.add(current)
            current = undoStack.removeAt(undoStack.lastIndex)
        } while (undoStack.isNotEmpty() && activePlayer(current) in aiPlayers)
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        do {
            undoStack.add(current)
            current = redoStack.removeAt(redoStack.lastIndex)
        } while (redoStack.isNotEmpty() && activePlayer(current) in aiPlayers)
    }

    private fun activePlayer(state: ClassicalGameState): Player? = if (state.isGameOver) null else state.nextPlayer

    fun dismissIllegalReason() {
        illegalReason = null
    }

    /** Used by "Play again": fresh game state, no history. */
    fun reset(state: ClassicalGameState) {
        current = state
        illegalReason = null
        thinking = false
        undoStack.clear()
        redoStack.clear()
    }

    private fun commit(newState: ClassicalGameState) {
        undoStack.add(current)
        redoStack.clear()
        current = newState
    }
}
