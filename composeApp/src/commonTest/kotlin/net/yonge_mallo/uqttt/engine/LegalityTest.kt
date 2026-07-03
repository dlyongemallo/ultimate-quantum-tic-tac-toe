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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cases for every `IllegalReason`, plus an initial-state shape check and
 * a positive control that confirms a legal move on a fresh board does
 * advance the state. The bulk of the file is a data-driven table to
 * keep new cases cheap.
 */
class LegalityTest {
    private data class IllegalityCase(
        val label: String,
        val state: GameState,
        val move: Move,
        val expected: IllegalReason,
    )

    @Test
    fun illegalityReasons() {
        val cases =
            listOf(
                IllegalityCase(
                    label = "SQUARE_IS_CLASSICAL: endpoint a is already classical",
                    state =
                        Rules
                            .initial(Variant.QUANTUM_TIC_TAC_TOE_SQUARED)
                            .copy(classical = mapOf(Square(1, 1) to Player.X)),
                    move = Move(1, Square(1, 1), Square(1, 2)),
                    expected = IllegalReason.SQUARE_IS_CLASSICAL,
                ),
                IllegalityCase(
                    label = "SQUARE_IS_CLASSICAL: endpoint b is already classical",
                    state =
                        Rules
                            .initial(Variant.QUANTUM_TIC_TAC_TOE_SQUARED)
                            .copy(classical = mapOf(Square(2, 5) to Player.O)),
                    move = Move(1, Square(1, 1), Square(2, 5)),
                    expected = IllegalReason.SQUARE_IS_CLASSICAL,
                ),
                IllegalityCase(
                    label = "DUPLICATE_INTER_BOARD_ENTANGLEMENT: same mini-board pair, different squares",
                    state =
                        stateWithSingleEntanglement(
                            Variant.QUANTUM_TIC_TAC_TOE_SQUARED,
                            moveNumber = 1,
                            a = Square(1, 5),
                            b = Square(2, 5),
                        ),
                    move = Move(2, Square(1, 9), Square(2, 1)),
                    expected = IllegalReason.DUPLICATE_INTER_BOARD_ENTANGLEMENT,
                ),
                IllegalityCase(
                    label = "DUPLICATE_INTER_BOARD_ENTANGLEMENT: pair is unordered",
                    state =
                        stateWithSingleEntanglement(
                            Variant.QUANTUM_TIC_TAC_TOE_SQUARED,
                            moveNumber = 1,
                            a = Square(1, 5),
                            b = Square(2, 5),
                        ),
                    // Same (1, 2) board pair but with endpoints swapped at construction time.
                    move = Move(2, Square(2, 9), Square(1, 1)),
                    expected = IllegalReason.DUPLICATE_INTER_BOARD_ENTANGLEMENT,
                ),
                IllegalityCase(
                    label = "NOT_YOUR_TURN: O attempts to play move 1",
                    state = Rules.initial(Variant.QUANTUM_TIC_TAC_TOE),
                    move = Move(2, Square(1, 1), Square(1, 2)),
                    expected = IllegalReason.NOT_YOUR_TURN,
                ),
                IllegalityCase(
                    label = "NOT_YOUR_TURN: move number doesn't match nextMoveNumber",
                    state = Rules.initial(Variant.QUANTUM_TIC_TAC_TOE),
                    move = Move(3, Square(1, 1), Square(1, 2)),
                    expected = IllegalReason.NOT_YOUR_TURN,
                ),
                IllegalityCase(
                    label = "PENDING_COLLAPSE_UNRESOLVED: a collapse is waiting on a choice",
                    state =
                        Rules
                            .initial(Variant.QUANTUM_TIC_TAC_TOE)
                            .copy(pendingCollapse = PendingCollapse(Player.O, emptyList())),
                    move = Move(1, Square(1, 1), Square(1, 2)),
                    expected = IllegalReason.PENDING_COLLAPSE_UNRESOLVED,
                ),
                IllegalityCase(
                    label = "BOARD_NOT_IN_VARIANT: Quantum variant rejects a board-2 endpoint",
                    state = Rules.initial(Variant.QUANTUM_TIC_TAC_TOE),
                    move = Move(1, Square(1, 1), Square(2, 1)),
                    expected = IllegalReason.BOARD_NOT_IN_VARIANT,
                ),
                IllegalityCase(
                    label = "BOARD_NOT_IN_VARIANT: both endpoints off-board in Quantum",
                    state = Rules.initial(Variant.QUANTUM_TIC_TAC_TOE),
                    move = Move(1, Square(3, 1), Square(4, 1)),
                    expected = IllegalReason.BOARD_NOT_IN_VARIANT,
                ),
                IllegalityCase(
                    label = "DUPLICATE_PAIR: intra-board same pair as an existing entanglement",
                    state =
                        stateWithSingleEntanglement(
                            Variant.QUANTUM_TIC_TAC_TOE,
                            moveNumber = 1,
                            a = Square(1, 1),
                            b = Square(1, 2),
                        ),
                    move = Move(2, Square(1, 1), Square(1, 2)),
                    expected = IllegalReason.DUPLICATE_PAIR,
                ),
                IllegalityCase(
                    label = "DUPLICATE_PAIR: pair order does not matter",
                    state =
                        stateWithSingleEntanglement(
                            Variant.QUANTUM_TIC_TAC_TOE,
                            moveNumber = 1,
                            a = Square(1, 1),
                            b = Square(1, 2),
                        ),
                    move = Move(2, Square(1, 2), Square(1, 1)),
                    expected = IllegalReason.DUPLICATE_PAIR,
                ),
            )

        for (c in cases) {
            val result = Rules.apply(c.state, c.move)
            val illegal = assertIs<MoveResult.Illegal>(result, c.label)
            assertEquals(c.expected, illegal.reason, c.label)
        }
    }

