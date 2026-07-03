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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import net.yonge_mallo.uqttt.engine.GameState
import net.yonge_mallo.uqttt.engine.Move
import net.yonge_mallo.uqttt.engine.Player
import net.yonge_mallo.uqttt.engine.QuantumMark
import net.yonge_mallo.uqttt.engine.Square
import net.yonge_mallo.uqttt.engine.Variant
import net.yonge_mallo.uqttt.ui.colorOf
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The whole board, drawn in a single Canvas. The Canvas is also the
 * tap target: `pointerInput { detectTapGestures }` converts the touch
 * location to a Square via `BoardGeometry.squareAt` and hands it back
 * via `onSquareTap`. Taps outside the board fall through to
 * `onOutsideTap`.
 *
 * X and O glyphs are Canvas primitives: two crossed `StrokeCap.Round`
 * lines for X and a stroked circle for O. Quantum-mark move numbers
 * render as plain digit text centred just below their glyph; no
 * special font glyphs (subscripts, large-circle, multiplication-X) are
 * required, so the same rendering works identically on every target.
 */
@Composable
fun BoardCanvas(
    state: GameState,
    selection: Square?,
    onSquareTap: (Square) -> Unit,
    modifier: Modifier = Modifier,
    illegalEndpoints: Set<Square> = emptySet(),
    highlightedSquares: Set<Square> = emptySet(),
    indicatorSquare: Square? = null,
    sentBoards: Pair<Int, Int>? = null,
    onOutsideTap: () -> Unit = {},
) {
    val textMeasurer = rememberTextMeasurer()
    val gridColor = MaterialTheme.colorScheme.onSurface
    val highlightColor = MaterialTheme.colorScheme.primary
    val tintColor = MaterialTheme.colorScheme.tertiary
    val sentColor = MaterialTheme.colorScheme.secondary
    // Last-move highlight: bright amber, theme-independent, so it
    // reads as "the pair just placed" rather than being mistaken for
    // any other primary-coloured selection state on the board.
    val lastMoveColor = Color(0xFFFFB300)

    Canvas(
        modifier =
            modifier.pointerInput(state.variant) {
                detectTapGestures { offset ->
                    val geometry =
                        BoardGeometry.fit(
                            Size(size.width.toFloat(), size.height.toFloat()),
                            state.variant,
                        )
                    val square = geometry.squareAt(offset)
                    if (square != null) onSquareTap(square) else onOutsideTap()
                }
            },
    ) {
        val geometry = BoardGeometry.fit(size, state.variant)
        // Pre-compute the glyph centre for every quantum mark so the
        // entanglement curves and the mark glyphs agree on where the
        // mark actually sits (otherwise lines end at the square centre
        // while the glyphs are drawn offset around an orbit).
        val markPositions = computeMarkPositions(state, geometry)
        drawGrids(geometry, gridColor)
        if (sentBoards != null) drawSentBoards(sentBoards, geometry, sentColor)
        drawEntanglements(state, geometry, markPositions)
        if (highlightedSquares.isNotEmpty()) {
            drawHighlightedSquares(highlightedSquares, geometry, tintColor)
        }
        val lastMove = state.lastMove
        if (lastMove != null) drawLastMoveHighlight(lastMove, geometry, lastMoveColor)
        if (illegalEndpoints.isNotEmpty()) {
            // Hash the squares the constraint says are illegal with one
            // diagonal direction; hash the already-classical squares
            // with the opposite diagonal so the legal (un-hashed)
            // squares stand out as the only tappable cells.
            drawHashedSquares(
                illegalEndpoints,
                geometry,
                gridColor,
                HashDirection.TOP_LEFT_TO_BOTTOM_RIGHT,
            )
            drawHashedSquares(
                state.classical.keys,
                geometry,
                gridColor,
                HashDirection.BOTTOM_LEFT_TO_TOP_RIGHT,
            )
        }
        if (selection != null) drawSelection(selection, geometry, highlightColor)
        drawClassicalMarks(state, geometry)
        drawQuantumMarks(state, geometry, textMeasurer, markPositions)
        drawWonBoardRings(state, geometry)
        if (indicatorSquare != null) {
            drawIndicatorArrow(indicatorSquare, geometry, highlightColor)
        }
    }
}

