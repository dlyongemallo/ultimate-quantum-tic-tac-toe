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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every `ClassicalIllegalReason`, an initial-state shape check, the
 * sending rule under a satisfiable and an unsatisfiable target, and
 * `legalMoves` cardinalities that make the send / free-play branches
 * observable.
 */
class ClassicalLegalityTest {
    private data class IllegalityCase(
        val label: String,
        val state: ClassicalGameState,
        val move: ClassicalMove,
        val expected: ClassicalIllegalReason,
    )

    @Test
    fun illegalityReasons() {
        val cases =
            listOf(
                IllegalityCase(
                    label = "SQUARE_OCCUPIED: target square already has a classical mark",
                    state =
                        ClassicalRules
                            .initial(Variant.ULTIMATE_TIC_TAC_TOE)
                            .copy(classical = mapOf(Square(1, 1) to Player.X)),
                    move = ClassicalMove(1, Square(1, 1)),
                    expected = ClassicalIllegalReason.SQUARE_OCCUPIED,
                ),
                IllegalityCase(
                    label = "NOT_YOUR_TURN: O attempts to play move 1",
                    state = ClassicalRules.initial(Variant.ULTIMATE_TIC_TAC_TOE),
                    move = ClassicalMove(2, Square(1, 1)),
                    expected = ClassicalIllegalReason.NOT_YOUR_TURN,
                ),
                IllegalityCase(
                    label = "NOT_YOUR_TURN: move number doesn't match nextMoveNumber",
                    state = ClassicalRules.initial(Variant.ULTIMATE_TIC_TAC_TOE),
                    move = ClassicalMove(3, Square(1, 1)),
                    expected = ClassicalIllegalReason.NOT_YOUR_TURN,
                ),
                IllegalityCase(
                    label = "WRONG_SENT_BOARD: sent to board 5 but playing in board 1",
                    state =
                        ClassicalRules
                            .initial(Variant.ULTIMATE_TIC_TAC_TOE)
                            .copy(nextMoveNumber = 2, requiredBoard = 5),
                    move = ClassicalMove(2, Square(1, 1)),
                    expected = ClassicalIllegalReason.WRONG_SENT_BOARD,
                ),
                IllegalityCase(
                    label = "BOARD_CLOSED: free play cannot reach a won mini-board",
                    state =
                        ClassicalRules
                            .initial(Variant.ULTIMATE_TIC_TAC_TOE)
                            .copy(
                                classical = mapOf(Square(2, 1) to Player.O),
                                wonBoards = mapOf(2 to setOf(Player.X)),
                                nextMoveNumber = 2,
                                requiredBoard = null,
                            ),
                    move = ClassicalMove(2, Square(2, 5)),
                    expected = ClassicalIllegalReason.BOARD_CLOSED,
                ),
            )

        for (c in cases) {
            val result = ClassicalRules.apply(c.state, c.move)
            val illegal = assertIs<ClassicalMoveResult.Illegal>(result, c.label)
            assertEquals(c.expected, illegal.reason, c.label)
        }
    }

    @Test
    fun initialRulesRejectQuantumVariants() {
        for (variant in Variant.entries.filter { it.isQuantum }) {
            assertFailsWith<IllegalArgumentException>(variant.name) {
                ClassicalRules.initial(variant)
            }
        }
    }

    @Test
    fun quantumRulesRejectClassicalVariants() {
        for (variant in Variant.entries.filter { it.isClassical }) {
            assertFailsWith<IllegalArgumentException>(variant.name) {
                Rules.initial(variant)
            }
        }
    }

    @Test
    fun initialStateIsEmpty() {
        val state = ClassicalRules.initial(Variant.ULTIMATE_TIC_TAC_TOE)
        assertEquals(Variant.ULTIMATE_TIC_TAC_TOE, state.variant)
        assertEquals(Player.X, state.nextPlayer)
        assertEquals(1, state.nextMoveNumber)
        assertTrue(state.classical.isEmpty())
        assertTrue(state.wonBoards.isEmpty())
        assertNull(state.lastMove)
        assertNull(state.requiredBoard)
        assertTrue(state.winners.isEmpty())
        assertNull(state.winner)
    }

    @Test
    fun positiveControlOnFreshBoard() {
        val state = ClassicalRules.initial(Variant.ULTIMATE_TIC_TAC_TOE)
        val move = ClassicalMove(1, Square(5, 3))
        val legal = assertIs<ClassicalMoveResult.Legal>(ClassicalRules.apply(state, move))
        assertEquals(Player.O, legal.nextState.nextPlayer)
        assertEquals(2, legal.nextState.nextMoveNumber)
        assertEquals(Player.X, legal.nextState.classical[Square(5, 3)])
        assertEquals(move, legal.nextState.lastMove)
        // Sending rule: position 3 sends opponent to mini-board 3.
        assertEquals(3, legal.nextState.requiredBoard)
    }

