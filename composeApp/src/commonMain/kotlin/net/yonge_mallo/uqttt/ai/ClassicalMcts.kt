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

package net.yonge_mallo.uqttt.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import net.yonge_mallo.uqttt.engine.BOARD_LINES
import net.yonge_mallo.uqttt.engine.ClassicalGameState
import net.yonge_mallo.uqttt.engine.ClassicalMove
import net.yonge_mallo.uqttt.engine.ClassicalMoveResult
import net.yonge_mallo.uqttt.engine.ClassicalRules
import net.yonge_mallo.uqttt.engine.Player
import net.yonge_mallo.uqttt.engine.Square
import kotlin.coroutines.coroutineContext
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Monte Carlo Tree Search for classical Ultimate. Sibling to `Mcts.kt`;
 * simpler because a classical position has one action shape (`Place`)
 * and no collapse phase, so the tree is just moves and their outcomes.
 * UCB selection, negamax backpropagation, and root parallelism follow
 * the same design as the quantum search so difficulty labels map
 * across both games.
 */

private const val UCB_C = 1.41421356

// A single leaf position gets one weighted-line-count heuristic call;
// no rollouts. The classical branching factor is bounded (81 in the
// opening, dropping to at most 9 once a live send is in play), so
// top-K pruning of the untried set isn't necessary -- every child at
// every node stays reachable.

// --- Heuristic tuning ---------------------------------------------------

// Per-square weight of an open square within a line, contrasted with
// the mover's classical mark (1.0). Purely relative; scaled out by
// `HEURISTIC_SCALE` at the end.
private const val EMPTY_LINE_TERM = 0.25

// A mini-board line is "one move from completion" for a player when
// they hold two of its three squares and the opponent holds none.
// Squares completing or blocking such a line receive a tactical bonus
// (via the prior; see below).
private const val TACTICAL_LINE_MULT = 8.0

// Meta-board weight: winning a mini-board contributes multiple times
// this base weight through the meta-board line scoring, and completing
// a meta-line ends the game. Higher than mini-board weight because
// meta-lines are terminal.
private const val META_BASE_WEIGHT = 5.0

// Flat bonus per mini-board won. See `WIN_BOARD_BONUS` in `Mcts.kt`
// for the reasoning; classical Ultimate uses the same idea.
private const val WIN_BOARD_BONUS = 30.0

// Line contributions in an already-won mini-board are downweighted:
// the mini-board can't change hands, so further squares there don't
// move the position much. Not zero -- filling a won board denies the
// opponent free play into it.
private const val WON_BOARD_LINE_WEIGHT = 0.5

// The sending rule (playing at position `p` sends the opponent to
// mini-board `p`) is deliberately not scored in the heuristic. Its
// strategic value is genuinely ambiguous: sending to a closed board
// grants free play, which is a strong handful on offence too, and
// sending to a board where we've stacked marks is a mixed bag. MCTS
// discovers the dynamics through search rather than baked-in weights.

// Normalises the raw heuristic sum into
// `[-HEURISTIC_BOUND, HEURISTIC_BOUND]` so it stays strictly below a
// real terminal reward (`±1.0`).
private const val HEURISTIC_SCALE = 80.0
private const val HEURISTIC_BOUND = 0.9

suspend fun chooseClassicalMove(
    state: ClassicalGameState,
    maxIterations: Int = DEFAULT_AI_MOVE_ITERATIONS,
    maxTimeMs: Long = DEFAULT_AI_MOVE_TIME_MS,
    random: Random = Random.Default,
): ClassicalMove {
    require(!state.isGameOver) { "chooseClassicalMove called on a finished game" }
    // Yield once so the caller's just-committed state gets a frame to
    // render before we hog Dispatchers.Default. Matters most on Wasm
    // where Default shares the single browser thread.
    yield()
    val workers = defaultMctsWorkers
    val perWorkerIterations = ((maxIterations + workers - 1) / workers).coerceAtLeast(1)
    val roots: List<ClassicalMctsNode> =
        withContext(Dispatchers.Default) {
            if (workers <= 1) {
                listOf(runMcts(state, perWorkerIterations, maxTimeMs, random))
            } else {
                coroutineScope {
                    val seeds = LongArray(workers) { random.nextLong() }
                    (0 until workers)
                        .map { i ->
                            async {
                                runMcts(
                                    state,
                                    perWorkerIterations,
                                    maxTimeMs,
                                    Random(seeds[i]),
                                )
                            }
                        }.awaitAll()
                }
            }
        }
    coroutineContext.ensureActive()
    val merged = HashMap<ClassicalMove, Int>()
    for (root in roots) {
        for (child in root.children) {
            val move = child.moveFromParent ?: continue
            merged[move] = (merged[move] ?: 0) + child.visits
        }
    }
    return merged.maxByOrNull { it.value }?.key
        ?: error("MCTS finished without exploring any child; budget too small?")
}

