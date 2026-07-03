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

// Ultimate Quantum Tic-Tac-Toe -- quantum rules engine.
//
// Immutable domain types plus the apply/resolve/legalMoves API consumed
// by the UI and the AI. Three quantum variants share this engine:
// Goff's original Quantum Tic-Tac-Toe on a single mini-board, Quantum
// Tic-Tac-Toe Squared on a 3x3 grid of mini-boards (per-pair inter-board
// entanglement constraint, meta-board win condition), and Ultimate
// Quantum Tic-Tac-Toe which adds the classical-Ultimate sending rule on
// top of Squared. All three share the same move placement, loop
// detection, and collapse cascade machinery. The classical parent
// game (`ULTIMATE_TIC_TAC_TOE`) is handled by `ClassicalRules` in
// `ClassicalRulesEngine.kt`; it shares the `Variant` enum and helpers
// like `BOARD_LINES` / `recomputeWonBoards` / `computeWinners` but has
// its own state and move types.
//
// Mini-boards are numbered 1..9 and squares within a mini-board are
// numbered 1..9, both in reading order:
// (1 = top-left, 2 = top-centre, 3 = top-right,
//  4 = middle-left, 5 = centre, 6 = middle-right,
//  7 = bottom-left, 8 = bottom-centre, 9 = bottom-right).
package net.yonge_mallo.uqttt.engine

// ---------------------------------------------------------------------------
// Variants.
// ---------------------------------------------------------------------------

/**
 * The supported games. Three of the four are quantum and share
 * `Rules` / `GameState`; the fourth is the classical parent game and
 * uses `ClassicalRules` / `ClassicalGameState` instead. `isClassical`
 * / `isQuantum` project each variant onto the family that owns its
 * engine, so UI-layer code that has to hold "either kind" (menu
 * selection, screen routing) doesn't have to enumerate cases.
 *
 * In `QUANTUM_TIC_TAC_TOE`, every square sits on mini-board 1 and the
 * inter-board entanglement constraint is vacuous; the game is won by
 * completing a classical line on the single board.
 *
 * `QUANTUM_TIC_TAC_TOE_SQUARED` uses all nine mini-boards, restricts
 * entanglements between distinct mini-boards to one per pair, and is won
 * by completing three won mini-boards in a row on the meta-board.
 *
 * `ULTIMATE_QUANTUM_TIC_TAC_TOE` is `QUANTUM_TIC_TAC_TOE_SQUARED` plus
 * the classical-Ultimate sending rule lifted to quantum pairs: a
 * placement at `(A/b, C/d)` forces the opponent's next pair to span
 * mini-boards `b` and `d` (or play intra-board within `b` if `b == d`),
 * unless that's unsatisfiable -- in which case the opponent has free
 * play. The sending rule is what makes this variant *Ultimate*; without
 * it the meta-board is just a parallel pile of mini-boards.
 *
 * `ULTIMATE_TIC_TAC_TOE` is the classical parent game: single-square
 * placements on a 3x3 meta-grid of 3x3 mini-boards, with the classical
 * sending rule (a move at position `p` sends the opponent to
 * mini-board `p`, unless that board is already won or full -- then
 * they play freely). Meta-board win condition matches
 * `QUANTUM_TIC_TAC_TOE_SQUARED`. Has no quantum pairs, entanglements,
 * or collapses; players see only their marks.
 */
enum class Variant {
    QUANTUM_TIC_TAC_TOE,
    QUANTUM_TIC_TAC_TOE_SQUARED,
    ULTIMATE_QUANTUM_TIC_TAC_TOE,
    ULTIMATE_TIC_TAC_TOE,
    ;

    val isClassical: Boolean get() = this == ULTIMATE_TIC_TAC_TOE
    val isQuantum: Boolean get() = !isClassical
}

// ---------------------------------------------------------------------------
// Players and coordinates.
// ---------------------------------------------------------------------------

enum class Player {
    X,
    O,
    ;

    val opponent: Player get() = if (this == X) O else X
}

