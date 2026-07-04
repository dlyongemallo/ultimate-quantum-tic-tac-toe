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

package net.yonge_mallo.uqttt.ui

import androidx.compose.ui.graphics.Color
import net.yonge_mallo.uqttt.engine.Player
import net.yonge_mallo.uqttt.engine.Variant

/**
 * Fixed mark colours -- intentionally not theme tokens so X stays red
 * and O stays green regardless of light / dark or any future dynamic
 * Material You palette. Values are the Material Design red 700 and
 * green 800 used in the original Quantum Tic-Tac-Toe paper.
 */
object PlayerColors {
    val X = Color(0xFFD32F2F)
    val O = Color(0xFF2E7D32)
}

fun colorOf(player: Player): Color =
    when (player) {
        Player.X -> PlayerColors.X
        Player.O -> PlayerColors.O
    }

/**
 * Which kind of agent controls a player's moves. The opening screen
 * lets the user assign a kind to each of X and O via a `PlayersConfig`.
 */
enum class PlayerKind {
    HUMAN,
    AI,
}

/**
 * The four player pairings offered by the opening screen: two human
 * seats, one of each, or a demo where both seats are AI and the user
 * watches. In demo mode the game screen replaces the undo / redo
 * controls with a pause / resume toggle (the undo stack chains
 * through AI states, so a single undo click would drain the whole
 * history).
 */
enum class PlayersConfig(
    val label: String,
    val xKind: PlayerKind,
    val oKind: PlayerKind,
) {
    HUMAN_VS_HUMAN("Human vs Human", PlayerKind.HUMAN, PlayerKind.HUMAN),
    HUMAN_VS_AI("Human vs AI (human is X)", PlayerKind.HUMAN, PlayerKind.AI),
    AI_VS_HUMAN("AI vs Human (AI is X)", PlayerKind.AI, PlayerKind.HUMAN),
    AI_VS_AI("AI vs AI (demo mode)", PlayerKind.AI, PlayerKind.AI),
}

/**
 * AI strength setting. The primary knob is `maxMoveIterations`:
 * the *total* number of MCTS iterations the search performs across
 * all workers. Root parallelism splits this budget across the
 * available cores, so a label corresponds to the same total
 * exploration on every platform. The time caps are a safety net so
 * slow single-threaded targets (Wasm in a browser) don't hang
 * indefinitely on the higher tiers; the search terminates at
 * whichever cap fires first.
 */
enum class Difficulty(
    val label: String,
    val maxMoveIterations: Int,
    val maxMoveTimeMs: Long,
    val maxCollapseIterations: Int,
    val maxCollapseTimeMs: Long,
) {
    BEGINNER("Beginner", 2_000, 3_000, 1_000, 1_000),
    EASY("Easy", 10_000, 6_000, 3_500, 2_000),
    MEDIUM("Medium", 40_000, 10_000, 10_000, 3_000),
    HARD("Hard", 100_000, 15_000, 25_000, 5_000),
    EXPERT("Expert", 250_000, 20_000, 60_000, 8_000),
}

/** The settings chosen on the opening screen and consumed by the game screen. */
data class GameSetup(
    val variant: Variant,
    val players: PlayersConfig,
    val difficulty: Difficulty = Difficulty.MEDIUM,
)

/** The two screens the app currently has; navigation is a `when` on the current value. */
sealed interface Screen {
    data object Menu : Screen

    data class Game(val setup: GameSetup) : Screen
}

/** Human-readable label for a variant, for menu rows and game-screen headers. */
fun Variant.displayName(): String =
    when (this) {
        Variant.QUANTUM_TIC_TAC_TOE -> "Quantum Tic-Tac-Toe"
        Variant.QUANTUM_TIC_TAC_TOE_SQUARED -> "Quantum Tic-Tac-Toe Squared"
        Variant.ULTIMATE_QUANTUM_TIC_TAC_TOE -> "Ultimate Quantum Tic-Tac-Toe"
        Variant.ULTIMATE_TIC_TAC_TOE -> "Ultimate Tic-Tac-Toe"
    }

/** Which kind of agent is on the X / O seat for a given player. */
fun PlayersConfig.kindFor(player: Player): PlayerKind =
    when (player) {
        Player.X -> xKind
        Player.O -> oKind
    }

/** The subset of players controlled by AI; empty for Human-vs-Human. */
fun PlayersConfig.aiPlayers(): Set<Player> =
    setOfNotNull(
        Player.X.takeIf { xKind == PlayerKind.AI },
        Player.O.takeIf { oKind == PlayerKind.AI },
    )
