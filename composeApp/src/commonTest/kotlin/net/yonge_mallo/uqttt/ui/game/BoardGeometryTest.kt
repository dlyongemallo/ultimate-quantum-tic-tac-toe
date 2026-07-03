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
import androidx.compose.ui.geometry.Size
import net.yonge_mallo.uqttt.engine.Square
import net.yonge_mallo.uqttt.engine.Variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Headless coverage for `BoardGeometry`, the pixel-layout layer that
 * sits between Compose's `Size` / `Offset` types and the engine's
 * `Square` coordinates. The engine suite does not exercise it
 * because it depends on Compose UI types, so the round-trips between
 * pixels and squares are pinned down here.
 */
class BoardGeometryTest {
    @Test
    fun quantumMiniBoardOriginIsCanvasOrigin() {
        // In Quantum (single mini-board) the board fills the whole canvas,
        // so the lone mini-board's origin must coincide with the canvas
        // origin -- no meta-grid offset applies.
        val g = BoardGeometry.fit(Size(300f, 300f), Variant.QUANTUM_TIC_TAC_TOE)
        assertEquals(g.origin, g.miniBoardOrigin(1))
    }

    @Test
    fun quantumGeometryFillsTheCanvas() {
        val g = BoardGeometry.fit(Size(300f, 300f), Variant.QUANTUM_TIC_TAC_TOE)
        assertEquals(100f, g.squareSize)
        assertEquals(300f, g.miniBoardSize)
        assertEquals(0f, g.metaGap)
    }

    @Test
    fun quantumPositionsAreInReadingOrder() {
        // 1 = top-left, 2 = top-centre, 3 = top-right,
        // 4 = middle-left, 5 = centre, 6 = middle-right,
        // 7 = bottom-left, 8 = bottom-centre, 9 = bottom-right.
        val g = BoardGeometry.fit(Size(300f, 300f), Variant.QUANTUM_TIC_TAC_TOE)
        val cases =
            listOf(
                1 to Pair(0f, 0f),
                2 to Pair(100f, 0f),
                3 to Pair(200f, 0f),
                4 to Pair(0f, 100f),
                5 to Pair(100f, 100f),
                6 to Pair(200f, 100f),
                7 to Pair(0f, 200f),
                8 to Pair(100f, 200f),
                9 to Pair(200f, 200f),
            )
        for ((position, expected) in cases) {
            val rect = g.squareRect(Square(1, position))
            assertEquals(expected.first, rect.left, "position $position left")
            assertEquals(expected.second, rect.top, "position $position top")
            assertEquals(100f, rect.width, "position $position width")
            assertEquals(100f, rect.height, "position $position height")
        }
    }

    @Test
    fun ultimateBoardsLaidOutInReadingOrder() {
        val g = BoardGeometry.fit(Size(900f, 900f), Variant.QUANTUM_TIC_TAC_TOE_SQUARED)
        val step = g.miniBoardSize + g.metaGap
        // board 1 top-left, 5 centre, 9 bottom-right; 3 top-right; 7 bottom-left.
        assertEquals(Offset(g.origin.x, g.origin.y), g.miniBoardOrigin(1))
        assertEquals(Offset(g.origin.x + 2 * step, g.origin.y), g.miniBoardOrigin(3))
        assertEquals(Offset(g.origin.x + step, g.origin.y + step), g.miniBoardOrigin(5))
        assertEquals(Offset(g.origin.x, g.origin.y + 2 * step), g.miniBoardOrigin(7))
        assertEquals(Offset(g.origin.x + 2 * step, g.origin.y + 2 * step), g.miniBoardOrigin(9))
    }

    @Test
    fun squareAtRoundTripsThroughSquareCenterForEverySquareInBothVariants() {
        for (variant in Variant.entries) {
            val g = BoardGeometry.fit(Size(900f, 900f), variant)
            val boards =
                when (variant) {
                    Variant.QUANTUM_TIC_TAC_TOE -> listOf(1)
                    Variant.QUANTUM_TIC_TAC_TOE_SQUARED,
                    Variant.ULTIMATE_QUANTUM_TIC_TAC_TOE,
                    -> (1..9).toList()
                }
            for (board in boards) {
                for (position in 1..9) {
                    val sq = Square(board, position)
                    val center = g.squareCenter(sq)
                    assertEquals(sq, g.squareAt(center), "round-trip for $sq on $variant")
                }
            }
        }
    }

    @Test
    fun squareAtReturnsNullOutsideTheBoard() {
        val g = BoardGeometry.fit(Size(300f, 300f), Variant.QUANTUM_TIC_TAC_TOE)
        assertNull(g.squareAt(Offset(-10f, 100f)), "left of board")
        assertNull(g.squareAt(Offset(100f, -10f)), "above board")
        assertNull(g.squareAt(Offset(400f, 100f)), "right of board")
        assertNull(g.squareAt(Offset(100f, 400f)), "below board")
    }

    @Test
    fun squareAtReturnsNullInTheMetaBoardGap() {
        // The visible gap between mini-boards in the meta-board variants
        // is not a square, so tapping there is a no-op (no Square to return).
        val g = BoardGeometry.fit(Size(900f, 900f), Variant.QUANTUM_TIC_TAC_TOE_SQUARED)
        val gapCenterX = g.origin.x + g.miniBoardSize + g.metaGap / 2f
        val gapCenterY = g.origin.y + g.miniBoardSize / 2f
        assertNull(g.squareAt(Offset(gapCenterX, gapCenterY)))
    }

    @Test
    fun nonSquareCanvasCentresTheBoardOnTheShorterAxis() {
        // Landscape canvas: side = min(width, height) = 400.
        val g = BoardGeometry.fit(Size(600f, 400f), Variant.QUANTUM_TIC_TAC_TOE)
        assertEquals(100f, g.origin.x)
        assertEquals(0f, g.origin.y)
        assertEquals(400f, g.miniBoardSize)

        // Portrait canvas: side = 400.
        val gp = BoardGeometry.fit(Size(400f, 600f), Variant.QUANTUM_TIC_TAC_TOE)
        assertEquals(0f, gp.origin.x)
        assertEquals(100f, gp.origin.y)
        assertEquals(400f, gp.miniBoardSize)
    }
}