/**
 * A square is identified by the mini-board it sits in (1..9) and its
 * position within that mini-board (1..9). Rendered as "B/S" to match the
 * paper's notation, e.g. Square(3, 1) prints as "3/1".
 */
data class Square(val board: Int, val position: Int) {
    init {
        require(board in 1..9) { "board must be 1..9, got $board" }
        require(position in 1..9) { "position must be 1..9, got $position" }
    }

    override fun toString(): String = "$board/$position"
}

// ---------------------------------------------------------------------------
// Marks.
// ---------------------------------------------------------------------------

/**
 * A quantum mark: one of the two halves of a placed entangled pair. It
 * sits in a particular square but has not yet collapsed to that square as
 * its final position. Several quantum marks may share a square.
 */
data class QuantumMark(
    val player: Player,
    val moveNumber: Int,
    val square: Square,
)

/**
 * A single entanglement: the edge joining the two quantum marks of one
 * move. By construction the two marks share player and moveNumber and
 * differ only in their square.
 */
data class Entanglement(val a: QuantumMark, val b: QuantumMark) {
    init {
        require(a.moveNumber == b.moveNumber) { "endpoints share a move number" }
        require(a.player == b.player) { "endpoints share a player" }
        require(a.square != b.square) { "endpoints occupy distinct squares" }
    }

    val moveNumber: Int get() = a.moveNumber
    val player: Player get() = a.player
    val isIntraBoard: Boolean get() = a.square.board == b.square.board

    /** The two mini-boards this edge spans, unordered. */
    val boards: Set<Int> get() = setOf(a.square.board, b.square.board)
}

// ---------------------------------------------------------------------------
// Moves and their outcomes.
// ---------------------------------------------------------------------------

/**
 * The act of placing an entangled pair. The move number is 1-based and
 * its parity determines the player (X for odd, O for even).
 */
data class Move(val number: Int, val a: Square, val b: Square) {
    init {
        require(number >= 1) { "move number is 1-based" }
        require(a != b) { "a pair's two endpoints must be distinct squares" }
    }

    val player: Player get() = if (number % 2 == 1) Player.X else Player.O
    val isIntraBoard: Boolean get() = a.board == b.board
}

/**
 * Why a move was rejected. Reasons are enumerated so the UI can surface
 * a specific message without parsing strings. `BOARD_NOT_IN_VARIANT`
 * fires only in Quantum Tic-Tac-Toe, where mini-board 1 is the sole
 * board in play.
 */
enum class IllegalReason {
    SQUARE_IS_CLASSICAL,
    DUPLICATE_PAIR,
    DUPLICATE_INTER_BOARD_ENTANGLEMENT,
    NOT_YOUR_TURN,
    PENDING_COLLAPSE_UNRESOLVED,
    BOARD_NOT_IN_VARIANT,
    GAME_OVER,
    WRONG_SENT_BOARDS,
}

/**
 * The outcome of attempting a move. A legal move either advances the
 * state directly or triggers a collapse; in the latter case the caller
 * must select one of the offered resolutions before further play.
 */
sealed interface MoveResult {
    data class Illegal(val reason: IllegalReason) : MoveResult

    data class Legal(val nextState: GameState) : MoveResult

    data class TriggersCollapse(
        val pendingState: GameState,
        val choices: List<CollapseChoice>,
    ) : MoveResult
}

/**
 * One of the two ways a closing loop can resolve. Each quantum mark
 * affected by the cascade (those in the loop plus those in trees hanging
 * off it) is mapped to the square it will become classical at. All
 * other quantum marks belonging to the same moves vanish on resolution.
 */
data class CollapseChoice(
    val id: Int,
    val assignments: Map<QuantumMark, Square>,
)

// ---------------------------------------------------------------------------
// Game state.
// ---------------------------------------------------------------------------

