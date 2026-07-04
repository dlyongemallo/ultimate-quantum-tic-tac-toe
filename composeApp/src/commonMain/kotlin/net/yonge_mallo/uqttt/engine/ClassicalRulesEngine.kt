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

// Classical Ultimate Tic-Tac-Toe -- rules engine.
//
// Sibling to `RulesEngine.kt`. Owns the single classical variant
// (`Variant.ULTIMATE_TIC_TAC_TOE`); every other variant belongs to the
// quantum engine. The two engines share the `Variant` enum, the
// `Square` / `Player` primitives, and the win-detection helpers
// (`BOARD_LINES`, `recomputeWonBoards`, `computeWinners`) but nothing
// else. In particular, `ClassicalGameState` has no entanglements,
// quantum marks, or pending collapse, and `ClassicalMove` places a
// single square instead of a pair.
//
// Classical rules (as commonly played, matching e.g. the Wikipedia
// article on Ultimate Tic-Tac-Toe):
//  - Move at position `p` on any open mini-board sends the opponent
//    to mini-board `p`.
//  - A "closed" mini-board is one already won by either player or one
//    with every square occupied (drawn). No further placements happen
//    in a closed board.
//  - Being sent to a closed board grants free play: place anywhere in
//    any open (non-closed) mini-board.
//  - Meta-board win condition matches quantum Squared: three mini-boards
//    held in a row on the 3x3 meta-grid; a shared mini-board (both
//    players hold a line in it) counts for both.
package net.yonge_mallo.uqttt.engine

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Moves and their outcomes.
// ---------------------------------------------------------------------------

/**
 * The act of placing a single classical mark. The move number is
 * 1-based and its parity determines the player (X for odd, O for even).
 */
@Serializable
data class ClassicalMove(val number: Int, val square: Square) {
    init {
        require(number >= 1) { "move number is 1-based" }
    }

    val player: Player get() = if (number % 2 == 1) Player.X else Player.O
}

/**
 * Why a classical move was rejected. `WRONG_SENT_BOARD` covers both
 * the "you must play in the sent board" case and the "you can't play
 * in a closed board even under free play" case; the message the UI
 * shows disambiguates.
 */
enum class ClassicalIllegalReason {
    SQUARE_OCCUPIED,
    NOT_YOUR_TURN,
    WRONG_SENT_BOARD,
    BOARD_CLOSED,
    GAME_OVER,
}

/**
 * The outcome of attempting a classical move. Simpler than
 * `MoveResult` because classical play has no collapse.
 */
sealed interface ClassicalMoveResult {
    data class Illegal(val reason: ClassicalIllegalReason) : ClassicalMoveResult

    data class Legal(val nextState: ClassicalGameState) : ClassicalMoveResult
}

// ---------------------------------------------------------------------------
// Game state.
// ---------------------------------------------------------------------------

/**
 * Immutable snapshot of a classical game. All mutation goes through
 * `ClassicalRules.apply`, which returns a fresh `ClassicalGameState`.
 *
 * - `variant` is always `Variant.ULTIMATE_TIC_TAC_TOE` today; the
 *   field is kept so the shape stays parallel with the quantum
 *   `GameState` (and so a future non-Ultimate classical variant can
 *   drop in without changing the shape).
 * - `classical` is the map of resolved squares to the player whose
 *   mark lives there permanently.
 * - `wonBoards` mirrors the quantum field: mini-board index to the
 *   set of players with a line in it. A shared mini-board has both
 *   entries.
 * - `nextMoveNumber` is what the next move's number will be.
 * - `lastMove` is the just-placed move the UI highlights; null on a
 *   fresh game.
 * - `requiredBoard` is the mini-board the previous move sent to.
 *   Null on the first move. If the sent board turns out to be
 *   closed (won or full), the mover has free play but the field
 *   still records where they were nominally sent.
 */
@Serializable
data class ClassicalGameState(
    val variant: Variant,
    val classical: Map<Square, Player>,
    val wonBoards: Map<Int, Set<Player>>,
    val nextMoveNumber: Int,
    val lastMove: ClassicalMove? = null,
    val requiredBoard: Int? = null,
) {
    val nextPlayer: Player
        get() = if (nextMoveNumber % 2 == 1) Player.X else Player.O

    val winners: Set<Player> get() = computeWinners(variant, wonBoards)

    val winner: Player? get() = winners.singleOrNull()

    val isSharedWin: Boolean get() = winners.size == 2

    val isDraw: Boolean
        get() = winners.isEmpty() && ClassicalRules.legalMoves(this).none()

    val isGameOver: Boolean get() = winners.isNotEmpty() || isDraw

    /**
     * The effective sending-rule constraint at this state: the sent
     * board if there is one AND it is still open, otherwise null (the
     * mover has free play among all open mini-boards). Callers who
     * want the raw send-target, closed or not, should read
     * `requiredBoard` directly.
     */
    val effectiveRequiredBoard: Int?
        get() {
            val target = requiredBoard ?: return null
            return if (isBoardClosed(target, classical, wonBoards)) null else target
        }
}

