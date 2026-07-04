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

// TikZ export of a board position. Produces a self-contained
// `\begin{tikzpicture}...\end{tikzpicture}` fragment (with two
// `\definecolor` lines above it for the fixed X / O palette) that a
// user pastes into a LaTeX document loading `tikz` and `xcolor`. Pure
// commonMain code with no Compose dependency; test coverage is via
// golden-file comparison.
//
// Coordinate system: origin at the bottom-left corner of the board,
// x increasing rightward, y increasing upward -- LaTeX's default.
// One unit == one square. In Quantum the board is 3 units on a
// side; in Squared / Ultimate (both quantum and classical) it is 9
// units on a side (three 3-square mini-boards per row). Reading
// order (board 1 = top-left, position 1 = top-left of that
// mini-board) is mapped to (col, row) where the top-most row's
// pixel-y is the largest.
package net.yonge_mallo.uqttt.tikz

import net.yonge_mallo.uqttt.engine.ClassicalGameState
import net.yonge_mallo.uqttt.engine.Entanglement
import net.yonge_mallo.uqttt.engine.GameState
import net.yonge_mallo.uqttt.engine.Player
import net.yonge_mallo.uqttt.engine.QuantumMark
import net.yonge_mallo.uqttt.engine.Square
import net.yonge_mallo.uqttt.engine.Variant
import kotlin.math.cos
import kotlin.math.sin

// --- Colours mirror `ui.PlayerColors` (Material red 700 / green 800).
private const val COLOR_X_R = 211
private const val COLOR_X_G = 47
private const val COLOR_X_B = 47
private const val COLOR_O_R = 46
private const val COLOR_O_G = 125
private const val COLOR_O_B = 50

// Amber-ish highlight for the last-played square, mirroring the
// on-screen `lastMoveColor`.
private const val COLOR_LAST_R = 255
private const val COLOR_LAST_G = 179
private const val COLOR_LAST_B = 0

private const val QUANTUM_ORBIT_RADIUS = 0.22
private const val TWO_PI = 2.0 * kotlin.math.PI

// Mark sizes are proportions of one square (which is one TikZ unit).
// They match the on-screen values in `BoardCanvas` so the exported
// picture reads as the same board at the same scale, not a symbolic
// rewrite. Stroke widths are pt values that look right against
// the picture's default `scale=0.6`; TikZ line widths are absolute,
// not scale-relative, so they stay in pt rather than units.
private const val CLASSICAL_HALF_SIZE = 0.28
private const val CLASSICAL_O_RADIUS = 0.30
private const val QUANTUM_HALF_SIZE = 0.13
private const val QUANTUM_O_RADIUS = 0.14

// The move-number label sits tucked against the mark, extending
// down and right from an anchor placed just left of the mark's
// centre at centre height (with `anchor=north west`). The `_FRAC`
// values are multiples of `QUANTUM_HALF_SIZE` so the label follows
// if the mark size is ever retuned; a negative `X_FRAC` moves the
// anchor left of centre, and `Y_FRAC = 0` keeps the anchor at the
// mark's vertical centre.
private const val QUANTUM_LABEL_X_FRAC = -0.4
private const val QUANTUM_LABEL_Y_FRAC = 0.0

// Stroke widths. Fixed pt values chosen to look right at
// `scale=0.6`; if the user rescales the picture on their side the
// mark strokes stay the same weight, which reads as "printed
// diagram" behaviour rather than the widths silently zooming with
// the geometry.
private const val GRID_INNER_STROKE_PT = "0.5pt"
private const val GRID_OUTER_STROKE_PT = "1.5pt"
private const val CLASSICAL_MARK_STROKE_PT = "3pt"
private const val QUANTUM_MARK_STROKE_PT = "1.5pt"

/**
 * Emit a TikZ fragment for a quantum game state. Draws the mini-
 * or meta-grid, classical marks, quantum marks (with move-number
 * subscripts), entanglement arcs, won-board rings, the last-move
 * highlight, and the sending-rule mini-board outline (for the
 * quantum Ultimate variant).
 */