/**
 * Immutable snapshot of the game. All mutation goes through Rules.apply
 * and Rules.resolve, which return a fresh GameState.
 *
 * - `variant` selects which game's rules apply.
 * - `classical` is the map of resolved squares to the player whose mark
 *   lives there permanently.
 * - `quantum` is the multiset of unresolved quantum marks (multiple marks
 *   may share a square).
 * - `entanglements` is the set of edges linking pairs.
 * - `wonBoards` maps mini-board index to the set of players who have a
 *   line in it; a shared mini-board has both players in the set.
 * - `nextMoveNumber` is what the next placed pair's number will be.
 * - `pendingCollapse` is non-null while waiting for the opponent to pick
 *   between the offered resolutions.
 * - `lastMove` is the entangled pair the UI should highlight; null on a
 *   fresh game. Set on both `apply` paths and preserved across
 *   `resolve` so the highlight remains until the next pair is placed.
 * - `requiredBoards` is the (b, d) constraint for the next placement under
 *   the sending-rule variant: the next pair must span mini-boards `b` and
 *   `d` (or play intra-board within `b` if `b == d`). Null for the other
 *   variants and for the very first move of a sending-rule game; null
 *   inside the sending-rule game on the turns where the sender's positions
 *   produced an unsatisfiable constraint (the opponent then has free play).
 */
data class GameState(
    val variant: Variant,
    val classical: Map<Square, Player>,
    val quantum: List<QuantumMark>,
    val entanglements: List<Entanglement>,
    val wonBoards: Map<Int, Set<Player>>,
    val nextMoveNumber: Int,
    val pendingCollapse: PendingCollapse? = null,
    val lastMove: Move? = null,
    val requiredBoards: Pair<Int, Int>? = null,
) {
    val nextPlayer: Player
        get() = if (nextMoveNumber % 2 == 1) Player.X else Player.O

    /**
     * The set of players who have currently won the meta-game (Squared
     * or Ultimate Quantum) or the single mini-board (Quantum). Size 0
     * = nobody yet, size 1 = a single winner, size 2 = a shared win
     * (both players completed winning lines in the same collapse).
     */
    val winners: Set<Player> get() = computeWinners(variant, wonBoards)

    /** The unique winner, or null if there is none yet or the game is a shared win. */
    val winner: Player? get() = winners.singleOrNull()

    val isSharedWin: Boolean get() = winners.size == 2

    /** A draw is "no winner and no legal continuation"; collapse phases are not draws. */
    val isDraw: Boolean
        get() =
            pendingCollapse == null &&
                winners.isEmpty() &&
                Rules.legalMoves(this).none()

    val isGameOver: Boolean get() = winners.isNotEmpty() || isDraw
}

/**
 * The intermediate state between a collapse-triggering move and the
 * choice that resolves it. The chooser is the player who did NOT close
 * the loop -- typically the opponent of the player who just moved.
 */
data class PendingCollapse(
    val chooser: Player,
    val choices: List<CollapseChoice>,
)

// ---------------------------------------------------------------------------
// Rules.
// ---------------------------------------------------------------------------

object Rules {
    /** A fresh game with no marks placed in the chosen variant. */
    fun initial(variant: Variant): GameState {
        require(variant.isQuantum) {
            "Rules.initial is for quantum variants only; use ClassicalRules.initial for $variant"
        }
        return GameState(
            variant = variant,
            classical = emptyMap(),
            quantum = emptyList(),
            entanglements = emptyList(),
            wonBoards = emptyMap(),
            nextMoveNumber = 1,
        )
    }