    @Test
    fun sendingRuleConstrainsOpponentToTheSentBoard() {
        val s0 = ClassicalRules.initial(Variant.ULTIMATE_TIC_TAC_TOE)
        val s1 =
            assertIs<ClassicalMoveResult.Legal>(ClassicalRules.apply(s0, ClassicalMove(1, Square(5, 7))))
                .nextState
        assertEquals(7, s1.requiredBoard)
        assertEquals(7, s1.effectiveRequiredBoard)
        // A correctly-sent O2 in board 7 is accepted.
        assertIs<ClassicalMoveResult.Legal>(ClassicalRules.apply(s1, ClassicalMove(2, Square(7, 5))))
        // A wrongly-sent O2 in any other board is rejected.
        val bad = assertIs<ClassicalMoveResult.Illegal>(ClassicalRules.apply(s1, ClassicalMove(2, Square(1, 1))))
        assertEquals(ClassicalIllegalReason.WRONG_SENT_BOARD, bad.reason)
    }

    @Test
    fun sendingRuleGrantsFreePlayWhenTargetBoardIsClosed() {
        // Set up: X was sent to board 7, but board 7 is already won
        // by O (from an earlier line of play). The sending constraint
        // is effectively null and X may play anywhere open.
        val state =
            ClassicalRules
                .initial(Variant.ULTIMATE_TIC_TAC_TOE)
                .copy(
                    classical =
                        mapOf(
                            // A won-by-O line in board 7.
                            Square(7, 1) to Player.O,
                            Square(7, 2) to Player.O,
                            Square(7, 3) to Player.O,
                            // A prior X mark somewhere else so it's X's turn.
                            Square(4, 5) to Player.X,
                        ),
                    wonBoards = mapOf(7 to setOf(Player.O)),
                    nextMoveNumber = 5,
                    requiredBoard = 7,
                )
        // With board 7 closed, `effectiveRequiredBoard` reports null.
        assertNull(state.effectiveRequiredBoard)
        // X may play in board 1 despite having been sent to 7.
        val freePlay = ClassicalRules.apply(state, ClassicalMove(5, Square(1, 1)))
        assertIs<ClassicalMoveResult.Legal>(freePlay)
    }

    @Test
    fun freePlayStillCannotReachAClosedBoard() {
        val state =
            ClassicalRules
                .initial(Variant.ULTIMATE_TIC_TAC_TOE)
                .copy(
                    classical =
                        mapOf(
                            Square(2, 1) to Player.O,
                            Square(2, 2) to Player.O,
                            Square(2, 3) to Player.O,
                        ),
                    wonBoards = mapOf(2 to setOf(Player.O)),
                    nextMoveNumber = 2,
                    requiredBoard = null,
                )
        val bad = assertIs<ClassicalMoveResult.Illegal>(ClassicalRules.apply(state, ClassicalMove(2, Square(2, 5))))
        assertEquals(ClassicalIllegalReason.BOARD_CLOSED, bad.reason)
    }

    @Test
    fun applyRejectsMovesAfterAWin() {
        val state =
            ClassicalRules
                .initial(Variant.ULTIMATE_TIC_TAC_TOE)
                .copy(
                    wonBoards =
                        mapOf(
                            1 to setOf(Player.X),
                            2 to setOf(Player.X),
                            3 to setOf(Player.X),
                        ),
                )
        val result = assertIs<ClassicalMoveResult.Illegal>(ClassicalRules.apply(state, ClassicalMove(1, Square(4, 1))))
        assertEquals(ClassicalIllegalReason.GAME_OVER, result.reason)
    }

    @Test
    fun legalMovesFirstMoveIs81() {
        val moves = ClassicalRules.legalMoves(ClassicalRules.initial(Variant.ULTIMATE_TIC_TAC_TOE)).toList()
        assertEquals(81, moves.size)
    }

    @Test
    fun legalMovesUnderLiveSendAreConfinedToTheSentBoard() {
        // X placed at 5/2; O is sent to board 2 and must play there.
        val s1 =
            assertIs<ClassicalMoveResult.Legal>(
                ClassicalRules.apply(
                    ClassicalRules.initial(Variant.ULTIMATE_TIC_TAC_TOE),
                    ClassicalMove(1, Square(5, 2)),
                ),
            ).nextState
        val moves = ClassicalRules.legalMoves(s1).toList()
        assertEquals(9, moves.size)
        assertTrue(moves.all { it.square.board == 2 })
    }

    @Test
    fun legalMovesUnderFreePlayCoverEveryOpenNonClosedSquare() {
        // Board 7 is won by O and therefore closed; requiredBoard is 7.
        // Free play applies, but the 9 squares in board 7 stay off-limits.
        val state =
            ClassicalRules
                .initial(Variant.ULTIMATE_TIC_TAC_TOE)
                .copy(
                    classical =
                        mapOf(
                            Square(7, 1) to Player.O,
                            Square(7, 2) to Player.O,
                            Square(7, 3) to Player.O,
                            Square(4, 5) to Player.X,
                        ),
                    wonBoards = mapOf(7 to setOf(Player.O)),
                    nextMoveNumber = 5,
                    requiredBoard = 7,
                )
        val moves = ClassicalRules.legalMoves(state).toList()
        // 8 open mini-boards; boards 4 and 7 each have one square
        // already occupied, and board 7 is entirely off-limits.
        // Boards 1, 2, 3, 5, 6, 8, 9: 7 * 9 = 63 open squares.
        // Board 4: 9 - 1 = 8 open squares.
        assertEquals(63 + 8, moves.size)
        assertTrue(moves.none { it.square.board == 7 })
    }
}
