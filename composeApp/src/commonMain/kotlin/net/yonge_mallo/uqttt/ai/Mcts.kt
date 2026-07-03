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
import net.yonge_mallo.uqttt.engine.CollapseChoice
import net.yonge_mallo.uqttt.engine.GameState
import net.yonge_mallo.uqttt.engine.Move
import net.yonge_mallo.uqttt.engine.MoveResult
import net.yonge_mallo.uqttt.engine.Player
import net.yonge_mallo.uqttt.engine.Rules
import net.yonge_mallo.uqttt.engine.Square
import net.yonge_mallo.uqttt.engine.Variant
import kotlin.coroutines.coroutineContext
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Monte Carlo Tree Search for the engine. One entry per action shape:
 * `chooseMove` for the placing phase and `chooseCollapse` for the
 * resolving phase. Both run on `Dispatchers.Default` and return the
 * action that received the most visits within the time budget.
 *
 * Internally a single unified `Action` (Place or Resolve) drives the
 * tree, so the search treats a placing move and a collapse pick as the
 * same kind of decision. Backpropagation is negamax: each node stores
 * its reward from the perspective of the player who took the action
 * leading to it, so opponents naturally minimise the AI's expected
 * reward without a separate min/max branch -- the collapse-chooser
 * therefore picks adversarially against the AI for free.
 *
 * Constants below are safety defaults for direct callers (tests,
 * scripts) that don't go through `Difficulty`.
 */

const val DEFAULT_AI_MOVE_ITERATIONS: Int = 2_000
const val DEFAULT_AI_MOVE_TIME_MS: Long = 10_000
const val DEFAULT_AI_COLLAPSE_ITERATIONS: Int = 300
const val DEFAULT_AI_COLLAPSE_TIME_MS: Long = 3_000

private const val UCB_C = 1.41421356

suspend fun chooseMove(
    state: GameState,
    maxIterations: Int = DEFAULT_AI_MOVE_ITERATIONS,
    maxTimeMs: Long = DEFAULT_AI_MOVE_TIME_MS,
    random: Random = Random.Default,
): Move {
    require(state.pendingCollapse == null) {
        "chooseMove called on a pending-collapse state"
    }
    require(!state.isGameOver) { "chooseMove called on a finished game" }
    val action = chooseAction(state, maxIterations, maxTimeMs, random)
    return (action as Action.Place).move
}

suspend fun chooseCollapse(
    state: GameState,
    maxIterations: Int = DEFAULT_AI_COLLAPSE_ITERATIONS,
    maxTimeMs: Long = DEFAULT_AI_COLLAPSE_TIME_MS,
    random: Random = Random.Default,
): CollapseChoice {
    require(state.pendingCollapse != null) {
        "chooseCollapse called without a pending collapse"
    }
    val action = chooseAction(state, maxIterations, maxTimeMs, random)
    return (action as Action.Resolve).choice
}

