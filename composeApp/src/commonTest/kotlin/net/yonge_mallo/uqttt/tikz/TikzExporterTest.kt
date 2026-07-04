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

package net.yonge_mallo.uqttt.tikz

import net.yonge_mallo.uqttt.engine.ClassicalMove
import net.yonge_mallo.uqttt.engine.ClassicalMoveResult
import net.yonge_mallo.uqttt.engine.ClassicalRules
import net.yonge_mallo.uqttt.engine.Move
import net.yonge_mallo.uqttt.engine.MoveResult
import net.yonge_mallo.uqttt.engine.Player
import net.yonge_mallo.uqttt.engine.Rules
import net.yonge_mallo.uqttt.engine.Square
import net.yonge_mallo.uqttt.engine.Variant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Structural checks on the TikZ output plus two golden-file tests
 * (one per engine) for stable-format sanity. The golden expectations
 * live inline; when a deliberate output change lands, update the
 * literals here.
 *
 * Golden coverage is intentionally minimal -- one fresh state per
 * engine. Broader coverage would over-fit the tests to formatting
 * choices that are not part of the contract (spacing, decimal
 * padding, comment wording).
 */
class TikzExporterTest {
    @Test
    fun quantumFreshBoardEmitsShellButNoMarks() {
        val out = exportTikz(Rules.initial(Variant.QUANTUM_TIC_TAC_TOE))
        assertContains(out, "\\begin{tikzpicture}")
        assertContains(out, "\\end{tikzpicture}")
        assertContains(out, "\\definecolor{uqtttMarkX}")
        assertContains(out, "\\definecolor{uqtttMarkO}")
        // No marks or entanglements on a fresh board.
        assertFalse(out.contains("\\times"), "fresh board should have no X glyph")
        assertFalse(out.contains("\\circ"), "fresh board should have no O glyph")
        assertFalse(out.contains("controls"), "fresh board should have no entanglement arcs")
    }

    @Test
    fun quantumMidGameEmitsExpectedElements() {
        val s0 = Rules.initial(Variant.QUANTUM_TIC_TAC_TOE_SQUARED)
        val s1 = assertIs<MoveResult.Legal>(Rules.apply(s0, Move(1, Square(1, 1), Square(2, 5)))).nextState
        val out = exportTikz(s1)
        // Each quantum X is emitted as two `\draw` cross strokes
        // (round-capped) instead of a math glyph; there are two
        // endpoints for move 1, so two cross-stroke commands.
        val quantumStrokeLines =
            out.lines().count { it.contains("line width=1.5pt, line cap=round") }
        assertEquals(2, quantumStrokeLines, "expected two quantum X strokes at move 1")
        // Each mark carries a move-number label in scriptsize; the
        // label content is just the digit.
        val moveLabels =
            out.lines().count { it.contains("font=\\tiny") && it.contains("{1}") }
        assertEquals(2, moveLabels, "expected a scriptsize move-number label under each mark")
        // One entanglement arc between the two endpoints.
        assertTrue(
            out.lines().count { it.contains(".. controls") } == 1,
            "expected exactly one entanglement arc",
        )
        // Last-move dashed rectangles on both endpoints.
        assertTrue(
            out.lines().count { it.contains("dashed") } == 2,
            "expected a dashed last-move highlight around each endpoint",
        )
    }

    @Test
    fun quantumUltimateEmitsSentBoardsHighlight() {
        val s0 = Rules.initial(Variant.ULTIMATE_QUANTUM_TIC_TAC_TOE)
        // X plays 5/3 -- 5/7; the sending rule sends O to mini-boards 3 and 7.
        val s1 = assertIs<MoveResult.Legal>(Rules.apply(s0, Move(1, Square(5, 3), Square(5, 7)))).nextState
        val out = exportTikz(s1)
        // Two mini-board rectangles for the sent-boards highlight,
        // in the secondary-blue-ish colour (present here as `25;green,118;blue,210`).
        val sentLines = out.lines().count { it.contains("25;green,118;blue,210") }
        assertEquals(2, sentLines, "expected sent-boards highlight around both boards 3 and 7")
    }

    @Test
    fun classicalMidGameEmitsClassicalMarkAndNoQuantum() {
        val s0 = ClassicalRules.initial(Variant.ULTIMATE_TIC_TAC_TOE)
        val s1 = assertIs<ClassicalMoveResult.Legal>(ClassicalRules.apply(s0, ClassicalMove(1, Square(5, 3)))).nextState
        val out = exportTikz(s1)
        // Classical X is a round-capped `\draw` cross at the
        // full classical stroke width (3pt).
        assertContains(out, "line width=3pt, line cap=round")
        // No math-mode subscripts and no `\times` text glyphs
        // anywhere in the file (marks are line segments, not
        // symbols).
        assertFalse(out.contains("\\times"), "classical mark should be drawn strokes, not a math glyph")
        // No move-number labels in classical: single-mark placements
        // don't need a subscript.
        assertFalse(
            out.lines().any { it.contains("font=\\tiny") },
            "classical export has no quantum-mark move labels",
        )
        // No entanglement arcs in the classical variant.
        assertFalse(out.lines().any { it.contains(".. controls") }, "classical export has no arcs")
        // Sending rule sends to mini-board 3.
        val sentLines = out.lines().count { it.contains("25;green,118;blue,210") }
        assertEquals(1, sentLines, "expected one sent-board highlight (board 3)")
    }