    @Test
    fun positiveControlOnFreshBoard() {
        // Sanity: legality checks aren't rejecting everything.
        val state = Rules.initial(Variant.QUANTUM_TIC_TAC_TOE)
        val move = Move(1, Square(1, 1), Square(1, 2))
        val result = Rules.apply(state, move)
        val legal = assertIs<MoveResult.Legal>(result)
        assertEquals(Player.O, legal.nextState.nextPlayer)
        assertEquals(2, legal.nextState.nextMoveNumber)
        assertEquals(1, legal.nextState.entanglements.size)
        assertEquals(Player.X, legal.nextState.entanglements.single().player)
        assertEquals(2, legal.nextState.quantum.size)
        assertEquals(move, legal.nextState.lastMove)
    }

    @Test
    fun initialStateIsEmpty() {
        for (variant in Variant.entries.filter { it.isQuantum }) {
            val state = Rules.initial(variant)
            assertEquals(variant, state.variant)
            assertEquals(Player.X, state.nextPlayer)
            assertEquals(1, state.nextMoveNumber)
            assertTrue(state.classical.isEmpty(), variant.name)
            assertTrue(state.quantum.isEmpty(), variant.name)
            assertTrue(state.entanglements.isEmpty(), variant.name)
            assertNull(state.pendingCollapse, variant.name)
            assertNull(state.lastMove, variant.name)
            assertTrue(state.winners.isEmpty(), variant.name)
            assertNull(state.winner, variant.name)
        }
    }

    @Test
    fun legalMovesCardinalityOnFreshBoard() {
        // QTTT: 9 squares, 9 choose 2 = 36 pairs.
        assertEquals(36, Rules.legalMoves(Rules.initial(Variant.QUANTUM_TIC_TAC_TOE)).count())
        // UQTTT: 81 squares, 81 choose 2 = 3240 pairs.
        assertEquals(
            3240,
            Rules.legalMoves(Rules.initial(Variant.QUANTUM_TIC_TAC_TOE_SQUARED)).count(),
        )
    }

    @Test
    fun legalMovesExcludeClassicalAndDuplicateInterBoardPairs() {
        // UQTTT: one classical square in board 1, plus an existing inter-board
        // entanglement (1, 2). Pairs spanning (1, 2) and pairs involving the
        // classical square should be excluded.
        val state =
            stateWithSingleEntanglement(
                Variant.QUANTUM_TIC_TAC_TOE_SQUARED,
                moveNumber = 1,
                a = Square(1, 5),
                b = Square(2, 5),
            ).copy(classical = mapOf(Square(3, 3) to Player.X))

        val moves = Rules.legalMoves(state).toList()
        // None of the generated moves should touch the classical square or span (1, 2).
        for (move in moves) {
            assertTrue(Square(3, 3) !in setOf(move.a, move.b), "$move touches classical")
            assertTrue(
                setOf(move.a.board, move.b.board) != setOf(1, 2) || move.a.board == move.b.board,
                "$move duplicates existing (1, 2) entanglement",
            )
        }
    }

    @Test
    fun legalMovesIsEmptyDuringPendingCollapse() {
        val state =
            Rules
                .initial(Variant.QUANTUM_TIC_TAC_TOE)
                .copy(pendingCollapse = PendingCollapse(Player.O, emptyList()))
        assertEquals(0, Rules.legalMoves(state).count())
    }

