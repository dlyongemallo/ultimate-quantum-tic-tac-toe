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
 * Loop detection + collapse resolution + cascade.
 *
 * The fixed Quantum cases verify the exact classical maps the two
 * resolutions of a 3-cycle produce. The Squared case verifies that a
 * 3-cycle whose loop nodes carry tree-edges propagates the collapse
 * outward through those edges so every hanging quantum mark is
 * resolved.
 */
class CollapseTest {
    @Test
    fun threeCycleInQttt() {
        // Build a 3-cycle 1/1 -> 1/2 -> 1/3 -> 1/1, alternating players.
        var state = Rules.initial(Variant.QUANTUM_TIC_TAC_TOE)
        state =
            assertIs<MoveResult.Legal>(
                Rules.apply(state, Move(1, Square(1, 1), Square(1, 2))),
            ).nextState
        state =
            assertIs<MoveResult.Legal>(
                Rules.apply(state, Move(2, Square(1, 2), Square(1, 3))),
            ).nextState
        val triggered =
            assertIs<MoveResult.TriggersCollapse>(
                Rules.apply(state, Move(3, Square(1, 3), Square(1, 1))),
            )

        assertEquals(2, triggered.choices.size)
        // X closed the loop, so O picks the resolution.
        assertEquals(Player.O, triggered.pendingState.pendingCollapse!!.chooser)

        // Both choices populate exactly the three loop squares, each with one classical mark.
        val loopSquares = setOf(Square(1, 1), Square(1, 2), Square(1, 3))
        for (choice in triggered.choices) {
            assertEquals(3, choice.assignments.size)
            assertEquals(loopSquares, choice.assignments.values.toSet())
        }

        // Resolve each choice and confirm the resulting classical map matches one
        // of the two valid rotations. The two valid rotations for this loop are:
        //   rotation A: 1/1=X (from m1), 1/2=O (from m2), 1/3=X (from m3)
        //   rotation B: 1/1=X (from m3), 1/2=X (from m1), 1/3=O (from m2)
        val rotationA =
            mapOf(
                Square(1, 1) to Player.X,
                Square(1, 2) to Player.O,
                Square(1, 3) to Player.X,
            )
        val rotationB =
            mapOf(
                Square(1, 1) to Player.X,
                Square(1, 2) to Player.X,
                Square(1, 3) to Player.O,
            )
        val resolvedClassicalMaps =
            triggered.choices.map { choice ->
                Rules.resolve(triggered.pendingState, choice).classical
            }
        assertEquals(setOf(rotationA, rotationB), resolvedClassicalMaps.toSet())
    }