    /**
     * Attempt to play a move. Returns Legal, TriggersCollapse, or
     * Illegal. A move is illegal if a collapse is still pending, if it
     * is not the mover's turn, if either endpoint lies outside the
     * variant's active boards or is already classical, or if an
     * inter-board pair would duplicate an existing entanglement between
     * the same two mini-boards.
     */
    fun apply(
        state: GameState,
        move: Move,
    ): MoveResult {
        // Terminal-state guard. A finished game has no further legal
        // moves; rejecting up front keeps callers from constructing
        // post-game quantum marks against a state that should be
        // immutable, even though the in-app UI gates moves on
        // `isGameOver` separately.
        if (state.isGameOver) {
            return MoveResult.Illegal(IllegalReason.GAME_OVER)
        }
        if (state.pendingCollapse != null) {
            return MoveResult.Illegal(IllegalReason.PENDING_COLLAPSE_UNRESOLVED)
        }
        if (move.number != state.nextMoveNumber || move.player != state.nextPlayer) {
            return MoveResult.Illegal(IllegalReason.NOT_YOUR_TURN)
        }
        if (state.variant == Variant.QUANTUM_TIC_TAC_TOE &&
            (move.a.board != 1 || move.b.board != 1)
        ) {
            return MoveResult.Illegal(IllegalReason.BOARD_NOT_IN_VARIANT)
        }
        if (move.a in state.classical || move.b in state.classical) {
            return MoveResult.Illegal(IllegalReason.SQUARE_IS_CLASSICAL)
        }
        // Sending rule. The previous move's position pair constrains
        // which two mini-boards the next placement may span; if the
        // constraint isn't satisfiable on this state the opponent gets
        // free play and the check falls through silently.
        if (state.variant == Variant.ULTIMATE_QUANTUM_TIC_TAC_TOE) {
            val required = state.requiredBoards
            if (required != null && hasLegalMoveWithin(state, required)) {
                val (rb, rd) = required
                val spansRequired =
                    if (rb == rd) {
                        move.a.board == rb && move.b.board == rb
                    } else {
                        (move.a.board == rb && move.b.board == rd) ||
                            (move.a.board == rd && move.b.board == rb)
                    }
                if (!spansRequired) {
                    return MoveResult.Illegal(IllegalReason.WRONG_SENT_BOARDS)
                }
            }
        }
        if ((
                state.variant == Variant.QUANTUM_TIC_TAC_TOE_SQUARED ||
                    state.variant == Variant.ULTIMATE_QUANTUM_TIC_TAC_TOE
            ) &&
            !move.isIntraBoard
        ) {
            // Comparing boards directly (two int equalities per check) instead
            // of `setOf(...) == setOf(...)` so this stays allocation-free --
            // `Rules.apply` runs in MCTS rollouts at thousands of calls per
            // second.
            val mb1 = move.a.board
            val mb2 = move.b.board
            val duplicate =
                state.entanglements.any { e ->
                    if (e.isIntraBoard) {
                        false
                    } else {
                        val eb1 = e.a.square.board
                        val eb2 = e.b.square.board
                        (eb1 == mb1 && eb2 == mb2) || (eb1 == mb2 && eb2 == mb1)
                    }
                }
            if (duplicate) {
                return MoveResult.Illegal(IllegalReason.DUPLICATE_INTER_BOARD_ENTANGLEMENT)
            }
        }
        // No two entanglements may share the same exact pair of squares.
        // The inter-board check above catches inter-board same-pair cases
        // first (more specific message); this catches the intra-board
        // case. "Intra-board entanglements are unrestricted" lifts the
        // inter-board pair limit, not the duplicate-edge prohibition.
        val ma = move.a
        val mb = move.b
        val duplicatePair =
            state.entanglements.any { e ->
                val ea = e.a.square
                val eb = e.b.square
                (ea == ma && eb == mb) || (ea == mb && eb == ma)
            }
        if (duplicatePair) {
            return MoveResult.Illegal(IllegalReason.DUPLICATE_PAIR)
        }

        val markA = QuantumMark(move.player, move.number, move.a)
        val markB = QuantumMark(move.player, move.number, move.b)
        val newEdge = Entanglement(markA, markB)

        val loop = detectLoop(state.entanglements, newEdge)
        val nextEntanglements = state.entanglements + newEdge
        val nextQuantum = state.quantum + markA + markB

        val nextRequiredBoards = nextRequiredBoardsAfter(state, move)
        return if (loop == null) {
            MoveResult.Legal(
                state.copy(
                    quantum = nextQuantum,
                    entanglements = nextEntanglements,
                    nextMoveNumber = state.nextMoveNumber + 1,
                    lastMove = move,
                    requiredBoards = nextRequiredBoards,
                ),
            )
        } else {
            val choices = computeCollapseChoices(nextEntanglements, loop)
            val pending = PendingCollapse(chooser = move.player.opponent, choices = choices)
            val pendingState =
                state.copy(
                    quantum = nextQuantum,
                    entanglements = nextEntanglements,
                    lastMove = move,
                    pendingCollapse = pending,
                    requiredBoards = nextRequiredBoards,
                )
            MoveResult.TriggersCollapse(pendingState, choices)
        }
    }