    @Test
    fun legalMovesIsEmptyAfterAWin() {
        val state =
            Rules
                .initial(Variant.QUANTUM_TIC_TAC_TOE)
                .copy(wonBoards = mapOf(1 to setOf(Player.X)))
        assertEquals(0, Rules.legalMoves(state).count())
    }

    @Test
    fun applyRejectsMovesAfterAWin() {
        val state =
            Rules
                .initial(Variant.QUANTUM_TIC_TAC_TOE)
                .copy(wonBoards = mapOf(1 to setOf(Player.X)))
        val result = Rules.apply(state, Move(1, Square(1, 1), Square(1, 2)))
        assertTrue(result is MoveResult.Illegal && result.reason == IllegalReason.GAME_OVER)
    }

    @Test
    fun sendingRuleConstrainsOpponentToBoardsBAndD() {
        // X1 places at 5/3-5/7 (intra-board within mini-board 5,
        // positions 3 and 7); the rule sends O to entangle
        // mini-boards 3 and 7. O's pair must span 3 and 7.
        val s0 = Rules.initial(Variant.ULTIMATE_QUANTUM_TIC_TAC_TOE)
        val s1 =
            assertIs<MoveResult.Legal>(Rules.apply(s0, Move(1, Square(5, 3), Square(5, 7))))
                .nextState
        assertEquals(Pair(3, 7), s1.requiredBoards)
        // A correctly-sent O2 is accepted.
        val good = Rules.apply(s1, Move(2, Square(3, 1), Square(7, 9)))
        assertIs<MoveResult.Legal>(good)
        // A wrongly-sent O2 is rejected with WRONG_SENT_BOARDS.
        val bad = Rules.apply(s1, Move(2, Square(2, 1), Square(8, 1)))
        assertTrue(bad is MoveResult.Illegal && bad.reason == IllegalReason.WRONG_SENT_BOARDS)
    }

    @Test
    fun sendingRuleAllowsIntraBoardWhenPositionsCoincide() {
        // X1 places at 4/5-6/5: both positions are 5, so opponent
        // must play intra-board within mini-board 5.
        val s0 = Rules.initial(Variant.ULTIMATE_QUANTUM_TIC_TAC_TOE)
        val s1 =
            assertIs<MoveResult.Legal>(Rules.apply(s0, Move(1, Square(4, 5), Square(6, 5))))
                .nextState
        assertEquals(Pair(5, 5), s1.requiredBoards)
        val good = Rules.apply(s1, Move(2, Square(5, 1), Square(5, 9)))
        assertIs<MoveResult.Legal>(good)
        val bad = Rules.apply(s1, Move(2, Square(5, 1), Square(6, 9)))
        assertTrue(bad is MoveResult.Illegal && bad.reason == IllegalReason.WRONG_SENT_BOARDS)
    }

    @Test
    fun sendingRuleGrantsFreePlayWhenConstraintUnsatisfiable() {
        // Pre-spend the inter-board entanglement budget between mini-boards
        // 3 and 7 (with an entanglement that is not part of any cycle),
        // then play a move whose positions try to send opponent to (3, 7).
        // The opponent must have free play.
        val base = Rules.initial(Variant.ULTIMATE_QUANTUM_TIC_TAC_TOE)
        val priorEdge =
            Entanglement(
                QuantumMark(Player.X, 99, Square(3, 1)),
                QuantumMark(Player.X, 99, Square(7, 1)),
            )
        val seeded =
            base.copy(
                entanglements = listOf(priorEdge),
                quantum = listOf(priorEdge.a, priorEdge.b),
                nextMoveNumber = 100,
                requiredBoards = Pair(3, 7),
            )
        // O's free play: any inter-board placement that isn't between 3 and
        // 7 (so it doesn't itself trip DUPLICATE_INTER_BOARD_ENTANGLEMENT)
        // should land as Legal even though it violates the nominal send.
        val freePlay = Rules.apply(seeded, Move(100, Square(2, 1), Square(8, 1)))
        assertIs<MoveResult.Legal>(freePlay)
    }
}

private fun stateWithSingleEntanglement(
    variant: Variant,
    moveNumber: Int,
    a: Square,
    b: Square,
): GameState {
    val player = if (moveNumber % 2 == 1) Player.X else Player.O
    val markA = QuantumMark(player, moveNumber, a)
    val markB = QuantumMark(player, moveNumber, b)
    val edge = Entanglement(markA, markB)
    return Rules
        .initial(variant)
        .copy(
            quantum = listOf(markA, markB),
            entanglements = listOf(edge),
            nextMoveNumber = moveNumber + 1,
        )
}