fun exportTikz(state: GameState): String {
    val out = TikzWriter()
    out.preamble("Quantum ${state.variant.displayLabel()} board")
    out.beginPicture()
    val geometry = QuantumGeometry(state.variant)
    drawGrid(out, geometry.boardCount)
    // Sending-rule highlight before other overlays so marks stay on top.
    state.requiredBoards?.let { drawSentBoards(out, geometry, listOf(it.first, it.second).distinct()) }
    // Won-board rings.
    for ((board, winners) in state.wonBoards) {
        winners.forEachIndexed { index, player ->
            drawWonBoardRing(out, geometry, board, player, index)
        }
    }
    // Entanglement arcs. Positioned mark-to-mark using the same
    // orbit offsets the on-screen canvas uses so overlapping marks
    // don't render on top of each other.
    val markPositions = quantumMarkPositions(state.quantum, geometry)
    for (edge in state.entanglements) drawEntanglement(out, markPositions, edge)
    // Last-move dashed rectangles around each endpoint.
    state.lastMove?.let { move ->
        for (sq in setOf(move.a, move.b)) drawLastMoveHighlight(out, geometry, sq)
    }
    // Marks last so they sit on top of the overlays above.
    for ((square, player) in state.classical) drawClassicalMark(out, geometry.squareCenter(square), player)
    for (mark in state.quantum) drawQuantumMark(out, markPositions.getValue(mark), mark)
    out.endPicture()
    return out.toString()
}

/**
 * Emit a TikZ fragment for a classical Ultimate state. Draws the
 * meta-grid, classical marks, won-board rings, the last-move
 * highlight, and the sending-rule mini-board outline. No
 * entanglements or quantum-mark subscripts because the classical
 * engine has neither.
 */
fun exportTikz(state: ClassicalGameState): String {
    val out = TikzWriter()
    out.preamble("Classical Ultimate Tic-Tac-Toe board")
    out.beginPicture()
    val geometry = QuantumGeometry(state.variant)
    drawGrid(out, geometry.boardCount)
    state.requiredBoard?.let { drawSentBoards(out, geometry, listOf(it)) }
    for ((board, winners) in state.wonBoards) {
        winners.forEachIndexed { index, player ->
            drawWonBoardRing(out, geometry, board, player, index)
        }
    }
    state.lastMove?.let { drawLastMoveHighlight(out, geometry, it.square) }
    for ((square, player) in state.classical) drawClassicalMark(out, geometry.squareCenter(square), player)
    out.endPicture()
    return out.toString()
}

// ---------------------------------------------------------------------------
// Geometry.
// ---------------------------------------------------------------------------

/**
 * Maps `(board, position)` pairs to TikZ coordinates (`x`, `y`) with
 * `y` growing upward. A shared metric for both engines because the
 * only difference is the classical variant reusing the quantum
 * Squared / Ultimate layout.
 */
private data class QuantumGeometry(val variant: Variant) {
    val boardCount: Int = if (variant == Variant.QUANTUM_TIC_TAC_TOE) 1 else 9
    val boardsPerSide: Int = if (variant == Variant.QUANTUM_TIC_TAC_TOE) 1 else 3
    val squareSize: Double = 1.0
    val sideLength: Double = boardsPerSide * 3.0 * squareSize

    /**
     * The centre point of a given square, in TikZ coordinates.
     * Reading order: board 1 is top-left, position 1 is top-left of
     * that mini-board, positions and boards each grow to the right
     * then down. Convert to a y-up coordinate system by subtracting
     * from the total side length.
     */
    fun squareCenter(square: Square): Point {
        val (boardCol, boardRow) =
            if (boardCount == 1) 0 to 0 else (square.board - 1) % 3 to (square.board - 1) / 3
        val posCol = (square.position - 1) % 3
        val posRow = (square.position - 1) / 3
        val xCells = boardCol * 3 + posCol + 0.5
        val yCells = (boardsPerSide * 3) - (boardRow * 3 + posRow) - 0.5
        return Point(xCells * squareSize, yCells * squareSize)
    }

    /**
     * The bottom-left corner of a mini-board. Used to place rings
     * around won boards and the sent-board outline.
     */
    fun miniBoardOrigin(board: Int): Point {
        val (col, row) =
            if (boardCount == 1) 0 to 0 else (board - 1) % 3 to (board - 1) / 3
        val x = col * 3.0 * squareSize
        val y = (boardsPerSide - 1 - row) * 3.0 * squareSize
        return Point(x, y)
    }

    val miniBoardSize: Double get() = 3.0 * squareSize
}

private data class Point(val x: Double, val y: Double)

