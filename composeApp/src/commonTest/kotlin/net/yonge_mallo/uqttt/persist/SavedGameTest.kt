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

package net.yonge_mallo.uqttt.persist

import net.yonge_mallo.uqttt.engine.ClassicalMove
import net.yonge_mallo.uqttt.engine.ClassicalMoveResult
import net.yonge_mallo.uqttt.engine.ClassicalRules
import net.yonge_mallo.uqttt.engine.Entanglement
import net.yonge_mallo.uqttt.engine.GameState
import net.yonge_mallo.uqttt.engine.Move
import net.yonge_mallo.uqttt.engine.MoveResult
import net.yonge_mallo.uqttt.engine.PendingCollapse
import net.yonge_mallo.uqttt.engine.Player
import net.yonge_mallo.uqttt.engine.QuantumMark
import net.yonge_mallo.uqttt.engine.Rules
import net.yonge_mallo.uqttt.engine.Square
import net.yonge_mallo.uqttt.engine.Variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Round-trip coverage for the save format: encoding a live state
 * and decoding it back must yield an equal state. Covers the shapes
 * likely to break serialization -- non-string map keys (`Square`,
 * `QuantumMark`), nested defaults (`pendingCollapse`, `lastMove`,
 * `requiredBoards`), and a `Pair<Int, Int>` field -- across both
 * engines. A malformed-input test also confirms `decodeSavedGame`
 * reports `Failed` instead of throwing.
 */
class SavedGameTest {
    @Test
    fun quantumFreshStateRoundTrips() {
        for (variant in Variant.entries.filter { it.isQuantum }) {
            val state = Rules.initial(variant)
            assertRoundTrip(state)
        }
    }

    @Test
    fun quantumMidGameStateRoundTrips() {
        val s0 = Rules.initial(Variant.QUANTUM_TIC_TAC_TOE_SQUARED)
        val s1 = assertIs<MoveResult.Legal>(Rules.apply(s0, Move(1, Square(1, 1), Square(2, 5)))).nextState
        val s2 = assertIs<MoveResult.Legal>(Rules.apply(s1, Move(2, Square(3, 3), Square(4, 4)))).nextState
        assertRoundTrip(s2)
    }

    @Test
    fun quantumPendingCollapseRoundTrips() {
        // Hand-built pending state so both `pendingCollapse.choices`
        // (with a `CollapseChoice.assignments` map whose key is a
        // complex `QuantumMark`) and `lastMove` are populated.
        val move1 = Move(1, Square(1, 1), Square(1, 2))
        val markA = QuantumMark(Player.X, 1, Square(1, 1))
        val markB = QuantumMark(Player.X, 1, Square(1, 2))
        val state =
            Rules.initial(Variant.QUANTUM_TIC_TAC_TOE).copy(
                quantum = listOf(markA, markB),
                entanglements = listOf(Entanglement(markA, markB)),
                nextMoveNumber = 2,
                lastMove = move1,
                pendingCollapse =
                    PendingCollapse(
                        chooser = Player.O,
                        choices = emptyList(),
                    ),
            )
        assertRoundTrip(state)
    }

    @Test
    fun quantumUltimateRequiredBoardsRoundTrips() {
        val s0 = Rules.initial(Variant.ULTIMATE_QUANTUM_TIC_TAC_TOE)
        val s1 = assertIs<MoveResult.Legal>(Rules.apply(s0, Move(1, Square(5, 3), Square(5, 7)))).nextState
        assertEquals(3 to 7, s1.requiredBoards)
        assertRoundTrip(s1)
    }

    @Test
    fun classicalFreshStateRoundTrips() {
        val state = ClassicalRules.initial(Variant.ULTIMATE_TIC_TAC_TOE)
        assertRoundTrip(state)
    }

    @Test
    fun classicalMidGameStateRoundTrips() {
        var state = ClassicalRules.initial(Variant.ULTIMATE_TIC_TAC_TOE)
        val script =
            listOf(
                ClassicalMove(1, Square(5, 3)),
                ClassicalMove(2, Square(3, 5)),
                ClassicalMove(3, Square(5, 1)),
            )
        for (move in script) {
            state = assertIs<ClassicalMoveResult.Legal>(ClassicalRules.apply(state, move)).nextState
        }
        assertRoundTrip(state)
    }

    @Test
    fun malformedInputReportsFailed() {
        val result = decodeSavedGame("this is not JSON")
        val failed = assertIs<LoadResult.Failed>(result)
        assertTrue(failed.reason.isNotBlank())
    }

    @Test
    fun mismatchedSchemaVersionReportsFailed() {
        // Handcraft a JSON with the sealed-type discriminator but a
        // future schema version this build doesn't know how to read.
        val futureVersion = SavedGame.SCHEMA_VERSION + 42
        val json =
            """
            {
              "type": "quantum",
              "schemaVersion": $futureVersion,
              "state": {
                "variant": "QUANTUM_TIC_TAC_TOE",
                "classical": [],
                "quantum": [],
                "entanglements": [],
                "wonBoards": {},
                "nextMoveNumber": 1
              }
            }
            """.trimIndent()
        val result = decodeSavedGame(json)
        val failed = assertIs<LoadResult.Failed>(result)
        assertTrue(
            failed.reason.contains(futureVersion.toString()),
            "reason should mention the offending schema version, got: ${failed.reason}",
        )
    }

    private fun assertRoundTrip(state: GameState) {
        val encoded = encodeSavedGame(state.toSavedGame())
        val decoded = decodeSavedGame(encoded)
        val ok = assertIs<LoadResult.Ok>(decoded, "encoded form:\n$encoded")
        val quantum = assertIs<SavedGame.Quantum>(ok.saved)
        assertEquals(state, quantum.state)
    }

    private fun assertRoundTrip(state: net.yonge_mallo.uqttt.engine.ClassicalGameState) {
        val encoded = encodeSavedGame(state.toSavedGame())
        val decoded = decodeSavedGame(encoded)
        val ok = assertIs<LoadResult.Ok>(decoded, "encoded form:\n$encoded")
        val classical = assertIs<SavedGame.Classical>(ok.saved)
        assertEquals(state, classical.state)
    }
}
