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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import net.yonge_mallo.uqttt.engine.CollapseChoice
import net.yonge_mallo.uqttt.engine.PendingCollapse
import net.yonge_mallo.uqttt.engine.Player
import net.yonge_mallo.uqttt.engine.Square
import net.yonge_mallo.uqttt.ui.colorOf

/**
 * Inline panel offered to a human chooser when a collapse is pending.
 * Sits beside (landscape) or below (portrait) the board, so the board
 * stays fully visible. Each radio shows only the squares whose
 * outcomes differ between the two choices -- common squares would read
 * the same on both lines -- and `GameScreen` highlights the same set
 * of squares on the board so the chooser can map letters to squares
 * by eye. Selecting a radio previews the resolved board upstream;
 * Confirm commits.
 *
 * Trivial collapses (every square identical between choices) are
 * auto-resolved by `GameViewModel.commit` and never reach this panel.
 */
@Composable
fun CollapsePicker(
    pending: PendingCollapse,
    selected: CollapseChoice?,
    indicator: Square?,
    onSelect: (CollapseChoice?) -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "${pending.chooser} chooses the collapse",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "The arrow points at the deciding square.  Pick a choice to preview the outcome, then Confirm.",
                style = MaterialTheme.typography.bodySmall,
            )
            pending.choices.forEachIndexed { i, choice ->
                val outcomeAtIndicator = indicator?.let { choice.outcomes()[it] }
                val isSelected = selected == choice
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = isSelected,
                                // Tap the selected row to deselect it. Useful for
                                // viewing the unresolved superposition (no preview).
                                onClick = { onSelect(if (isSelected) null else choice) },
                            )
                            .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = isSelected, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = indicatorLabel(outcomeAtIndicator),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Button(
                onClick = onConfirm,
                enabled = selected != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Confirm")
            }
        }
    }
}

/** "This square gets X" / "This square gets O" with the X / O coloured per player. */
@Composable
private fun indicatorLabel(player: Player?): AnnotatedString =
    buildAnnotatedString {
        append("This square gets ")
        if (player != null) {
            withStyle(SpanStyle(color = colorOf(player), fontWeight = FontWeight.Bold)) {
                append(if (player == Player.X) "X" else "O")
            }
        } else {
            append("?")
        }
    }

/** Square -> classical-mark player, derived from a choice's mark-to-square assignments. */
internal fun CollapseChoice.outcomes(): Map<Square, Player> =
    assignments.entries.associate { (mark, sq) -> sq to mark.player }

/**
 * The set of squares whose classical outcome differs between the two
 * choices of a pending collapse, sorted in absolute-grid reading order
 * (top-to-bottom, left-to-right over the visible 9x9 grid). Reused by
 * both this panel (to label each choice) and `GameScreen` (to
 * highlight the same set of squares on the board).
 */
internal fun differingSquares(pending: PendingCollapse): List<Square> {
    val outcomes = pending.choices.map { it.outcomes() }
    if (outcomes.isEmpty()) return emptyList()
    val all = outcomes.first().keys
    return all
        .filter { sq -> outcomes.map { it[sq] }.toSet().size > 1 }
        .sortedWith(readingOrderComparator)
}

private val Square.absRow: Int
    get() = ((board - 1) / 3) * 3 + (position - 1) / 3

private val Square.absCol: Int
    get() = ((board - 1) % 3) * 3 + (position - 1) % 3

private val readingOrderComparator: Comparator<Square> =
    compareBy(Square::absRow, Square::absCol)