    @Test
    fun interBoardLoopCascadesThroughTreeEdges() {
        // Build a 3-cycle on inter-board edges (1-2, 2-3, 1-3) with one intra-
        // board tree edge hanging off each of two loop nodes:
        //   m1 X: 1/5 - 2/5  (inter, boards 1-2)
        //   m2 O: 2/5 - 2/1  (intra board 2; tree edge off loop node 2/5)
        //   m3 X: 2/5 - 3/5  (inter, boards 2-3)
        //   m4 O: 3/5 - 3/1  (intra board 3; tree edge off loop node 3/5)
        //   m5 X: 1/5 - 3/5  (inter, boards 1-3; closes loop {1/5, 2/5, 3/5})
        var state = Rules.initial(Variant.QUANTUM_TIC_TAC_TOE_SQUARED)
        state =
            assertIs<MoveResult.Legal>(
                Rules.apply(state, Move(1, Square(1, 5), Square(2, 5))),
            ).nextState
        state =
            assertIs<MoveResult.Legal>(
                Rules.apply(state, Move(2, Square(2, 5), Square(2, 1))),
            ).nextState
        state =
            assertIs<MoveResult.Legal>(
                Rules.apply(state, Move(3, Square(2, 5), Square(3, 5))),
            ).nextState
        state =
            assertIs<MoveResult.Legal>(
                Rules.apply(state, Move(4, Square(3, 5), Square(3, 1))),
            ).nextState
        val triggered =
            assertIs<MoveResult.TriggersCollapse>(
                Rules.apply(state, Move(5, Square(1, 5), Square(3, 5))),
            )

        assertEquals(2, triggered.choices.size)
        // X closed the loop with m5, so O picks.
        assertEquals(Player.O, triggered.pendingState.pendingCollapse!!.chooser)

        // Both resolutions must promote every loop square AND every tree-edge
        // outside square: 3 loop nodes + 2 cascaded outside nodes.
        val expectedTouched =
            setOf(
                Square(1, 5),
                Square(2, 5),
                Square(3, 5),
                Square(2, 1),
                Square(3, 1),
            )
        for (choice in triggered.choices) {
            assertEquals(5, choice.assignments.size)
            assertEquals(expectedTouched, choice.assignments.values.toSet())
        }

        // Resolve and confirm everything quantum vanished and 5 classicals landed.
        val resolved = Rules.resolve(triggered.pendingState, triggered.choices[0])
        assertTrue(resolved.quantum.isEmpty())
        assertTrue(resolved.entanglements.isEmpty())
        assertEquals(expectedTouched, resolved.classical.keys)
        // The three loop edges are all X moves, so the three loop squares end up X.
        assertEquals(Player.X, resolved.classical[Square(1, 5)])
        assertEquals(Player.X, resolved.classical[Square(2, 5)])
        assertEquals(Player.X, resolved.classical[Square(3, 5)])
        // The two cascaded squares come from O moves (m2 and m4).
        assertEquals(Player.O, resolved.classical[Square(2, 1)])
        assertEquals(Player.O, resolved.classical[Square(3, 1)])
    }

    @Test
    fun legalMoveWithoutLoopAdvancesWithoutCollapse() {
        val state = Rules.initial(Variant.QUANTUM_TIC_TAC_TOE_SQUARED)
        val result = Rules.apply(state, Move(1, Square(1, 1), Square(9, 9)))
        val legal = assertIs<MoveResult.Legal>(result)
        assertNull(legal.nextState.pendingCollapse)
        assertEquals(2, legal.nextState.nextMoveNumber)
        assertEquals(1, legal.nextState.entanglements.size)
    }

    @Test
    fun resolveIgnoresForgedAssignmentsAndUsesCanonicalChoice() {
        // Build the same 3-cycle as `threeCycleInQttt` so we have a
        // legitimate `pendingState` with two offered choices, then call
        // `Rules.resolve` with a `CollapseChoice` carrying the right id
        // but a single bogus assignment. The engine must apply the
        // canonical assignments (resolving all three loop squares),
        // not the caller's one-entry map.
        var state = Rules.initial(Variant.QUANTUM_TIC_TAC_TOE)
        state =
            assertIs<MoveResult.Legal>(
                Rules.apply(state, Move(1, Square(1, 1), Square(1, 2))),
            ).nextState
        state =
            assertIs<MoveResult.Legal>(
                Rules.apply(state, Move(2, Square(1, 2), Square(1, 3))),
            ).nextState
        val triggered =
            assertIs<MoveResult.TriggersCollapse>(
                Rules.apply(state, Move(3, Square(1, 3), Square(1, 1))),
            )
        val realChoice = triggered.choices[0]
        val forgedSingleMark =
            QuantumMark(player = Player.O, moveNumber = 1, square = Square(1, 9))
        val forged = CollapseChoice(id = realChoice.id, assignments = mapOf(forgedSingleMark to Square(1, 9)))

        val resolved = Rules.resolve(triggered.pendingState, forged)

        // The forged classical mark at 1/9 must not appear; the
        // resolution must populate the three loop squares exactly as
        // the canonical choice would.
        assertEquals(realChoice.assignments.values.toSet(), resolved.classical.keys)
        assertTrue(Square(1, 9) !in resolved.classical)
    }
}