private suspend fun runMcts(
    state: ClassicalGameState,
    maxIterations: Int,
    maxTimeMs: Long,
    random: Random,
): ClassicalMctsNode {
    val aiPlayer = state.nextPlayer
    val root = ClassicalMctsNode(state, moveFromParent = null, playerWhoActed = null)
    val start = TimeSource.Monotonic.markNow()
    val timeCap = maxTimeMs.milliseconds
    val yieldInterval = 33.milliseconds
    var lastYield = start
    var iterations = 0
    while (iterations < maxIterations && start.elapsedNow() < timeCap) {
        coroutineContext.ensureActive()
        iterate(root, aiPlayer, random)
        iterations++
        if (lastYield.elapsedNow() >= yieldInterval) {
            yield()
            lastYield = TimeSource.Monotonic.markNow()
        }
    }
    return root
}

private fun iterate(
    root: ClassicalMctsNode,
    aiPlayer: Player,
    random: Random,
) {
    val path = mutableListOf(root)
    var node = root
    while (true) {
        if (node.state.isGameOver) break
        node.ensureUntried(random)
        val untried = node.untriedMoves!!
        if (untried.isNotEmpty()) {
            val move = untried.removeAt(untried.lastIndex)
            val newState = node.state.applyMove(move)
            val actor = node.state.nextPlayer
            val child = ClassicalMctsNode(newState, move, actor)
            node.children.add(child)
            node = child
            path.add(node)
            break
        }
        if (node.children.isEmpty()) break
        node = node.selectBestUcb()
        path.add(node)
    }
    val reward = terminalReward(node.state, aiPlayer)
    backpropagate(path, reward, aiPlayer)
}

private fun ClassicalMctsNode.ensureUntried(random: Random) {
    if (untriedMoves != null) return
    val moves = ClassicalRules.legalMoves(state).toMutableList()
    moves.shuffle(random)
    untriedMoves = moves
}

private fun ClassicalMctsNode.selectBestUcb(): ClassicalMctsNode {
    val logParent = ln(visits.toDouble().coerceAtLeast(1.0))
    return children.maxByOrNull { c ->
        if (c.visits == 0) {
            Double.POSITIVE_INFINITY
        } else {
            c.totalReward / c.visits + UCB_C * sqrt(logParent / c.visits)
        }
    } ?: error("selectBestUcb called on a node with no children")
}

private fun terminalReward(
    state: ClassicalGameState,
    aiPlayer: Player,
): Double {
    val winners = state.winners
    return when {
        state.isDraw -> 0.0
        winners.size == 2 -> 0.0
        winners.contains(aiPlayer) -> 1.0
        winners.size == 1 -> -1.0
        else -> heuristic(state, aiPlayer)
    }
}

/**
 * Positional heuristic: sum weighted line contributions per mini-board
 * and per meta-line. A line's contribution is `count^2` on the mover's
 * side, `-count^2` on the opponent's, so concentrating marks scores
 * strictly better than spreading them. Opponent-classical marks on a
 * line block it entirely.
 *
 * Won mini-boards contribute a flat `WIN_BOARD_BONUS` on top of their
 * meta-line participation; mini-board lines inside a decided board
 * are downweighted by `WON_BOARD_LINE_WEIGHT`.
 *
 * Normalised into `[-HEURISTIC_BOUND, HEURISTIC_BOUND]` so it stays
 * below a terminal reward.
 */
