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
 * The player pairings offered by the opening screen. AI-vs-AI is
 * intentionally not offered: the app is for a human (or two) to play,
 * not to watch.
 */
enum class PlayersConfig(
    val label: String,
    val xKind: PlayerKind,
    val oKind: PlayerKind,
) {
    HUMAN_VS_HUMAN("Human vs Human", PlayerKind.HUMAN, PlayerKind.HUMAN),
}

/** The settings chosen on the opening screen and consumed by the game screen. */
data class GameSetup(
    val variant: Variant,
    val players: PlayersConfig,
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
