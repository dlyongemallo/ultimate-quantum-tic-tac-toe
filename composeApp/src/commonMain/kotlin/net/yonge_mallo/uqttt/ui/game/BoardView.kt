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

import net.yonge_mallo.uqttt.engine.ClassicalGameState
import net.yonge_mallo.uqttt.engine.Entanglement
import net.yonge_mallo.uqttt.engine.GameState
import net.yonge_mallo.uqttt.engine.Player
import net.yonge_mallo.uqttt.engine.QuantumMark
import net.yonge_mallo.uqttt.engine.Square
import net.yonge_mallo.uqttt.engine.Variant

/**
 * The subset of game state `BoardCanvas` needs to draw a position.
 * Both the quantum `GameState` and the classical `ClassicalGameState`
 * project into this shape, so `BoardCanvas` stays engine-agnostic and
 * `GameScreen` / `ClassicalGameScreen` each hand it their own state
 * translated through `toBoardView`.
 *
 * `quantum` and `entanglements` are empty for the classical variant
 * (the canvas simply draws nothing for those slots). `lastMoveSquares`
 * holds two entries for a quantum pair and one for a classical mark;
 * empty on a fresh game. `requiredBoards` is the sending-rule
 * highlight (`Pair(b, d)` in quantum, `Pair(b, b)` in classical).
 */
data class BoardView(
    val variant: Variant,
    val classical: Map<Square, Player>,
    val quantum: List<QuantumMark>,
    val entanglements: List<Entanglement>,
    val wonBoards: Map<Int, Set<Player>>,
    val lastMoveSquares: Set<Square>,
    val requiredBoards: Pair<Int, Int>?,
)

fun GameState.toBoardView(): BoardView =
    BoardView(
        variant = variant,
        classical = classical,
        quantum = quantum,
        entanglements = entanglements,
        wonBoards = wonBoards,
        lastMoveSquares = lastMove?.let { setOf(it.a, it.b) } ?: emptySet(),
        requiredBoards = requiredBoards,
    )

fun ClassicalGameState.toBoardView(): BoardView =
    BoardView(
        variant = variant,
        classical = classical,
        quantum = emptyList(),
        entanglements = emptyList(),
        wonBoards = wonBoards,
        lastMoveSquares = lastMove?.square?.let { setOf(it) } ?: emptySet(),
        requiredBoards = requiredBoard?.let { Pair(it, it) },
    )