    /**
     * The sending-rule constraint imposed on the next placement by the
     * just-applied `move`. Null for the non-sending variants and the
     * first move of a sending game. Returned as the raw position pair
     * even if the constraint will turn out to be unsatisfiable on the
     * resulting state -- `Rules.apply` and `Rules.legalMoves` defer to
     * `hasLegalMoveWithin` at use time to grant free play.
     */
    private fun nextRequiredBoardsAfter(
        state: GameState,
        move: Move,
    ): Pair<Int, Int>? =
        if (state.variant == Variant.ULTIMATE_QUANTUM_TIC_TAC_TOE) {
            Pair(move.a.position, move.b.position)
        } else {
            null
        }

    /**
     * Whether `state` admits at least one legal placement whose two
     * endpoints span the mini-boards named by `required`. Used both
     * by `Rules.apply` (to decide whether to enforce the sending
     * constraint or let the move through as free play) and by
     * `Rules.legalMoves` (to filter / fall back).
     *
     * Intentionally does not delegate to `Rules.apply` -- it inlines
     * the post-`SQUARE_IS_CLASSICAL` legality checks (duplicate-pair,
     * inter-board entanglement budget) so the satisfiability test
     * doesn't recurse through the sending check it's trying to
     * decide.
     */
    internal fun hasLegalMoveWithin(
        state: GameState,
        required: Pair<Int, Int>,
    ): Boolean {
        val (b, d) = required
        val openInB = (1..9).map { Square(b, it) }.filter { it !in state.classical }
        if (b == d) {
            if (openInB.size < 2) return false
            for (i in openInB.indices) {
                for (j in i + 1 until openInB.size) {
                    val sqA = openInB[i]
                    val sqB = openInB[j]
                    val duplicatePair =
                        state.entanglements.any { e ->
                            val ea = e.a.square
                            val eb = e.b.square
                            (ea == sqA && eb == sqB) || (ea == sqB && eb == sqA)
                        }
                    if (!duplicatePair) return true
                }
            }
            return false
        }
        val interBoardSpent =
            state.entanglements.any { e ->
                if (e.isIntraBoard) {
                    false
                } else {
                    val eb1 = e.a.square.board
                    val eb2 = e.b.square.board
                    (eb1 == b && eb2 == d) || (eb1 == d && eb2 == b)
                }
            }
        if (interBoardSpent) return false
        val openInD = (1..9).map { Square(d, it) }.filter { it !in state.classical }
        return openInB.isNotEmpty() && openInD.isNotEmpty()
    }

    /**
     * Resolve a pending collapse by selecting one of the offered choices.
     * The returned GameState clears `pendingCollapse`, promotes the
     * chosen assignments to classical marks, removes the resolved
     * quantum marks and their entanglements, and updates `wonBoards`.
     * The move counter advances by one (the triggering move only counts
     * once collapse is resolved).
     */
    fun resolve(
        state: GameState,
        choice: CollapseChoice,
    ): GameState {
        val pending =
            checkNotNull(state.pendingCollapse) {
                "resolve() called without a pending collapse"
            }
        // Look up the canonical choice by id and use its assignments,
        // not the caller-supplied object's. Validating the id alone
        // would let a caller pass a `CollapseChoice` with a real id
        // but arbitrary assignments and have those assignments applied
        // verbatim; the engine is the authority on what each
        // resolution does, so the caller's role is only to *name*
        // which one to apply.
        val canonical =
            pending.choices.firstOrNull { it.id == choice.id }
                ?: error("choice id ${choice.id} is not one of the offered resolutions")

        val resolvedMoveNumbers = canonical.assignments.keys.map { it.moveNumber }.toSet()
        val addedClassical =
            canonical.assignments.entries
                .associate { (mark, square) -> square to mark.player }
        val newClassical = state.classical + addedClassical

        val remainingQuantum = state.quantum.filterNot { it.moveNumber in resolvedMoveNumbers }
        val remainingEntanglements =
            state.entanglements.filterNot {
                it.moveNumber in resolvedMoveNumbers
            }

        val touchedBoards = addedClassical.keys.map { it.board }.toSet()
        val newWonBoards = recomputeWonBoards(state.wonBoards, newClassical, touchedBoards)

        return state.copy(
            classical = newClassical,
            quantum = remainingQuantum,
            entanglements = remainingEntanglements,
            wonBoards = newWonBoards,
            nextMoveNumber = state.nextMoveNumber + 1,
            pendingCollapse = null,
        )
    }