// ---------------------------------------------------------------------------
// Drawing.
// ---------------------------------------------------------------------------

/**
 * The grid: inner square-separator lines plus the mini-board outer
 * outlines. Skipped for Quantum's single-board layout except for the
 * inner cells; Quantum has no meta-board to outline.
 */
private fun drawGrid(
    out: TikzWriter,
    boardCount: Int,
) {
    val boards = if (boardCount == 1) 1..1 else 1..9
    val boardsPerSide = if (boardCount == 1) 1 else 3
    val side = boardsPerSide * 3.0
    // Inner cell lines, one full grid across the whole board. The
    // mini-board outer outline (thicker) is drawn on top per board.
    out.line("% Inner cell lines")
    for (i in 1 until (boardsPerSide * 3)) {
        out.raw("\\draw[line width=$GRID_INNER_STROKE_PT] (${d(i)}, 0) -- (${d(i)}, ${d(side)});")
        out.raw("\\draw[line width=$GRID_INNER_STROKE_PT] (0, ${d(i)}) -- (${d(side)}, ${d(i)});")
    }
    if (boardCount > 1) {
        out.line("% Mini-board outer outlines")
        for (board in boards) {
            val origin = QuantumGeometry(Variant.QUANTUM_TIC_TAC_TOE_SQUARED).miniBoardOrigin(board)
            out.raw(
                "\\draw[line width=$GRID_OUTER_STROKE_PT] (${d(
                    origin.x,
                )}, ${d(origin.y)}) rectangle (${d(origin.x + 3)}, ${d(origin.y + 3)});",
            )
        }
    } else {
        out.line("% Single-board outer outline")
        out.raw("\\draw[line width=$GRID_OUTER_STROKE_PT] (0, 0) rectangle (${d(side)}, ${d(side)});")
    }
}

/**
 * Draw a classical (permanent) mark. X is two crossed round-capped
 * strokes; O is a stroked circle. Sized as a proportion of one
 * square so the mark visually occupies the same fraction of its
 * cell as the on-screen `BoardCanvas.drawClassicalMarks` (`halfSize
 * = 0.28 * squareSize`, `radius = 0.30 * squareSize`).
 */
private fun drawClassicalMark(
    out: TikzWriter,
    at: Point,
    player: Player,
) {
    val color = if (player == Player.X) "uqtttMarkX" else "uqtttMarkO"
    if (player == Player.X) {
        drawCrossStrokes(out, color, CLASSICAL_MARK_STROKE_PT, at, CLASSICAL_HALF_SIZE)
    } else {
        out.raw(
            "\\draw[$color, line width=$CLASSICAL_MARK_STROKE_PT] " +
                "(${d(at.x)}, ${d(at.y)}) circle (${d(CLASSICAL_O_RADIUS)});",
        )
    }
}

/**
 * Draw a quantum (superposed) mark and its move-number label. The
 * mark shape mirrors the classical version at a smaller size
 * (`halfSize = 0.13`, `radius = 0.14` -- the same fractions
 * `BoardCanvas.drawQuantumMarks` uses); the move number sits
 * outside the mark's bottom-right corner in a subscript position,
 * matching the on-screen convention of a small digit tucked
 * against each superposed mark. Rendered in `\tiny` so the label
 * is visibly smaller than the mark itself.
 */
private fun drawQuantumMark(
    out: TikzWriter,
    at: Point,
    mark: QuantumMark,
) {
    val color = if (mark.player == Player.X) "uqtttMarkX" else "uqtttMarkO"
    if (mark.player == Player.X) {
        drawCrossStrokes(out, color, QUANTUM_MARK_STROKE_PT, at, QUANTUM_HALF_SIZE)
    } else {
        out.raw(
            "\\draw[$color, line width=$QUANTUM_MARK_STROKE_PT] " +
                "(${d(at.x)}, ${d(at.y)}) circle (${d(QUANTUM_O_RADIUS)});",
        )
    }
    val labelX = at.x + QUANTUM_HALF_SIZE * QUANTUM_LABEL_X_FRAC
    val labelY = at.y - QUANTUM_HALF_SIZE * QUANTUM_LABEL_Y_FRAC
    out.raw(
        "\\node[$color, font=\\tiny, anchor=north west] " +
            "at (${d(labelX)}, ${d(labelY)}) {${mark.moveNumber}};",
    )
}

