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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.yonge_mallo.uqttt.BuildInfo
import net.yonge_mallo.uqttt.engine.Variant

/**
 * The opening screen. Radio-button groups for variant and players, with
 * a Start button that hands a `GameSetup` back to the navigator in
 * `App.kt`. State lives in this composable via `remember`.
 */
@Composable
fun MenuScreen(
    initialSetup: GameSetup,
    onStart: (GameSetup) -> Unit,
) {
    var variant: Variant by remember { mutableStateOf(initialSetup.variant) }
    var players: PlayersConfig by remember { mutableStateOf(initialSetup.players) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "Ultimate Quantum Tic-Tac-Toe",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        // The form sits in the middle of the screen as a block; its width
        // matches its widest row (IntrinsicSize.Max) so all sections share
        // a common left edge instead of each centering individually.
        Column(
            modifier = Modifier.align(Alignment.CenterHorizontally).width(IntrinsicSize.Max),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Section(title = "Game variant") {
                Variant.entries.forEach { v ->
                    ChoiceRow(
                        label = v.displayName(),
                        selected = v == variant,
                        onSelect = { variant = v },
                    )
                }
            }

            Section(title = "Players") {
                PlayersConfig.entries.forEach { p ->
                    ChoiceRow(
                        label = p.label,
                        selected = p == players,
                        onSelect = { players = p },
                    )
                }
            }
        }

        Button(
            onClick = { onStart(GameSetup(variant, players)) },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text("Start")
        }

        // Small build-identifier footer; subdued so it doesn't compete
        // with the form but visible enough to confirm at a glance
        // which build is running.
        Text(
            text = "build ${BuildInfo.GIT_HASH}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    // The Row is the selectable target so the entire label area toggles the radio,
    // matching the Material 3 guidance for radio rows.
    Row(
        modifier =
            Modifier
                .selectable(selected = selected, onClick = onSelect)
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}