private suspend fun chooseAction(
    state: GameState,
    maxIterations: Int,
    maxTimeMs: Long,
    random: Random,
): Action {
    // Yield once on the caller's dispatcher (typically Main) so any
    // recomposition the caller's just-committed state triggered -- the
    // human's move that launched us -- gets a frame to render before
    // we hog the dispatcher with MCTS. On Wasm this is essential
    // because `Dispatchers.Default` shares the single browser thread.
    yield()
    val workers = defaultMctsWorkers
    // `maxIterations` is the total MCTS iteration budget for this call;
    // divide it (ceiling) across the available workers so a difficulty
    // label corresponds to the same total exploration on every platform.
    // The single-worker path still has its full budget; an N-worker path
    // gives each worker ~maxIterations / N iterations. Wall-clock wins
    // from parallelism are kept (multi-core finishes the same total
    // budget in ~1/N the time). This makes it so that the AI is not
    // stronger at the same difficulty label on a faster machine, just
    // faster.
    // hardware" coupling is removed.
    val perWorkerIterations =
        ((maxIterations + workers - 1) / workers).coerceAtLeast(1)
    val roots: List<MctsNode> =
        withContext(Dispatchers.Default) {
            if (workers <= 1) {
                listOf(runMcts(state, perWorkerIterations, maxTimeMs, random))
            } else {
                // Root parallelisation: each worker grows an independent
                // tree from its own seed; we sum visit counts across
                // root-child branches at the end and pick the most-visited.
                // Variance of the visit-count estimator drops with the
                // number of independent trees.
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
    val merged = HashMap<Action, Int>()
    for (root in roots) {
        for (child in root.children) {
            val action = child.actionFromParent ?: continue
            merged[action] = (merged[action] ?: 0) + child.visits
        }
    }
    return merged.maxByOrNull { it.value }?.key
        ?: error("MCTS finished without exploring any child; budget too small?")
}

private suspend fun runMcts(
    state: GameState,
    maxIterations: Int,
    maxTimeMs: Long,
    random: Random,
): MctsNode {
    val aiPlayer =
        activePlayer(state)
            ?: error("chooseAction called when no player is to move")
    val root = MctsNode(state, actionFromParent = null, playerWhoActed = null)
    val start = TimeSource.Monotonic.markNow()
    val timeCap = maxTimeMs.milliseconds
    // Wall-clock yields every ~33ms so the event loop can render frames
    // and tick the progress-bar timer on single-threaded targets (Wasm).
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
    root: MctsNode,
    aiPlayer: Player,
    random: Random,
) {
    // Selection + (one) expansion.
    val path = mutableListOf(root)
    var node = root
    while (true) {
        if (node.state.isGameOver) break
        node.ensureUntried(random)
        val untried = node.untriedActions!!
        if (untried.isNotEmpty()) {
            val action = untried.removeAt(untried.lastIndex)
            val newState = node.state.applyAction(action)
            val actor = activePlayer(node.state)
            val child = MctsNode(newState, action, actor)
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

private fun MctsNode.ensureUntried(random: Random) {
    if (untriedActions != null) return
    val actions = state.legalActions().toMutableList()
    // High branching factor (3240 legal pairs in fresh Ultimate
    // Quantum) makes MCTS degenerate: every iteration just expands
    // another untried child, the UCB selector never runs at the root,
    // and the final pick is a near-tie on visits. Prune to a
    // fixed-size top-K by a fast positional prior so the search
    // actually discriminates.
    if (actions.size > MAX_UNTRIED_PER_NODE) {
        prioritiseActions(actions, state)
        while (actions.size > MAX_UNTRIED_PER_NODE) actions.removeAt(actions.lastIndex)
    }
    // Shuffle within the kept set so expansion order is unbiased
    // among the top-K -- UCB then differentiates them by reward.
    actions.shuffle(random)
    untriedActions = actions
}

// --- Search and heuristic tuning ---------------------------------------
//
// All knobs the AI exposes for hand-tuning live in this block so they
// can be read and adjusted together. The functions below
// (`prioritiseActions`, `perSquareContribution`, `nonTerminalHeuristic`,
// `terminalReward`) reference each one by name; comments at each
// constant note where it shows up.

// Top-K cap on the action list each node retains for expansion. A
// fresh Ultimate Quantum position has ~3240 legal pairs; without
// pruning every `iterate()` would expand a fresh untried child and
// the UCB selector at the root would never run. K is chosen so that
// even at the shallowest difficulty (`Difficulty.BEGINNER` = 2 000
// iterations) each surviving root child gets several visits.
private const val MAX_UNTRIED_PER_NODE = 40

// Per-square weight of a quantum mark vs a classical mark (1.0) on a
// line, used by both `perSquareContribution` and `nonTerminalHeuristic`.
// A quantum mark on a square may or may not collapse to that square,
// so it counts as half-presence.
private const val SPOOKY_WEIGHT = 0.5

// Marginal bonus added to each line term in `perSquareContribution`,
// equal to the constant from the expansion `(w + 0.5)^2 - w^2 = w +
// 0.25`. Floors the per-line contribution of an empty line at 0.25
// rather than 0 so a square on an unblocked but empty line still
// outranks one on a fully-blocked line.
private const val LINE_MARGINAL_BONUS = 0.25

// Multiplier on a square's prior and on its lines' heuristic
// contributions when the square sits in a mini-board whose outcome
// has already been decided. Not zero so the AI can still reach for
// the strategic exceptions (turning a lost mini-board into a shared
// win, or using its empty squares as part of a loop forcing a
// collapse elsewhere) when nothing better is available. Set high
// enough that winning a "rich" mini-board (one packed with the
// AI's spooky marks) doesn't strictly *lower* the heuristic via the
// per-line downweighting -- `WIN_BOARD_BONUS` below acts as a
// second backstop on the same case.
private const val WON_BOARD_LINE_WEIGHT = 0.5

// Flat heuristic credit per won mini-board (the AI gains it, the
// opponent costs it). Sits on top of the per-meta-line scoring so
// that winning a board is structurally a positive heuristic event
// even when the won board's downweighted line contributions don't
// fully recover the rich pre-win position they replace. Without
// this, MCTS would prefer to keep accumulating spooky marks on a
// mini-board it could win, because the won-board penalty (above)
// cuts those mini-board line scores faster than meta-line scoring
// makes them up.
private const val WIN_BOARD_BONUS = 30.0

// A line (mini-board or meta-board) is "one move from completion"
// when one side's weighted presence reaches this threshold and the
// other side has no classical mark blocking it. At
// `SPOOKY_WEIGHT = 0.5`, 2.0 corresponds to "two classical marks", or
// "one classical mark plus two spooky marks", or "four spooky marks".
private const val TACTICAL_THREAT_THRESHOLD = 2.0

// Flat prior bonus added to every open square that, if marked, would
// complete or block a one-move-from-completion threat. Set large
// enough that tactical-block squares always survive the top-K
// pruning -- typical line-contribution sums sit well below this.
private const val TACTICAL_BLOCK_BONUS = 10.0

// Multiplier on the count^2 score for a meta-line term in
// `nonTerminalHeuristic`. A won mini-board on an unblocked meta-line
// counts `count^2 * META_BASE_WEIGHT`; 5x because completing a
// meta-line decides the whole game, whereas completing a single
// mini-board only contributes one meta-line square.
private const val META_BASE_WEIGHT = 5.0

// Normalises the unbounded sum returned by `nonTerminalHeuristic`
// into `[-HEURISTIC_BOUND, HEURISTIC_BOUND]` so it stays strictly
// below a real terminal win (`±1.0`) -- MCTS will still prefer
// actually winning to merely being well-positioned. Tune alongside
// the per-line weights above if their balance changes.
private const val HEURISTIC_SCALE = 80.0
private const val HEURISTIC_BOUND = 0.9

/**
 * Sort `actions` in place so the highest-prior actions come first.
 * Pair priors approximate the marginal heuristic gain from playing
 * the pair: for each endpoint, sum over the lines through it the
 * mover's current weighted presence (classical 1.0, spooky
 * `SPOOKY_WEIGHT`) on unblocked lines. Endpoints that sit on lines
 * the mover already threatens score higher, so the search prefers
 * concentrating marks into existing lines -- the kind of placement
 * that builds toward a cycle-closing win.
 */
private fun prioritiseActions(
    actions: MutableList<Action>,
    state: GameState,
) {
    val mover = activePlayer(state) ?: return
    val contrib = perSquareContribution(state, mover)
    actions.sortByDescending { action ->
        if (action is Action.Place) {
            (contrib[action.move.a] ?: 0.0) + (contrib[action.move.b] ?: 0.0)
        } else {
            0.0
        }
    }
}

/**
 * Per-square placement value for `player`, summed over all lines
 * through each square. The value is symmetric in attack and defence:
 * a line contributes its attack term (`pW + LINE_MARGINAL_BONUS` --
 * how much placing a spooky mark here builds toward `player`'s own
 * `count^2` score) and its defence term (`oW + LINE_MARGINAL_BONUS`
 * -- how much that placement denies the opponent's symmetric line
 * score), each gated on the *other* side not already having a
 * classical mark on the line (which would lock it one way and make
 * further marks on it inert).
 *
 * Including the defence term is what lets blocking moves survive the
 * top-K pruning in `ensureUntried`: a square that sits on an
 * opponent's two-of-three threat scores highly even when it
 * contributes nothing to the mover's own lines, so it stays in the
 * candidate set and MCTS can discover that it averts an imminent
 * loss. Without it the prior would only reward building, leaving the
 * search blind to threats it has to stop.
 *
 * Two further adjustments narrow the AI's focus:
 *
 *  - In Ultimate Quantum, line contributions for a square in a
 *    mini-board whose outcome is already decided are scaled by
 *    `WON_BOARD_LINE_WEIGHT`. The line still matters a little (a
 *    sliver, not zero, so the strategic exceptions remain reachable),
 *    but the AI avoids spending top-K slots on dead territory.
 *
 *  - Squares on a "one move from completion" line for *either* side
 *    receive a flat `TACTICAL_BLOCK_BONUS` -- big enough to dominate
 *    typical line-sum contributions, so the move always survives the
 *    pruning step. In Ultimate Quantum the same bonus extends to any
 *    open square in the third mini-board of an unblocked meta-line
 *    where one side has already won the other two, since that
 *    mini-board's outcome decides the meta-line.
 */
private fun perSquareContribution(
    state: GameState,
    player: Player,
): Map<Square, Double> {
    val opp = player.opponent
    val classical = state.classical
    val playerSpooky = HashSet<Square>()
    val oppSpooky = HashSet<Square>()
    for (qm in state.quantum) {
        if (qm.player == player) playerSpooky.add(qm.square) else oppSpooky.add(qm.square)
    }
    val contrib = HashMap<Square, Double>()
    val boards = if (state.variant == Variant.QUANTUM_TIC_TAC_TOE) 1..1 else 1..9
    for (board in boards) {
        val boardDecided = !state.wonBoards[board].isNullOrEmpty()
        val boardMultiplier = if (boardDecided) WON_BOARD_LINE_WEIGHT else 1.0
        for (line in BOARD_LINES) {
            var pW = 0.0
            var oW = 0.0
            var playerClassical = 0
            var oppClassical = 0
            for (pos in line) {
                val sq = Square(board, pos)
                val c = classical[sq]
                when {
                    c == player -> {
                        pW += 1.0
                        playerClassical++
                    }
                    c == opp -> {
                        oW += 1.0
                        oppClassical++
                    }
                    else -> {
                        if (sq in playerSpooky) pW += SPOOKY_WEIGHT
                        if (sq in oppSpooky) oW += SPOOKY_WEIGHT
                    }
                }
            }
            val attackPer = if (oppClassical == 0) pW + LINE_MARGINAL_BONUS else 0.0
            val defencePer = if (playerClassical == 0) oW + LINE_MARGINAL_BONUS else 0.0
            val lineContribution = (attackPer + defencePer) * boardMultiplier
            // Tactical threat: a one-move-from-completion line for
            // either side. The bonus is *not* scaled by
            // `boardMultiplier` -- a winning or blocking move keeps
            // its tactical priority even in a decided mini-board
            // (placement there can still close a cycle and trigger a
            // collapse that flips the meta-line).
            val playerThreat = oppClassical == 0 && pW >= TACTICAL_THREAT_THRESHOLD
            val oppThreat = playerClassical == 0 && oW >= TACTICAL_THREAT_THRESHOLD
            val tacticalBonus = if (playerThreat || oppThreat) TACTICAL_BLOCK_BONUS else 0.0
            val per = lineContribution + tacticalBonus
            if (per == 0.0) continue
            for (pos in line) {
                val sq = Square(board, pos)
                if (classical[sq] == null) {
                    contrib[sq] = (contrib[sq] ?: 0.0) + per
                }
            }
        }
    }
    // Meta-board tactical threat (Squared and Ultimate Quantum): if
    // one side has already won two mini-boards on an unblocked
    // meta-line and the third is still winnable, any open square in
    // that third mini-board gets the tactical bonus. A meta-line is
    // blocked for a side only if some board on it is closed against
    // that side -- i.e., won solely by the opponent or drawn. A
    // shared mini-board (both players in `winners`) counts for both
    // sides and blocks neither, because `computeWinners` also credits
    // it to both.
    if (state.variant == Variant.QUANTUM_TIC_TAC_TOE_SQUARED ||
        state.variant == Variant.ULTIMATE_QUANTUM_TIC_TAC_TOE
    ) {
        for (line in BOARD_LINES) {
            var pBoards = 0
            var oBoards = 0
            var undecidedBoardIdx = -1
            var undecidedCount = 0
            var pBlocked = false
            var oBlocked = false
            for (boardIdx in line) {
                val winners = state.wonBoards[boardIdx]
                if (winners.isNullOrEmpty()) {
                    if (isBoardFull(boardIdx, classical)) {
                        pBlocked = true
                        oBlocked = true
                    } else {
                        undecidedBoardIdx = boardIdx
                        undecidedCount++
                    }
                } else {
                    if (player in winners) pBoards++ else pBlocked = true
                    if (opp in winners) oBoards++ else oBlocked = true
                }
            }
            if (undecidedCount != 1) continue
            val playerMetaThreat = pBoards == 2 && !pBlocked
            val oppMetaThreat = oBoards == 2 && !oBlocked
            if (!playerMetaThreat && !oppMetaThreat) continue
            for (pos in 1..9) {
                val sq = Square(undecidedBoardIdx, pos)
                if (classical[sq] == null) {
                    contrib[sq] = (contrib[sq] ?: 0.0) + TACTICAL_BLOCK_BONUS
                }
            }
        }
    }
    return contrib
}

/**
 * Whether every square in `board` carries a classical mark. A
 * mini-board with no winner (`wonBoards[board]` empty) that is
 * nonetheless full is drawn -- no further placements can land there,
 * so it permanently blocks any meta-line it sits on. Used by the
 * heuristic paths that walk meta-lines to distinguish drawn from
 * still-in-progress mini-boards.
 */
private fun isBoardFull(
    board: Int,
    classical: Map<Square, Player>,
): Boolean {
    for (position in 1..9) {
        if (Square(board, position) !in classical) return false
    }
    return true
}

private fun MctsNode.selectBestUcb(): MctsNode {
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
    state: GameState,
    aiPlayer: Player,
): Double {
    val winners = state.winners
    return when {
        // A shared win and a no-winner draw both score 0.0; without
        // the explicit draw branch a drawn state would fall through to
        // `nonTerminalHeuristic`, which is defined only for in-progress
        // play and could return a non-zero value on contrived boards.
        state.isDraw -> 0.0
        winners.size == 2 -> 0.0
        winners.contains(aiPlayer) -> 1.0
        winners.size == 1 -> -1.0
        state.pendingCollapse != null -> resolveAwareReward(state, aiPlayer)
        else -> nonTerminalHeuristic(state, aiPlayer)
    }
}

/**
 * Evaluate a `pendingCollapse` state by resolving each offered choice
 * and returning the value the chooser would actually pick (max for
 * the AI, min for the opponent). Without this, the heuristic on a
 * pending state would describe the *pre*-collapse position -- which
 * still hides the impending win or loss that the resolved board will
 * make terminal -- so MCTS would need an extra ply of search to find
 * it. Resolving inline collapses that ply into the leaf evaluation
 * itself, at fixed two-resolve cost.
 *
 * Recursion is one level deep at most: `Rules.resolve` clears
 * `pendingCollapse` (collapse cascades are folded into the single
 * resolution), so the inner `terminalReward` call cannot re-enter
 * this branch.
 */
private fun resolveAwareReward(
    state: GameState,
    aiPlayer: Player,
): Double {
    val pending = state.pendingCollapse!!
    val values =
        pending.choices.map { choice ->
            terminalReward(Rules.resolve(state, choice), aiPlayer)
        }
    return if (pending.chooser == aiPlayer) values.max() else values.min()
}

/**
 * Spooky-aware positional heuristic, applied per mini-board (classical
 * + quantum lines) and -- in Ultimate Quantum -- per meta-board
 * (won-mini-board lines). For each 3-square line we tally a weighted
 * presence count per player: a classical mark contributes 1.0, a
 * quantum mark on a not-yet-collapsed square contributes
 * `SPOOKY_WEIGHT` (less than a real mark because the collapse might
 * land elsewhere, but still real potential). Counting quantum presence
 * gives MCTS a non-zero gradient before any cycle has closed --
 * otherwise the heuristic would return 0 for the entire pre-collapse
 * phase of an Ultimate Quantum game and the search would have nothing
 * to prefer one early move over another.
 *
 * Line score is `weight^2` if the opponent has *no classical* mark on
 * the line; a classical opponent mark permanently blocks it, whereas
 * a spooky opponent mark only competes for it (collapse may free the
 * square again), so lines where both sides have only quantum presence
 * still register on both sides' books and net out. Squaring the
 * count makes concentration strictly better than spreading
 * (`2^2 > 1 + 1`), which is the right incentive: a line with two of
 * a player's marks is one tempo from completion, two singletons on
 * different lines are several.
 *
 * Line scores in mini-boards whose outcome is already decided are
 * scaled by `WON_BOARD_LINE_WEIGHT`, since further marks there don't
 * change the meta-game. Won mini-boards in unblocked meta-lines
 * score the same `count^2` shape with weight `META_BASE_WEIGHT`,
 * heavier because completing a meta-line decides the whole game.
 *
 * The total is normalised into `[-HEURISTIC_BOUND, HEURISTIC_BOUND]`
 * so it stays strictly below a terminal win (`±1.0`) and the search
 * will still prefer actually winning to merely being well-positioned.
 */
private fun nonTerminalHeuristic(
    state: GameState,
    aiPlayer: Player,
): Double {
    val opp = aiPlayer.opponent
    val classical = state.classical
    // Build per-square spooky presence once; lookups inside the line
    // loop are O(1). Sets are small (one entry per square that
    // currently hosts at least one mark of that player).
    val aiSpooky = HashSet<Square>()
    val oppSpooky = HashSet<Square>()
    for (qm in state.quantum) {
        if (qm.player == aiPlayer) aiSpooky.add(qm.square) else oppSpooky.add(qm.square)
    }
    var score = 0.0
    for (board in 1..9) {
        if (state.variant == Variant.QUANTUM_TIC_TAC_TOE && board != 1) break
        val boardDecided = !state.wonBoards[board].isNullOrEmpty()
        val boardMultiplier = if (boardDecided) WON_BOARD_LINE_WEIGHT else 1.0
        for (line in BOARD_LINES) {
            var aiW = 0.0
            var oppW = 0.0
            var aiClassical = 0
            var oppClassical = 0
            for (pos in line) {
                val sq = Square(board, pos)
                val c = classical[sq]
                when {
                    c == aiPlayer -> {
                        aiW += 1.0
                        aiClassical++
                    }
                    c == opp -> {
                        oppW += 1.0
                        oppClassical++
                    }
                    else -> {
                        // A square may host marks of both players (a
                        // contested square); each contributes to its
                        // own side's weighted count.
                        if (sq in aiSpooky) aiW += SPOOKY_WEIGHT
                        if (sq in oppSpooky) oppW += SPOOKY_WEIGHT
                    }
                }
            }
            // Only a classical opponent mark fully blocks the line; a
            // spooky opponent mark on it doesn't, since collapse may
            // remove it from this square.
            if (oppClassical == 0) score += aiW * aiW * boardMultiplier
            if (aiClassical == 0) score -= oppW * oppW * boardMultiplier
        }
    }
    if (state.variant == Variant.QUANTUM_TIC_TAC_TOE_SQUARED ||
        state.variant == Variant.ULTIMATE_QUANTUM_TIC_TAC_TOE
    ) {
        // Flat per-won-board credit -- structurally guarantees that
        // winning a mini-board is a net positive heuristic event even
        // when the won board's downweighted line contributions don't
        // fully recover its rich pre-win position. Shared wins
        // contribute to both sides and net out.
        for ((_, winners) in state.wonBoards) {
            if (aiPlayer in winners) score += WIN_BOARD_BONUS
            if (opp in winners) score -= WIN_BOARD_BONUS
        }
        // Meta-line scoring. A meta-line is dead for a side only when
        // some board on it is closed against that side -- won solely
        // by the opponent, or drawn (no winner but every square
        // classical). A shared mini-board (both players in `winners`)
        // counts for both sides and blocks neither, matching how
        // `computeWinners` credits it to both.
        for (line in BOARD_LINES) {
            var ai = 0
            var op = 0
            var aiBlocked = false
            var opBlocked = false
            for (boardIdx in line) {
                val winners = state.wonBoards[boardIdx]
                if (winners.isNullOrEmpty()) {
                    if (isBoardFull(boardIdx, classical)) {
                        aiBlocked = true
                        opBlocked = true
                    }
                    continue
                }
                if (aiPlayer in winners) ai++ else aiBlocked = true
                if (opp in winners) op++ else opBlocked = true
            }
            if (!aiBlocked) score += ai * ai * META_BASE_WEIGHT
            if (!opBlocked) score -= op * op * META_BASE_WEIGHT
        }
    }
    return (score / HEURISTIC_SCALE).coerceIn(-HEURISTIC_BOUND, HEURISTIC_BOUND)
}

private fun backpropagate(
    path: List<MctsNode>,
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

// -- Action / state helpers ----------------------------------------------

internal sealed interface Action {
    data class Place(val move: Move) : Action

    data class Resolve(val choice: CollapseChoice) : Action
}

internal fun GameState.legalActions(): List<Action> {
    val pending = pendingCollapse
    return when {
        pending != null -> pending.choices.map(Action::Resolve)
        isGameOver -> emptyList()
        else -> Rules.legalMoves(this).map(Action::Place).toList()
    }
}

internal fun GameState.applyAction(action: Action): GameState =
    when (action) {
        is Action.Place ->
            when (val r = Rules.apply(this, action.move)) {
                is MoveResult.Legal -> r.nextState
                is MoveResult.TriggersCollapse -> r.pendingState
                is MoveResult.Illegal -> error("Illegal move during MCTS: ${r.reason}")
            }

        is Action.Resolve -> Rules.resolve(this, action.choice)
    }

/** Who is to move (or to choose, during collapse). Null when the game is over. */
internal fun activePlayer(state: GameState): Player? {
    val pending = state.pendingCollapse
    return when {
        state.isGameOver -> null
        pending != null -> pending.chooser
        else -> state.nextPlayer
    }
}

// -- Node ----------------------------------------------------------------

internal class MctsNode(
    val state: GameState,
    val actionFromParent: Action?,
    val playerWhoActed: Player?,
) {
    var visits: Int = 0
    var totalReward: Double = 0.0
    var untriedActions: MutableList<Action>? = null
    val children: MutableList<MctsNode> = mutableListOf()
}