    /**
     * Enumerate all legal moves for the current player as a lazy sequence.
     * Intended for the AI; pruning happens upstream. Iteration is in
     * canonical (a < b lexicographic on (board, position)) order so two
     * runs over the same state yield the same sequence.
     */
    fun legalMoves(state: GameState): Sequence<Move> =
        sequence {
            if (state.pendingCollapse != null) return@sequence
            if (state.winners.isNotEmpty()) return@sequence

            val squares: List<Square> =
                when (state.variant) {
                    Variant.QUANTUM_TIC_TAC_TOE -> (1..9).map { Square(1, it) }
                    Variant.QUANTUM_TIC_TAC_TOE_SQUARED,
                    Variant.ULTIMATE_QUANTUM_TIC_TAC_TOE,
                    ->
                        (1..9).flatMap { b -> (1..9).map { p -> Square(b, p) } }
                    Variant.ULTIMATE_TIC_TAC_TOE ->
                        error("classical variant reached quantum Rules.legalMoves")
                }
            val open = squares.filterNot { it in state.classical }
            val occupiedInterBoardPairs: Set<Set<Int>> =
                state.entanglements
                    .filterNot { it.isIntraBoard }
                    .map { it.boards }
                    .toSet()
            val existingSquarePairs: Set<Set<Square>> =
                state.entanglements
                    .map { setOf(it.a.square, it.b.square) }
                    .toSet()
            // Sending-rule pre-filter: when the constraint is satisfiable,
            // only emit pairs that span the required mini-boards. When
            // it isn't, emit everything (free play).
            val sendingFilter: ((Square, Square) -> Boolean)? =
                if (state.variant == Variant.ULTIMATE_QUANTUM_TIC_TAC_TOE) {
                    val required = state.requiredBoards
                    if (required != null && hasLegalMoveWithin(state, required)) {
                        val (rb, rd) = required
                        { sqA, sqB ->
                            if (rb == rd) {
                                sqA.board == rb && sqB.board == rb
                            } else {
                                (sqA.board == rb && sqB.board == rd) ||
                                    (sqA.board == rd && sqB.board == rb)
                            }
                        }
                    } else {
                        null
                    }
                } else {
                    null
                }

            for (i in open.indices) {
                for (j in i + 1 until open.size) {
                    val a = open[i]
                    val b = open[j]
                    if (sendingFilter != null && !sendingFilter(a, b)) continue
                    if ((
                            state.variant == Variant.QUANTUM_TIC_TAC_TOE_SQUARED ||
                                state.variant == Variant.ULTIMATE_QUANTUM_TIC_TAC_TOE
                        ) &&
                        a.board != b.board &&
                        setOf(a.board, b.board) in occupiedInterBoardPairs
                    ) {
                        continue
                    }
                    if (setOf(a, b) in existingSquarePairs) continue
                    yield(Move(state.nextMoveNumber, a, b))
                }
            }
        }
}

// ---------------------------------------------------------------------------
// Internal helpers.
//
// The pre-move quantum graph is always a forest: every move that would
// have closed an earlier cycle triggered a collapse and removed it. So
// at most one cycle exists at any time -- the one just created by
// `newEdge` -- and the forest invariant rules out a tree edge with both
// endpoints inside the loop or with two paths reaching the same outside
// node. The cascade therefore visits each outside square exactly once
// and assigns each non-loop edge at most one classical destination.
// ---------------------------------------------------------------------------

