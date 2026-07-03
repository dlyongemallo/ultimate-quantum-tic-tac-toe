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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Win / draw detection for classical Ultimate. The meta-board win
 * condition is shared with quantum Squared and covered there; this
 * file focuses on classical-specific wiring: playing an actual line
 * flips `wonBoards`, three won mini-boards on a meta-line finishes
 * the game, drawn / closed boards do not count toward wins, and a
 * full-but-not-won mini-board correctly reads as drawn (not sendable
 * but not won).
 */
class ClassicalWinConditionTest {
    @Test
    fun completingAMiniBoardLineFlipsWonBoards() {
        // Set up a state with two X marks already on the top row of
        // board 1 and a live send that directs X back to board 1, so
        // driving the closing move through `ClassicalRules.apply` -- the
        // real code path -- exercises `recomputeWonBoards`.
        // Constructing the state directly (rather than a natural
        // move sequence) avoids the sending rule's constraint that
        // X can only be sent to board 1 when the prior O move lands
        // at position 1, which quickly boxes in short scripts.
        val state =
            ClassicalRules.initial(Variant.ULTIMATE_TIC_TAC_TOE).copy(
                classical =
                    mapOf(
                        Square(1, 1) to Player.X,
                        Square(1, 2) to Player.X,
                        Square(5, 5) to Player.O,
                        Square(5, 1) to Player.O,
                    ),
                nextMoveNumber = 5,
                requiredBoard = 1,
            )
        val closing = ClassicalMove(5, Square(1, 3))
        val next = assertIs<ClassicalMoveResult.Legal>(ClassicalRules.apply(state, closing)).nextState
        assertEquals(setOf(Player.X), next.wonBoards[1])
    }

    @Test
    fun threeWonMiniBoardsInARowGiveTheMetaGameToThatPlayer() {
        val state =
            ClassicalRules.initial(Variant.ULTIMATE_TIC_TAC_TOE).copy(
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
    fun drawnMiniBoardDoesNotCountForEitherPlayerOnAMetaLine() {
        // Fully-occupied board 5 with no winner (drawn). Boards 1
        // and 9 won by X. The (1, 5, 9) meta-diagonal doesn't
        // resolve for X because 5 is drawn, not X-won.
        val classicalMap = mutableMapOf<Square, Player>()
        // Board 5 filled as a drawn 3x3:
        //   O X X
        //   X O O
        //   X O X
        val board5Owners =
            listOf(
                Player.O, Player.X, Player.X,
                Player.X, Player.O, Player.O,
                Player.X, Player.O, Player.X,
            )
        board5Owners.forEachIndexed { i, p -> classicalMap[Square(5, i + 1)] = p }
        val state =
            ClassicalRules.initial(Variant.ULTIMATE_TIC_TAC_TOE).copy(
                classical = classicalMap,
                wonBoards =
                    mapOf(
                        1 to setOf(Player.X),
                        5 to emptySet(),
                        9 to setOf(Player.X),
                    ),
            )
        assertTrue(state.winners.isEmpty())
        assertNull(state.winner)
    }

    @Test
    fun drawnMiniBoardIsClosedForSendingPurposes() {
        // Board 5 is drawn (full, no winner). O was sent to 5 and
        // has free play.
        val classicalMap = mutableMapOf<Square, Player>()
        val board5Owners =
            listOf(
                Player.O, Player.X, Player.X,
                Player.X, Player.O, Player.O,
                Player.X, Player.O, Player.X,
            )
        board5Owners.forEachIndexed { i, p -> classicalMap[Square(5, i + 1)] = p }
        val state =
            ClassicalRules.initial(Variant.ULTIMATE_TIC_TAC_TOE).copy(
                classical = classicalMap,
                wonBoards = mapOf(5 to emptySet()),
                nextMoveNumber = 10,
                requiredBoard = 5,
            )
        assertNull(state.effectiveRequiredBoard)
        val legal = ClassicalRules.apply(state, ClassicalMove(10, Square(1, 1)))
        assertIs<ClassicalMoveResult.Legal>(legal)
    }

    @Test
    fun fullyClosedMetaBoardIsADraw() {
        // Every mini-board is won by *someone* but no meta-line
        // resolves. The pattern below is a standard drawn 3x3
        // (X O X / X X O / O X O) applied to the meta-grid: rows,
        // columns, and both diagonals all mix players.
        val state =
            ClassicalRules.initial(Variant.ULTIMATE_TIC_TAC_TOE).copy(
                classical = emptyMap(),
                wonBoards =
                    mapOf(
                        1 to setOf(Player.X),
                        2 to setOf(Player.O),
                        3 to setOf(Player.X),
                        4 to setOf(Player.X),
                        5 to setOf(Player.X),
                        6 to setOf(Player.O),
                        7 to setOf(Player.O),
                        8 to setOf(Player.X),
                        9 to setOf(Player.O),
                    ),
                nextMoveNumber = 100,
                requiredBoard = null,
            )
        assertTrue(state.winners.isEmpty(), "sanity: no meta-line resolved")
        assertEquals(0, ClassicalRules.legalMoves(state).count())
        assertTrue(state.isDraw)
        assertTrue(state.isGameOver)
    }

    @Test
    fun gameInProgressIsNotADraw() {
        val state = ClassicalRules.initial(Variant.ULTIMATE_TIC_TAC_TOE)
        assertFalse(state.isDraw)
        assertFalse(state.isGameOver)
    }
}