/**
 * The two diagonal strokes that make up an X glyph. Emitted as a
 * single `\draw` command with two segments so the two lines share
 * the colour and stroke settings. Round line caps match the
 * on-screen `StrokeCap.Round` on the Compose canvas.
 */
private fun drawCrossStrokes(
    out: TikzWriter,
    color: String,
    strokeWidth: String,
    center: Point,
    halfSize: Double,
) {
    val x0 = d(center.x - halfSize)
    val y0 = d(center.y - halfSize)
    val x1 = d(center.x + halfSize)
    val y1 = d(center.y + halfSize)
    out.raw(
        "\\draw[$color, line width=$strokeWidth, line cap=round] " +
            "($x0, $y0) -- ($x1, $y1) ($x1, $y0) -- ($x0, $y1);",
    )
}

/**
 * Position every quantum mark's centre. Marks sharing a square are
 * spread around the square's centre on a small circle, matching
 * `BoardCanvas.computeMarkPositions` so a TikZ export is visually
 * consistent with what's on-screen.
 */
private fun quantumMarkPositions(
    marks: List<QuantumMark>,
    geometry: QuantumGeometry,
): Map<QuantumMark, Point> {
    val result = mutableMapOf<QuantumMark, Point>()
    for ((square, group) in marks.groupBy { it.square }) {
        val center = geometry.squareCenter(square)
        if (group.size == 1) {
            result[group.single()] = center
        } else {
            group.forEachIndexed { index, mark ->
                val angle = index * TWO_PI / group.size
                val x = center.x + QUANTUM_ORBIT_RADIUS * cos(angle)
                val y = center.y + QUANTUM_ORBIT_RADIUS * sin(angle)
                result[mark] = Point(x, y)
            }
        }
    }
    return result
}

/**
 * A quadratic Bézier arc joining the two endpoints of an
 * entanglement. The control point sits perpendicular to the midpoint
 * so the arc bends visibly rather than lying flat over the straight
 * line between the marks.
 */
private fun drawEntanglement(
    out: TikzWriter,
    markPositions: Map<QuantumMark, Point>,
    edge: Entanglement,
) {
    val a = markPositions.getValue(edge.a)
    val b = markPositions.getValue(edge.b)
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len = kotlin.math.sqrt(dx * dx + dy * dy)
    val (px, py) = if (len > 0.0) -dy / len to dx / len else 0.0 to 0.0
    val bend = len * 0.18
    val midX = (a.x + b.x) / 2.0
    val midY = (a.y + b.y) / 2.0
    val cx = midX + px * bend
    val cy = midY + py * bend
    val color = if (edge.player == Player.X) "uqtttMarkX" else "uqtttMarkO"
    // `.. controls (c) ..` is TikZ's quadratic Bézier syntax; the
    // opacity mirrors the on-screen `alpha = 0.65` on entanglement
    // curves so they read as a joining thread, not a bold line.
    out.raw(
        "\\draw[$color, opacity=0.65, thick] (${d(
            a.x,
        )}, ${d(a.y)}) .. controls (${d(cx)}, ${d(cy)}) .. (${d(b.x)}, ${d(b.y)});",
    )
}

/**
 * Coloured outline around a won mini-board. Shared boards draw two
 * concentric outlines (one inset by a small amount) so both winners'
 * colours are visible, matching the on-screen ring drawing.
 */
private fun drawWonBoardRing(
    out: TikzWriter,
    geometry: QuantumGeometry,
    board: Int,
    player: Player,
    ringIndex: Int,
) {
    val origin = geometry.miniBoardOrigin(board)
    val inset = 0.12 * ringIndex
    val color = if (player == Player.X) "uqtttMarkX" else "uqtttMarkO"
    val x0 = origin.x + inset
    val y0 = origin.y + inset
    val x1 = origin.x + geometry.miniBoardSize - inset
    val y1 = origin.y + geometry.miniBoardSize - inset
    out.raw(
        "\\draw[$color, opacity=0.55, line width=3pt] (${d(x0)}, ${d(y0)}) rectangle (${d(x1)}, ${d(y1)});",
    )
}

/**
 * Coloured outline around the mini-board(s) the sending rule points
 * at (a single board in classical Ultimate, one or two in quantum
 * Ultimate). Rendered before marks so the marks stay legible on
 * top.
 */
