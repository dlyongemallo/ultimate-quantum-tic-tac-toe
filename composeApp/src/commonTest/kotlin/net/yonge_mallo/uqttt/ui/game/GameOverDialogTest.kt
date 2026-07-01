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

import net.yonge_mallo.uqttt.engine.GameState
import net.yonge_mallo.uqttt.engine.Player
import net.yonge_mallo.uqttt.engine.Rules
import net.yonge_mallo.uqttt.engine.Square
import net.yonge_mallo.uqttt.engine.Variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The end-of-game dialog title is derived purely from `GameState`'s
 * computed properties, so we pin the mapping down without bringing
 * up a Compose UI host. Draws in particular are essentially
 * unreachable in regular Ultimate play, so a synthetic-state test is
 * the only practical coverage.
 */
class GameOverDialogTest {
    @Test
    fun drawnQuantumBoardYieldsDrawTitle() {
        // 1 2 3    X O X      No row, column, or diagonal is filled
        // 4 5 6 -> X O O      by a single player; the board is full
        // 7 8 9    O X X      classical, no pair can be placed, the
        //                     game is a draw.
        val state =
            GameState(
                variant = Variant.QUANTUM_TIC_TAC_TOE,
                classical =
                    mapOf(
                        Square(1, 1) to Player.X,
                        Square(1, 2) to Player.O,
                        Square(1, 3) to Player.X,
                        Square(1, 4) to Player.X,
                        Square(1, 5) to Player.O,
                        Square(1, 6) to Player.O,
                        Square(1, 7) to Player.O,
                        Square(1, 8) to Player.X,
                        Square(1, 9) to Player.X,
                    ),
                quantum = emptyList(),
                entanglements = emptyList(),
                wonBoards = emptyMap(),
                nextMoveNumber = 10,
            )
        assertTrue(state.isDraw, "synthetic board should classify as a draw")
        assertFalse(state.isSharedWin)
        assertEquals(emptySet<Player>(), state.winners)
        assertEquals("Draw", gameOverOutcomeText(state))
    }

    @Test
    fun singleWinnerYieldsXWinsTitle() {
        val state =
            GameState(
                variant = Variant.QUANTUM_TIC_TAC_TOE,
                classical = emptyMap(),
                quantum = emptyList(),
                entanglements = emptyList(),
                wonBoards = mapOf(1 to setOf(Player.X)),
                nextMoveNumber = 5,
            )
        assertEquals("X wins", gameOverOutcomeText(state))
    }

    @Test
    fun sharedWinYieldsSharedWinTitle() {
        val state =
            GameState(
                variant = Variant.QUANTUM_TIC_TAC_TOE,
                classical = emptyMap(),
                quantum = emptyList(),
                entanglements = emptyList(),
                wonBoards = mapOf(1 to setOf(Player.X, Player.O)),
                nextMoveNumber = 5,
            )
        assertTrue(state.isSharedWin)
        assertEquals("Shared win", gameOverOutcomeText(state))
    }

    @Test
    fun freshStateIsNotTerminalSoTitleFallsThrough() {
        // Defensive: the composable guards on `isGameOver` before
        // showing the dialog, but if anything ever calls
        // `gameOverOutcomeText` on a live state we want a non-empty
        // fallback rather than an exception.
        val state = Rules.initial(Variant.QUANTUM_TIC_TAC_TOE_SQUARED)
        assertFalse(state.isGameOver)
        assertEquals("Game over", gameOverOutcomeText(state))
    }
}
