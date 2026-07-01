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

package net.yonge_mallo.uqttt.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mini-board winner detection, meta-game winner detection, and draw.
 *
 * `recomputeWonBoards` and `computeWinners` are `internal`, so tests in
 * the same module can drive them directly with synthetic inputs rather
 * than building elaborate apply/resolve sequences just to land specific
 * classical patterns. The public-surface checks live alongside via
 * `GameState`'s computed properties.
 */
class WinConditionTest {
    @Test
    fun miniBoardWithOneCompleteLineRecordsThePlayerAsWinner() {
        // X completes the bottom row of board 1.
        val classical =
            mapOf(
                Square(1, 7) to Player.X,
                Square(1, 8) to Player.X,
                Square(1, 9) to Player.X,
            )
        val won =
            recomputeWonBoards(
                previous = emptyMap(),
                classical = classical,
                touchedBoards = setOf(1),
            )
        assertEquals(setOf(Player.X), won[1])
    }

    @Test
    fun miniBoardWithLinesForBothPlayersIsSharedInTheWinnerSet() {
        // X has the bottom row, O has the top row, both in board 1.
        val classical =
            mapOf(
                Square(1, 7) to Player.X,
                Square(1, 8) to Player.X,
                Square(1, 9) to Player.X,
                Square(1, 1) to Player.O,
                Square(1, 2) to Player.O,
                Square(1, 3) to Player.O,
            )
        val won =
            recomputeWonBoards(
                previous = emptyMap(),
                classical = classical,
                touchedBoards = setOf(1),
            )
        assertEquals(setOf(Player.X, Player.O), won[1])
    }

    @Test
    fun untouchedBoardsKeepTheirPreviousWinnerSet() {
        // Board 1 was already won by X. Touching board 2 must not erase that.
        val previous = mapOf(1 to setOf(Player.X))
        val classical =
            mapOf(
                Square(1, 7) to Player.X,
                Square(1, 8) to Player.X,
                Square(1, 9) to Player.X,
                Square(2, 5) to Player.O,
            )
        val won =
            recomputeWonBoards(
                previous = previous,
                classical = classical,
                touchedBoards = setOf(2),
            )
        assertEquals(setOf(Player.X), won[1])
        // Board 2 has just one classical, no winner.
        assertEquals(emptySet(), won[2])
    }

    @Test
    fun threeWonMiniBoardsInARowGiveTheMetaGameToThatPlayer() {
        // Meta row (squares 7, 8, 9) all held by X.
        val state =
            Rules.initial(Variant.QUANTUM_TIC_TAC_TOE_SQUARED).copy(
                wonBoards =
                    mapOf(
                        7 to setOf(Player.X),
                        8 to setOf(Player.X),
                        9 to setOf(Player.X),
                    ),
            )
        assertEquals(setOf(Player.X), state.winners)
        assertEquals(Player.X, state.winner)
        assertTrue(state.isGameOver)
        assertFalse(state.isSharedWin)
    }

    @Test
    fun sharedMiniBoardCountsForBothPlayersInMetaWinDetection() {
        // Meta diagonal (squares 1, 5, 9) where the centre is shared.
        // X has 1, X has 9; the centre 5 is shared. X completes the diagonal.
        val state =
            Rules.initial(Variant.QUANTUM_TIC_TAC_TOE_SQUARED).copy(
                wonBoards =
                    mapOf(
                        1 to setOf(Player.X),
                        5 to setOf(Player.X, Player.O),
                        9 to setOf(Player.X),
                    ),
            )
        assertEquals(setOf(Player.X), state.winners)
        assertEquals(Player.X, state.winner)
        assertTrue(state.isGameOver)
    }

    @Test
    fun bothPlayersCanWinTheMetaGameInTheSameMove() {
        // Independent rows: X holds bottom row, O holds top row.
        val state =
            Rules.initial(Variant.QUANTUM_TIC_TAC_TOE_SQUARED).copy(
                wonBoards =
                    mapOf(
                        7 to setOf(Player.X),
                        8 to setOf(Player.X),
                        9 to setOf(Player.X),
                        1 to setOf(Player.O),
                        2 to setOf(Player.O),
                        3 to setOf(Player.O),
                    ),
            )
        assertEquals(setOf(Player.X, Player.O), state.winners)
        // `winner` is the unique winner, so it is null when shared.
        assertNull(state.winner)
        assertTrue(state.isSharedWin)
        assertTrue(state.isGameOver)
    }

    @Test
    fun quantumVariantWinIsJustTheWinnersOfMiniBoardOne() {
        val state =
            Rules.initial(Variant.QUANTUM_TIC_TAC_TOE).copy(
                wonBoards = mapOf(1 to setOf(Player.O)),
            )
        assertEquals(setOf(Player.O), state.winners)
        assertEquals(Player.O, state.winner)
        assertTrue(state.isGameOver)
    }

    @Test
    fun quantumVariantSharedMiniBoardIsAlsoASharedWin() {
        val state =
            Rules.initial(Variant.QUANTUM_TIC_TAC_TOE).copy(
                wonBoards = mapOf(1 to setOf(Player.X, Player.O)),
            )
        assertEquals(setOf(Player.X, Player.O), state.winners)
        assertNull(state.winner)
        assertTrue(state.isSharedWin)
        assertTrue(state.isGameOver)
    }

    @Test
    fun fullyClassicalBoardWithoutAWinnerIsADraw() {
        // Standard tic-tac-toe drawn position on board 1 in QTTT:
        //   1 O | 2 X | 3 O
        //   4 O | 5 X | 6 O
        //   7 X | 8 O | 9 X
        val state =
            Rules.initial(Variant.QUANTUM_TIC_TAC_TOE).copy(
                classical =
                    mapOf(
                        Square(1, 7) to Player.X,
                        Square(1, 8) to Player.O,
                        Square(1, 9) to Player.X,
                        Square(1, 4) to Player.O,
                        Square(1, 5) to Player.X,
                        Square(1, 6) to Player.O,
                        Square(1, 1) to Player.O,
                        Square(1, 2) to Player.X,
                        Square(1, 3) to Player.O,
                    ),
                wonBoards = mapOf(1 to emptySet()),
                nextMoveNumber = 10,
            )
        assertTrue(state.winners.isEmpty())
        assertTrue(state.isDraw)
        assertTrue(state.isGameOver)
        // Sanity: no legal continuation.
        assertEquals(0, Rules.legalMoves(state).count())
    }

    @Test
    fun gameInProgressIsNotADraw() {
        val state = Rules.initial(Variant.QUANTUM_TIC_TAC_TOE)
        assertFalse(state.isDraw)
        assertFalse(state.isGameOver)
    }

    @Test
    fun pendingCollapseIsNeitherWinNorDraw() {
        val state =
            Rules.initial(Variant.QUANTUM_TIC_TAC_TOE).copy(
                pendingCollapse = PendingCollapse(Player.O, emptyList()),
            )
        assertFalse(state.isDraw)
        assertFalse(state.isGameOver)
    }
}