private fun drawSentBoards(
    out: TikzWriter,
    geometry: QuantumGeometry,
    boards: List<Int>,
) {
    for (board in boards) {
        val origin = geometry.miniBoardOrigin(board)
        // Secondary theme colour on screen is not fixed; TikZ output
        // uses a mid-blue that reads distinctly against the mark
        // palette in both light and dark PDF viewers.
        out.raw(
            "\\draw[color={rgb,255:red,25;green,118;blue,210}, line width=2.5pt] (${d(
                origin.x,
            )}, ${d(
                origin.y,
            )}) rectangle (${d(origin.x + geometry.miniBoardSize)}, ${d(origin.y + geometry.miniBoardSize)});",
        )
    }
}

private fun drawLastMoveHighlight(
    out: TikzWriter,
    geometry: QuantumGeometry,
    square: Square,
) {
    val center = geometry.squareCenter(square)
    val half = geometry.squareSize / 2.0
    val inset = 0.06
    val x0 = center.x - half + inset
    val y0 = center.y - half + inset
    val x1 = center.x + half - inset
    val y1 = center.y + half - inset
    out.raw(
        "\\draw[uqtttMarkLast, line width=1.5pt, dashed] (${d(x0)}, ${d(y0)}) rectangle (${d(x1)}, ${d(y1)});",
    )
}

// ---------------------------------------------------------------------------
// Formatting.
// ---------------------------------------------------------------------------

/**
 * `Double` -> string with a fixed 3 decimal places, trailing zeroes
 * trimmed and `-0` normalised to `0`. Uses a hand-written formatter
 * so the output is byte-identical across JVM / Wasm / Native locales
 * (some `Double.toString` implementations emit locale-specific
 * decimal separators, which would break golden tests).
 */
private fun d(value: Double): String {
    val rounded = kotlin.math.round(value * 1000.0).toLong()
    if (rounded == 0L) return "0"
    val negative = rounded < 0
    val magnitude = if (negative) -rounded else rounded
    val whole = magnitude / 1000
    val fraction = magnitude % 1000
    val sign = if (negative) "-" else ""
    if (fraction == 0L) return "$sign$whole"
    val padded = fraction.toString().padStart(3, '0').trimEnd('0')
    return "$sign$whole.$padded"
}

private fun d(value: Int): String = value.toString()

/**
 * Small text-mode label for a variant, used in a comment at the top
 * of the fragment so users can see what board they're pasting.
 */
private fun Variant.displayLabel(): String =
    when (this) {
        Variant.QUANTUM_TIC_TAC_TOE -> "Tic-Tac-Toe"
        Variant.QUANTUM_TIC_TAC_TOE_SQUARED -> "Tic-Tac-Toe Squared"
        Variant.ULTIMATE_QUANTUM_TIC_TAC_TOE -> "Ultimate Tic-Tac-Toe"
        Variant.ULTIMATE_TIC_TAC_TOE -> "Ultimate Tic-Tac-Toe"
    }

/**
 * Line-based builder for the LaTeX output. Guarantees stable line
 * ordering, uniform indentation inside `tikzpicture`, and a trailing
 * newline on the whole fragment -- all necessary for golden-file
 * comparisons.
 */
private class TikzWriter {
    private val lines = mutableListOf<String>()

    fun preamble(title: String) {
        lines.add("% $title.")
        lines.add("% Generated by UQTTT. Paste into a document loading \\usepackage{tikz} and \\usepackage{xcolor}.")
        lines.add("\\definecolor{uqtttMarkX}{RGB}{$COLOR_X_R,$COLOR_X_G,$COLOR_X_B}")
        lines.add("\\definecolor{uqtttMarkO}{RGB}{$COLOR_O_R,$COLOR_O_G,$COLOR_O_B}")
        lines.add("\\definecolor{uqtttMarkLast}{RGB}{$COLOR_LAST_R,$COLOR_LAST_G,$COLOR_LAST_B}")
    }

    fun beginPicture() {
        lines.add("\\begin{tikzpicture}[scale=0.6, line join=round]")
    }

    fun endPicture() {
        lines.add("\\end{tikzpicture}")
    }

    fun line(text: String) {
        lines.add("  $text")
    }

    fun raw(text: String) {
        lines.add("  $text")
    }

    override fun toString(): String = lines.joinToString(separator = "\n", postfix = "\n")
}
