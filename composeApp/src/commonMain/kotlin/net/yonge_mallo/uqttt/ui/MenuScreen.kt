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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.yonge_mallo.uqttt.BuildInfo
import net.yonge_mallo.uqttt.engine.Variant
import net.yonge_mallo.uqttt.persist.LoadResult
import net.yonge_mallo.uqttt.persist.SavedGame
import net.yonge_mallo.uqttt.persist.gameFileOps

/**
 * The opening screen. Radio-button groups for variant, players, and
 * (when at least one player is AI) difficulty, with a Start button that
 * hands a `GameSetup` back to the navigator in `App.kt`. State lives
 * in this composable via `remember`.
 */
@Composable
fun MenuScreen(
    initialSetup: GameSetup,
    onStart: (GameSetup) -> Unit,
    onLoad: (SavedGame) -> Unit,
) {
    var variant: Variant by remember { mutableStateOf(initialSetup.variant) }
    var players: PlayersConfig by remember { mutableStateOf(initialSetup.players) }
    var difficulty: Difficulty by remember { mutableStateOf(initialSetup.difficulty) }

    val hasAi = players.aiPlayers().isNotEmpty()

    // "Load..." action -- only available where the platform ships a
    // file picker (web today). On other targets the button never
    // renders because `gameFileOps` is null.
    val fileOps = gameFileOps
    val loadScope = rememberCoroutineScope()
    var loadError: String? by remember { mutableStateOf(null) }

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
            // Group the variant list by family so the parent-and-quantum
            // relationship is legible: three quantum games and their
            // classical parent live in the same app, but they are
            // distinct kinds of game.
            Section(title = "Game variant") {
                VariantGroup("Classical") {
                    Variant.entries.filter { it.isClassical }.forEach { v ->
                        ChoiceRow(
                            label = v.displayName(),
                            selected = v == variant,
                            onSelect = { variant = v },
                        )
                    }
                }
                VariantGroup("Quantum") {
                    Variant.entries.filter { it.isQuantum }.forEach { v ->
                        ChoiceRow(
                            label = v.displayName(),
                            selected = v == variant,
                            onSelect = { variant = v },
                        )
                    }
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

            if (hasAi) {
                Section(title = "AI difficulty") {
                    Difficulty.entries.forEach { d ->
                        ChoiceRow(
                            label = d.label,
                            selected = d == difficulty,
                            onSelect = { difficulty = d },
                        )
                    }
                }
            }
        }

        Button(
            onClick = { onStart(GameSetup(variant, players, difficulty)) },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text("Start")
        }

        if (fileOps != null) {
            TextButton(
                onClick = {
                    loadScope.launch {
                        when (val result = fileOps.openGame()) {
                            is LoadResult.Ok -> onLoad(result.saved)
                            is LoadResult.Failed -> loadError = result.reason
                            null -> Unit // user cancelled the picker
                        }
                    }
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("Load...")
            }
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

    loadError?.let { reason ->
        AlertDialog(
            onDismissRequest = { loadError = null },
            title = { Text("Couldn't load game") },
            text = { Text(reason) },
            confirmButton = {
                TextButton(onClick = { loadError = null }) { Text("OK") }
            },
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

/**
 * A subordinate group inside a `Section`: a small header (Classical /
 * Quantum) above its own set of `ChoiceRow`s. Provides the extra
 * vertical breathing room between groups so the two families read as
 * separate.
 */
@Composable
private fun VariantGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
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