/**
 * The pixel centre of every quantum mark's glyph. Marks sharing a
 * square are spread around the square's centre on a circular orbit;
 * this is the single source of truth so glyph drawing and
 * entanglement endpoints agree.
 */
private fun computeMarkPositions(
    state: GameState,
    geometry: BoardGeometry,
): Map<QuantumMark, Offset> {
    val result = mutableMapOf<QuantumMark, Offset>()
    state.quantum.groupBy { it.square }.forEach { (square, marks) ->
        val center = geometry.squareCenter(square)
        val n = marks.size
        marks.forEachIndexed { i, mark ->
            result[mark] =
                if (n == 1) {
                    center
                } else {
                    val angle = (i * 2 * PI / n).toFloat()
                    val orbit = geometry.squareSize * 0.22f
                    Offset(center.x + orbit * cos(angle), center.y + orbit * sin(angle))
                }
        }
    }
    return result
}

/**
 * A small filled triangle inside the top of `square`, pointing down,
 * to mark the square the collapse-picker label is talking about.
 * Drawn after the marks so it sits on top.
 */
private fun DrawScope.drawIndicatorArrow(
    square: Square,
    geometry: BoardGeometry,
    color: Color,
) {
    val rect = geometry.squareRect(square)
    val baseY = rect.top + geometry.squareSize * 0.06f
    val halfBase = geometry.squareSize * 0.10f
    val height = geometry.squareSize * 0.14f
    val tipX = rect.center.x
    val path =
        Path().apply {
            moveTo(tipX - halfBase, baseY)
            lineTo(tipX + halfBase, baseY)
            lineTo(tipX, baseY + height)
            close()
        }
    drawPath(path = path, color = color)
}

/**
 * Tinted background fill on every square in `squares` so the chooser
 * can see at a glance which cells the collapse-picker's letters refer
 * to. Drawn before the marks so they remain readable on top.
 */
private fun DrawScope.drawHighlightedSquares(
    squares: Set<Square>,
    geometry: BoardGeometry,
    color: Color,
) {
    squares.forEach { sq ->
        val rect = geometry.squareRect(sq)
        drawRect(
            color = color.copy(alpha = 0.22f),
            topLeft = rect.topLeft,
            size = rect.size,
        )
    }
}

private enum class HashDirection {
    TOP_LEFT_TO_BOTTOM_RIGHT,
    BOTTOM_LEFT_TO_TOP_RIGHT,
}

/**
 * Diagonal hash overlay on each square in `squares`. The direction is
 * chosen so the two "can't tap here" reasons (constraint-illegal vs
 * already-classical) read as distinct families at a glance. Uses
 * `clipRect` so the lines do not bleed past the square's border.
 */
private fun DrawScope.drawHashedSquares(
    squares: Set<Square>,
    geometry: BoardGeometry,
    color: Color,
    direction: HashDirection,
) {
    val stroke = geometry.squareSize * 0.025f
    val spacing = geometry.squareSize * 0.18f
    // Light enough that entanglement curves passing through hashed
    // squares stay readable; just dense enough that the un-hashed
    // tappable squares still pop.
    val lineColor = color.copy(alpha = 0.20f)
    squares.forEach { sq ->
        val rect = geometry.squareRect(sq)
        clipRect(rect.left, rect.top, rect.right, rect.bottom) {
            var x = rect.left - rect.height
            while (x < rect.right) {
                val (startY, endY) =
                    when (direction) {
                        HashDirection.TOP_LEFT_TO_BOTTOM_RIGHT -> rect.top to rect.bottom
                        HashDirection.BOTTOM_LEFT_TO_TOP_RIGHT -> rect.bottom to rect.top
                    }
                drawLine(
                    color = lineColor,
                    start = Offset(x, startY),
                    end = Offset(x + rect.height, endY),
                    strokeWidth = stroke,
                )
                x += spacing
            }
        }
    }
}