/** A closed walk through the quantum graph; nodes[i] is joined to nodes[(i+1) % n] by edges[i]. */
internal data class Loop(val nodes: List<Square>, val edges: List<Entanglement>) {
    init {
        require(nodes.size == edges.size) {
            "a loop has the same number of nodes and edges"
        }
        require(nodes.size >= 3) { "a loop spans at least three distinct squares" }
    }
}

/**
 * BFS the existing quantum graph from one endpoint of `newEdge` to the
 * other. If they are already connected, the discovered path plus
 * `newEdge` closes a loop; otherwise the new edge merely joins two trees.
 */
internal fun detectLoop(
    edges: List<Entanglement>,
    newEdge: Entanglement,
): Loop? {
    val start = newEdge.a.square
    val end = newEdge.b.square

    val adj = mutableMapOf<Square, MutableList<Pair<Square, Entanglement>>>()
    for (e in edges) {
        adj.getOrPut(e.a.square) { mutableListOf() }.add(e.b.square to e)
        adj.getOrPut(e.b.square) { mutableListOf() }.add(e.a.square to e)
    }

    val parent = mutableMapOf<Square, Pair<Square, Entanglement>>()
    val visited = mutableSetOf(start)
    val queue = ArrayDeque<Square>().also { it.add(start) }
    var reached = false
    bfs@ while (queue.isNotEmpty()) {
        val cur = queue.removeFirst()
        val neighbors = adj[cur] ?: continue
        for ((nb, edge) in neighbors) {
            if (nb in visited) continue
            visited.add(nb)
            parent[nb] = cur to edge
            if (nb == end) {
                reached = true
                break@bfs
            }
            queue.add(nb)
        }
    }
    if (!reached) return null

    val pathNodes = mutableListOf<Square>()
    val pathEdges = mutableListOf<Entanglement>()
    var cur = end
    while (cur != start) {
        pathNodes.add(cur)
        val (prev, edge) = parent.getValue(cur)
        pathEdges.add(edge)
        cur = prev
    }
    pathNodes.add(start)
    pathNodes.reverse()
    pathEdges.reverse()
    // pathNodes = [start, ..., end]; pathEdges[i] joins pathNodes[i] to pathNodes[i+1].
    // newEdge closes the cycle from end back to start.
    return Loop(nodes = pathNodes, edges = pathEdges + newEdge)
}

/**
 * Generate the two possible collapse resolutions for the given loop.
 * Resolution A rotates one way around the loop (edge i delivers its
 * classical mark to nodes[i]); resolution B rotates the other way (edge
 * i delivers its mark to nodes[(i+1) % n]). Each resolution then
 * cascades outward through non-loop edges: whenever an edge has one
 * endpoint already classical, the entanglement's other quantum mark is
 * forced to become classical at the opposite square.
 */
internal fun computeCollapseChoices(
    edges: List<Entanglement>,
    loop: Loop,
): List<CollapseChoice> {
    val n = loop.nodes.size
    val choiceA = mutableMapOf<QuantumMark, Square>()
    val choiceB = mutableMapOf<QuantumMark, Square>()

    for (i in 0 until n) {
        val first = loop.nodes[i]
        val second = loop.nodes[(i + 1) % n]
        val edge = loop.edges[i]
        val markAtFirst = if (edge.a.square == first) edge.a else edge.b
        val markAtSecond = if (edge.a.square == second) edge.a else edge.b
        choiceA[markAtFirst] = first
        choiceB[markAtSecond] = second
    }

    val loopEdges = loop.edges.toSet()
    val nonLoopEdges = edges.filterNot { it in loopEdges }
    val loopNodes = loop.nodes.toSet()

    cascade(choiceA, loopNodes, nonLoopEdges)
    cascade(choiceB, loopNodes, nonLoopEdges)

    return listOf(
        CollapseChoice(id = 0, assignments = choiceA.toMap()),
        CollapseChoice(id = 1, assignments = choiceB.toMap()),
    )
}