private fun heuristic(
    state: ClassicalGameState,
    aiPlayer: Player,
): Double {
    val opp = aiPlayer.opponent
    val classical = state.classical
    var score = 0.0
    for (board in 1..9) {
        val boardDecided = !state.wonBoards[board].isNullOrEmpty()
        val boardMultiplier = if (boardDecided) WON_BOARD_LINE_WEIGHT else 1.0
        for (line in BOARD_LINES) {
            var ai = 0
            var op = 0
            for (position in line) {
                when (classical[Square(board, position)]) {
                    aiPlayer -> ai++
                    opp -> op++
                    else -> {}
                }
            }
            // A line only scores for a side while the other side hasn't
            // planted a classical mark on it (a permanent block).
            if (op == 0) score += (ai * ai + EMPTY_LINE_TERM) * boardMultiplier
            if (ai == 0) score -= (op * op + EMPTY_LINE_TERM) * boardMultiplier
            // Tactical amplifier: a one-move-from-completion line for
            // either side scales toward a decisive multiplier so MCTS
            // doesn't need many rollouts to notice the imminent
            // completion / block. Only applied outside decided boards,
            // where the outcome is fixed.
            if (!boardDecided) {
                if (ai == 2 && op == 0) score += TACTICAL_LINE_MULT
                if (op == 2 && ai == 0) score -= TACTICAL_LINE_MULT
            }
        }
    }
    for ((_, winners) in state.wonBoards) {
        if (aiPlayer in winners) score += WIN_BOARD_BONUS
        if (opp in winners) score -= WIN_BOARD_BONUS
    }
    for (line in BOARD_LINES) {
        var ai = 0
        var op = 0
        var aiBlocked = false
        var opBlocked = false
        for (boardIdx in line) {
            val winners = state.wonBoards[boardIdx]
            if (winners.isNullOrEmpty()) {
                // No winner recorded means either the board has never
                // been touched (still undecided) or was recomputed with
                // no line completed. If every square is nonetheless
                // occupied, the board is drawn -- a permanent blocker
                // for both players since no further marks can land
                // there. An untouched board is empty and trivially
                // fails the fullness check.
                if (isBoardFull(boardIdx, classical)) {
                    aiBlocked = true
                    opBlocked = true
                }
                continue
            }
            // A meta-line is dead for a side only when some board on
            // it is closed against that side -- won solely by the
            // opponent, or drawn. Classical placement rules prevent a
            // single mini-board from being won by both players, so
            // per-side blocking degenerates to opponent-solo blocking
            // in practice; the split still tracks `computeWinners`
            // exactly and stays symmetric with the quantum engine.
            if (aiPlayer in winners) ai++ else aiBlocked = true
            if (opp in winners) op++ else opBlocked = true
        }
        if (!aiBlocked) score += ai * ai * META_BASE_WEIGHT
        if (!opBlocked) score -= op * op * META_BASE_WEIGHT
    }
    return (score / HEURISTIC_SCALE).coerceIn(-HEURISTIC_BOUND, HEURISTIC_BOUND)
}

private fun isBoardFull(
    board: Int,
    classical: Map<Square, Player>,
): Boolean {
    for (position in 1..9) {
        if (Square(board, position) !in classical) return false
    }
    return true
}

private fun backpropagate(
    path: List<ClassicalMctsNode>,
    terminalRewardFromAi: Double,
    aiPlayer: Player,
) {
    for (node in path) {
        node.visits++
        val actor = node.playerWhoActed
        if (actor != null) {
            val r = if (actor == aiPlayer) terminalRewardFromAi else -terminalRewardFromAi
            node.totalReward += r
        }
    }
}

private fun ClassicalGameState.applyMove(move: ClassicalMove): ClassicalGameState =
    when (val r = ClassicalRules.apply(this, move)) {
        is ClassicalMoveResult.Legal -> r.nextState
        is ClassicalMoveResult.Illegal -> error("Illegal move during MCTS: ${r.reason}")
    }

private class ClassicalMctsNode(
    val state: ClassicalGameState,
    val moveFromParent: ClassicalMove?,
    val playerWhoActed: Player?,
) {
    var visits: Int = 0
    var totalReward: Double = 0.0
    var untriedMoves: MutableList<ClassicalMove>? = null
    val children: MutableList<ClassicalMctsNode> = mutableListOf()
}