// -- Drawing helpers ------------------------------------------------------

private fun DrawScope.drawGrids(
    geometry: BoardGeometry,
    color: Color,
) {
    val boards =
        when (geometry.variant) {
            Variant.QUANTUM_TIC_TAC_TOE -> intArrayOf(1)
            Variant.QUANTUM_TIC_TAC_TOE_SQUARED,
            Variant.ULTIMATE_QUANTUM_TIC_TAC_TOE,
            Variant.ULTIMATE_TIC_TAC_TOE,
            -> IntArray(9) { it + 1 }
        }
    val innerStroke = geometry.squareSize * 0.03f
    val outerStroke = geometry.squareSize * 0.06f
    for (board in boards) {
        val origin = geometry.miniBoardOrigin(board)
        val mbSize = geometry.miniBoardSize
        for (i in 1..2) {
            drawLine(
                color = color,
                start = Offset(origin.x, origin.y + i * geometry.squareSize),
                end = Offset(origin.x + mbSize, origin.y + i * geometry.squareSize),
                strokeWidth = innerStroke,
            )
            drawLine(
                color = color,
                start = Offset(origin.x + i * geometry.squareSize, origin.y),
                end = Offset(origin.x + i * geometry.squareSize, origin.y + mbSize),
                strokeWidth = innerStroke,
            )
        }
        if (geometry.variant != Variant.QUANTUM_TIC_TAC_TOE) {
            drawRect(
                color = color,
                topLeft = origin,
                size = Size(mbSize, mbSize),
                style = Stroke(width = outerStroke),
            )
        }
    }
}

private fun DrawScope.drawSentBoards(
    sent: Pair<Int, Int>,
    geometry: BoardGeometry,
    color: Color,
) {
    val mbSize = geometry.miniBoardSize
    val stroke = geometry.squareSize * 0.10f
    val boards = if (sent.first == sent.second) intArrayOf(sent.first) else intArrayOf(sent.first, sent.second)
    for (board in boards) {
        val origin = geometry.miniBoardOrigin(board)
        drawRect(
            color = color,
            topLeft = origin,
            size = Size(mbSize, mbSize),
            style = Stroke(width = stroke),
        )
    }
}

private fun DrawScope.drawClassicalMarks(
    state: GameState,
    geometry: BoardGeometry,
) {
    val halfSize = geometry.squareSize * 0.28f
    val radius = geometry.squareSize * 0.30f
    val stroke = geometry.squareSize * 0.10f
    state.classical.forEach { (square, player) ->
        val center = geometry.squareCenter(square)
        val color = colorOf(player)
        if (player == Player.X) {
            drawXGlyph(center, halfSize, color, stroke)
        } else {
            drawOGlyph(center, radius, color, stroke)
        }
    }
}

private fun DrawScope.drawQuantumMarks(
    state: GameState,
    geometry: BoardGeometry,
    textMeasurer: TextMeasurer,
    markPositions: Map<QuantumMark, Offset>,
) {
    val halfSize = geometry.squareSize * 0.13f
    val radius = geometry.squareSize * 0.14f
    val stroke = geometry.squareSize * 0.05f
    val numberFontSize = (geometry.squareSize * 0.14f).toSp()
    state.quantum.forEach { mark ->
        val pos = markPositions[mark] ?: return@forEach
        val color = colorOf(mark.player)
        if (mark.player == Player.X) {
            drawXGlyph(pos, halfSize, color, stroke)
        } else {
            drawOGlyph(pos, radius, color, stroke)
        }
        // The move number sits centred just below the glyph -- placing
        // it inside the glyph's bounds (subscript-style) overlapped the
        // X strokes and the O outline.
        val style = TextStyle(color = color, fontSize = numberFontSize)
        val result = textMeasurer.measure(mark.moveNumber.toString(), style)
        drawText(
            textLayoutResult = result,
            topLeft =
                Offset(
                    pos.x - result.size.width / 2f,
                    pos.y + halfSize + geometry.squareSize * 0.02f,
                ),
        )
    }
}