// ---------------------------------------------------------------------------
// Rules.
// ---------------------------------------------------------------------------

object ClassicalRules {
    /** A fresh classical game with no marks placed. */
    fun initial(variant: Variant): ClassicalGameState {
        require(variant.isClassical) {
            "ClassicalRules.initial is for classical variants only; use Rules.initial for $variant"
        }
        return ClassicalGameState(
            variant = variant,
            classical = emptyMap(),
            wonBoards = emptyMap(),
            nextMoveNumber = 1,
        )
    }

    /**
     * Attempt to play a move. Returns Legal or Illegal. A move is
     * illegal if the game is already over, if it is not the mover's
     * turn, if the target square is already occupied, or if the
     * target board violates the sending rule (either "you were sent
     * to another board" or "that board is closed and free play
     * doesn't extend to closed boards").
     */
    fun apply(
        state: ClassicalGameState,
        move: ClassicalMove,
    ): ClassicalMoveResult {
        if (state.isGameOver) {
            return ClassicalMoveResult.Illegal(ClassicalIllegalReason.GAME_OVER)
        }
        if (move.number != state.nextMoveNumber || move.player != state.nextPlayer) {
            return ClassicalMoveResult.Illegal(ClassicalIllegalReason.NOT_YOUR_TURN)
        }
        if (move.square in state.classical) {
            return ClassicalMoveResult.Illegal(ClassicalIllegalReason.SQUARE_OCCUPIED)
        }
        val target = state.effectiveRequiredBoard
        if (target != null && move.square.board != target) {
            return ClassicalMoveResult.Illegal(ClassicalIllegalReason.WRONG_SENT_BOARD)
        }
        // Whether under a live send or free play, a closed board is
        // never a legal target. The live-send case is already
        // eliminated above (a closed target puts us in free play);
        // this catches free play trying to reach a closed board.
        if (isBoardClosed(move.square.board, state.classical, state.wonBoards)) {
            return ClassicalMoveResult.Illegal(ClassicalIllegalReason.BOARD_CLOSED)
        }
        val newClassical = state.classical + (move.square to move.player)
        val newWonBoards =
            recomputeWonBoards(
                state.wonBoards,
                newClassical,
                setOf(move.square.board),
            )
        // Sending rule: the position played is the board sent to.
        val nextRequiredBoard = move.square.position
        return ClassicalMoveResult.Legal(
            state.copy(
                classical = newClassical,
                wonBoards = newWonBoards,
                nextMoveNumber = state.nextMoveNumber + 1,
                lastMove = move,
                requiredBoard = nextRequiredBoard,
            ),
        )
    }

    /**
     * Enumerate all legal moves for the current player as a lazy
     * sequence. Iteration is in canonical (board, position) order so
     * two runs over the same state yield the same sequence.
     */
    fun legalMoves(state: ClassicalGameState): Sequence<ClassicalMove> =
        sequence {
            if (computeWinners(state.variant, state.wonBoards).isNotEmpty()) return@sequence
            val target = state.effectiveRequiredBoard
            val allowedBoards: List<Int> =
                if (target != null) {
                    listOf(target)
                } else {
                    (1..9).filter { !isBoardClosed(it, state.classical, state.wonBoards) }
                }
            for (board in allowedBoards) {
                for (position in 1..9) {
                    val sq = Square(board, position)
                    if (sq !in state.classical) {
                        yield(ClassicalMove(state.nextMoveNumber, sq))
                    }
                }
            }
        }
}

// ---------------------------------------------------------------------------
// Internal helpers.
// ---------------------------------------------------------------------------

/**
 * A mini-board is "closed" once no further marks may be placed on
 * it: it has been won by a player, or every one of its nine squares
 * is occupied (drawn).
 */
internal fun isBoardClosed(
    board: Int,
    classical: Map<Square, Player>,
    wonBoards: Map<Int, Set<Player>>,
): Boolean {
    if (!wonBoards[board].isNullOrEmpty()) return true
    for (position in 1..9) {
        if (Square(board, position) !in classical) return false
    }
    return true
}
