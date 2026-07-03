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

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import net.yonge_mallo.uqttt.engine.Square
import net.yonge_mallo.uqttt.engine.Variant

/**
 * Maps between board / square indices and pixel rects in the Canvas
 * coordinate space. Cheap to construct -- one allocation per draw
 * frame or pointer event.
 *
 * Indices 1..9 are reading order (left-to-right, top-to-bottom),
 * i.e., 1 = top-left ... 9 = bottom-right:
 *   1 2 3
 *   4 5 6
 *   7 8 9
 * Both the meta-board (`board`) and per-mini-board (`position`) use
 * this same convention.
 */
class BoardGeometry private constructor(
    val canvasSize: Size,
    val variant: Variant,
    val origin: Offset,
    val miniBoardSize: Float,
    val squareSize: Float,
    val metaGap: Float,
) {
    fun miniBoardOrigin(board: Int): Offset {
        // In Quantum the only board is board=1; the meta-grid does not exist,
        // so the mini-board sits at the canvas origin.
        if (variant == Variant.QUANTUM_TIC_TAC_TOE) return origin
        val col = (board - 1) % 3
        val row = (board - 1) / 3
        return Offset(
            origin.x + col * (miniBoardSize + metaGap),
            origin.y + row * (miniBoardSize + metaGap),
        )
    }

    fun miniBoardRect(board: Int): Rect {
        val o = miniBoardOrigin(board)
        return Rect(o, Size(miniBoardSize, miniBoardSize))
    }

    fun squareRect(square: Square): Rect {
        val mbOrigin = miniBoardOrigin(square.board)
        val col = (square.position - 1) % 3
        val row = (square.position - 1) / 3
        val x = mbOrigin.x + col * squareSize
        val y = mbOrigin.y + row * squareSize
        return Rect(x, y, x + squareSize, y + squareSize)
    }

    fun squareCenter(square: Square): Offset = squareRect(square).center

    /** Hit-test: which square (if any) contains the given canvas point. */
    fun squareAt(point: Offset): Square? {
        val boards =
            when (variant) {
                Variant.QUANTUM_TIC_TAC_TOE -> intArrayOf(1)
                Variant.QUANTUM_TIC_TAC_TOE_SQUARED,
                Variant.ULTIMATE_QUANTUM_TIC_TAC_TOE,
                -> IntArray(9) { it + 1 }
            }
        for (board in boards) {
            val o = miniBoardOrigin(board)
            if (point.x < o.x || point.y < o.y) continue
            val relX = point.x - o.x
            val relY = point.y - o.y
            if (relX > miniBoardSize || relY > miniBoardSize) continue
            val col = (relX / squareSize).toInt().coerceIn(0, 2)
            val row = (relY / squareSize).toInt().coerceIn(0, 2)
            val position = row * 3 + col + 1
            return Square(board, position)
        }
        return null
    }

    companion object {
        /**
         * Largest centred square that fits inside `canvasSize`. In the
         * meta-board variants we reserve ~2% of the side as a visible
         * gap between mini-boards; in Quantum the gap is zero (one
         * mini-board).
         */
        fun fit(
            canvasSize: Size,
            variant: Variant,
        ): BoardGeometry {
            val side = minOf(canvasSize.width, canvasSize.height)
            val metaGap =
                when (variant) {
                    Variant.QUANTUM_TIC_TAC_TOE -> 0f
                    Variant.QUANTUM_TIC_TAC_TOE_SQUARED,
                    Variant.ULTIMATE_QUANTUM_TIC_TAC_TOE,
                    -> side * 0.02f
                }
            val squareSize =
                when (variant) {
                    Variant.QUANTUM_TIC_TAC_TOE -> side / 3f
                    Variant.QUANTUM_TIC_TAC_TOE_SQUARED,
                    Variant.ULTIMATE_QUANTUM_TIC_TAC_TOE,
                    -> (side - 2 * metaGap) / 9f
                }
            val miniBoardSize = 3 * squareSize
            val origin =
                Offset(
                    (canvasSize.width - side) / 2f,
                    (canvasSize.height - side) / 2f,
                )
            return BoardGeometry(canvasSize, variant, origin, miniBoardSize, squareSize, metaGap)
        }
    }
}