    @Test
    fun classicalRingAppearsAroundWonBoard() {
        // Direct-state construction so the fixture pins wonBoards
        // without a long play sequence.
        val state =
            ClassicalRules.initial(Variant.ULTIMATE_TIC_TAC_TOE).copy(
                wonBoards = mapOf(1 to setOf(Player.X)),
                nextMoveNumber = 4,
            )
        val out = exportTikz(state)
        val ringLines = out.lines().count { it.contains("opacity=0.55") }
        assertEquals(1, ringLines, "expected one won-board ring")
    }

    @Test
    fun quantumSharedWonBoardEmitsTwoConcentricRings() {
        val state =
            Rules.initial(Variant.QUANTUM_TIC_TAC_TOE_SQUARED).copy(
                wonBoards = mapOf(1 to setOf(Player.X, Player.O)),
                nextMoveNumber = 10,
            )
        val out = exportTikz(state)
        val ringLines = out.lines().count { it.contains("opacity=0.55") }
        assertEquals(2, ringLines, "shared board should draw one ring per winner")
    }

    @Test
    fun freshQuantumBoardMatchesGolden() {
        val out = exportTikz(Rules.initial(Variant.QUANTUM_TIC_TAC_TOE))
        val expected =
            """
            % Quantum Tic-Tac-Toe board.
            % Generated by UQTTT. Paste into a document loading \usepackage{tikz} and \usepackage{xcolor}.
            \definecolor{uqtttMarkX}{RGB}{211,47,47}
            \definecolor{uqtttMarkO}{RGB}{46,125,50}
            \definecolor{uqtttMarkLast}{RGB}{255,179,0}
            \begin{tikzpicture}[scale=0.6, line join=round]
              % Inner cell lines
              \draw[line width=0.5pt] (1, 0) -- (1, 3);
              \draw[line width=0.5pt] (0, 1) -- (3, 1);
              \draw[line width=0.5pt] (2, 0) -- (2, 3);
              \draw[line width=0.5pt] (0, 2) -- (3, 2);
              % Single-board outer outline
              \draw[line width=1.5pt] (0, 0) rectangle (3, 3);
            \end{tikzpicture}
            """.trimIndent() + "\n"
        assertEquals(expected, out)
    }

    @Test
    fun freshClassicalBoardMatchesGolden() {
        val out = exportTikz(ClassicalRules.initial(Variant.ULTIMATE_TIC_TAC_TOE))
        val expected =
            """
            % Classical Ultimate Tic-Tac-Toe board.
            % Generated by UQTTT. Paste into a document loading \usepackage{tikz} and \usepackage{xcolor}.
            \definecolor{uqtttMarkX}{RGB}{211,47,47}
            \definecolor{uqtttMarkO}{RGB}{46,125,50}
            \definecolor{uqtttMarkLast}{RGB}{255,179,0}
            \begin{tikzpicture}[scale=0.6, line join=round]
              % Inner cell lines
              \draw[line width=0.5pt] (1, 0) -- (1, 9);
              \draw[line width=0.5pt] (0, 1) -- (9, 1);
              \draw[line width=0.5pt] (2, 0) -- (2, 9);
              \draw[line width=0.5pt] (0, 2) -- (9, 2);
              \draw[line width=0.5pt] (3, 0) -- (3, 9);
              \draw[line width=0.5pt] (0, 3) -- (9, 3);
              \draw[line width=0.5pt] (4, 0) -- (4, 9);
              \draw[line width=0.5pt] (0, 4) -- (9, 4);
              \draw[line width=0.5pt] (5, 0) -- (5, 9);
              \draw[line width=0.5pt] (0, 5) -- (9, 5);
              \draw[line width=0.5pt] (6, 0) -- (6, 9);
              \draw[line width=0.5pt] (0, 6) -- (9, 6);
              \draw[line width=0.5pt] (7, 0) -- (7, 9);
              \draw[line width=0.5pt] (0, 7) -- (9, 7);
              \draw[line width=0.5pt] (8, 0) -- (8, 9);
              \draw[line width=0.5pt] (0, 8) -- (9, 8);
              % Mini-board outer outlines
              \draw[line width=1.5pt] (0, 6) rectangle (3, 9);
              \draw[line width=1.5pt] (3, 6) rectangle (6, 9);
              \draw[line width=1.5pt] (6, 6) rectangle (9, 9);
              \draw[line width=1.5pt] (0, 3) rectangle (3, 6);
              \draw[line width=1.5pt] (3, 3) rectangle (6, 6);
              \draw[line width=1.5pt] (6, 3) rectangle (9, 6);
              \draw[line width=1.5pt] (0, 0) rectangle (3, 3);
              \draw[line width=1.5pt] (3, 0) rectangle (6, 3);
              \draw[line width=1.5pt] (6, 0) rectangle (9, 3);
            \end{tikzpicture}
            """.trimIndent() + "\n"
        assertEquals(expected, out)
    }
}