/**
 * Cascade classical assignments outward from the loop nodes through the
 * non-loop edges. Mutates `assignment` in place. The forest invariant
 * guarantees each outside square is reached by exactly one path, so the
 * order of visits does not affect the result.
 */
private fun cascade(
    assignment: MutableMap<QuantumMark, Square>,
    loopNodes: Set<Square>,
    nonLoopEdges: List<Entanglement>,
) {
    val adj = mutableMapOf<Square, MutableList<Pair<Square, Entanglement>>>()
    for (e in nonLoopEdges) {
        adj.getOrPut(e.a.square) { mutableListOf() }.add(e.b.square to e)
        adj.getOrPut(e.b.square) { mutableListOf() }.add(e.a.square to e)
    }

    val filled = loopNodes.toMutableSet()
    val processedEdges = mutableSetOf<Entanglement>()
    val queue = ArrayDeque<Square>().also { it.addAll(loopNodes) }

    while (queue.isNotEmpty()) {
        val sq = queue.removeFirst()
        val neighbors = adj[sq] ?: continue
        for ((other, edge) in neighbors) {
            if (!processedEdges.add(edge)) continue
            // The mark in `sq` is destroyed; the mark in `other` becomes classical.
            val markAtOther = if (edge.a.square == other) edge.a else edge.b
            assignment[markAtOther] = other
            if (filled.add(other)) queue.add(other)
        }
    }
}

/** Indices of the eight winning lines on a 3x3 board. */
internal val BOARD_LINES: List<List<Int>> =
    listOf(
        listOf(1, 2, 3),
        listOf(4, 5, 6),
        listOf(7, 8, 9),
        listOf(1, 4, 7),
        listOf(2, 5, 8),
        listOf(3, 6, 9),
        listOf(1, 5, 9),
        listOf(3, 5, 7),
    )

/**
 * Recompute the winners of any mini-board touched by the just-applied
 * classical marks. Boards not in `touchedBoards` keep their previous
 * winner set (classical marks never disappear, so a previously won board
 * stays won).
 */
internal fun recomputeWonBoards(
    previous: Map<Int, Set<Player>>,
    classical: Map<Square, Player>,
    touchedBoards: Set<Int>,
): Map<Int, Set<Player>> {
    val updated = previous.toMutableMap()
    for (board in touchedBoards) {
        updated[board] = winnersOfMiniBoard(board, classical)
    }
    return updated
}

private fun winnersOfMiniBoard(
    board: Int,
    classical: Map<Square, Player>,
): Set<Player> {
    val winners = mutableSetOf<Player>()
    for (line in BOARD_LINES) {
        val first = classical[Square(board, line[0])] ?: continue
        if (classical[Square(board, line[1])] == first &&
            classical[Square(board, line[2])] == first
        ) {
            winners.add(first)
        }
    }
    return winners
}

/**
 * The set of meta-game winners. In Quantum Tic-Tac-Toe this is just
 * the winners of mini-board 1. In every other variant a player wins
 * the meta-game when they hold three mini-boards in a row, where a
 * shared mini-board counts as held by both players.
 */
internal fun computeWinners(
    variant: Variant,
    wonBoards: Map<Int, Set<Player>>,
): Set<Player> =
    when (variant) {
        Variant.QUANTUM_TIC_TAC_TOE -> wonBoards[1] ?: emptySet()
        Variant.QUANTUM_TIC_TAC_TOE_SQUARED,
        Variant.ULTIMATE_QUANTUM_TIC_TAC_TOE,
        Variant.ULTIMATE_TIC_TAC_TOE,
        -> {
            val winners = mutableSetOf<Player>()
            for (player in Player.entries) {
                for (line in BOARD_LINES) {
                    if (line.all { player in (wonBoards[it] ?: emptySet()) }) {
                        winners.add(player)
                        break
                    }
                }
            }
            winners
        }
    }