private fun DrawScope.drawXGlyph(
    center: Offset,
    halfSize: Float,
    color: Color,
    stroke: Float,
) {
    drawLine(
        color = color,
        start = Offset(center.x - halfSize, center.y - halfSize),
        end = Offset(center.x + halfSize, center.y + halfSize),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = color,
        start = Offset(center.x + halfSize, center.y - halfSize),
        end = Offset(center.x - halfSize, center.y + halfSize),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawOGlyph(
    center: Offset,
    radius: Float,
    color: Color,
    stroke: Float,
) {
    drawCircle(
        color = color,
        radius = radius,
        center = center,
        style = Stroke(width = stroke),
    )
}

private fun DrawScope.drawEntanglements(
    state: GameState,
    geometry: BoardGeometry,
    markPositions: Map<QuantumMark, Offset>,
) {
    val stroke = geometry.squareSize * 0.035f
    state.entanglements.forEach { edge ->
        val a = markPositions[edge.a] ?: geometry.squareCenter(edge.a.square)
        val b = markPositions[edge.b] ?: geometry.squareCenter(edge.b.square)
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = sqrt(dx * dx + dy * dy)
        val (px, py) =
            if (len > 0f) Pair(-dy / len, dx / len) else Pair(0f, 0f)
        val bend = len * 0.18f
        val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
        val control = Offset(mid.x + px * bend, mid.y + py * bend)
        val path =
            Path().apply {
                moveTo(a.x, a.y)
                quadraticTo(control.x, control.y, b.x, b.y)
            }
        drawPath(
            path = path,
            color = colorOf(edge.player).copy(alpha = 0.65f),
            style = Stroke(width = stroke),
        )
    }
}

private fun DrawScope.drawLastMoveHighlight(
    lastMove: Move,
    geometry: BoardGeometry,
    color: Color,
) {
    val stroke = geometry.squareSize * 0.06f
    val dashOn = geometry.squareSize * 0.12f
    val dashOff = geometry.squareSize * 0.08f
    val pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashOn, dashOff), 0f)
    listOf(lastMove.a, lastMove.b).forEach { sq ->
        val rect = geometry.squareRect(sq).deflate(stroke / 2f)
        drawRect(
            color = color,
            topLeft = rect.topLeft,
            size = rect.size,
            style = Stroke(width = stroke, pathEffect = pathEffect),
        )
    }
}

private fun DrawScope.drawSelection(
    selection: Square,
    geometry: BoardGeometry,
    color: Color,
) {
    drawRect(
        color = color.copy(alpha = 0.18f),
        topLeft = geometry.squareRect(selection).topLeft,
        size = Size(geometry.squareSize, geometry.squareSize),
    )
}

private fun DrawScope.drawWonBoardRings(
    state: GameState,
    geometry: BoardGeometry,
) {
    val stroke = geometry.squareSize * 0.10f
    val inset = stroke * 1.6f
    // A shared mini-board draws a second concentric ring inset by `inset`
    // so both winners' colours are visible.
    state.wonBoards.forEach { (board, winners) ->
        val rect = geometry.miniBoardRect(board)
        winners.forEachIndexed { i, player ->
            val off = inset * i
            drawRect(
                color = colorOf(player).copy(alpha = 0.55f),
                topLeft = Offset(rect.topLeft.x + off, rect.topLeft.y + off),
                size = Size(rect.size.width - 2 * off, rect.size.height - 2 * off),
                style = Stroke(width = stroke),
            )
        }
    }
}
