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

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import net.yonge_mallo.uqttt.ai.chooseClassicalMove
import net.yonge_mallo.uqttt.engine.ClassicalGameState
import net.yonge_mallo.uqttt.engine.ClassicalIllegalReason
import net.yonge_mallo.uqttt.engine.ClassicalRules
import net.yonge_mallo.uqttt.persist.gameFileOps
import net.yonge_mallo.uqttt.ui.game.AdvancedActionsMenu
import net.yonge_mallo.uqttt.ui.game.BoardArea
import net.yonge_mallo.uqttt.ui.game.ClassicalGameViewModel
import net.yonge_mallo.uqttt.ui.game.SystemBackGuard
import net.yonge_mallo.uqttt.ui.game.toBoardView
import kotlin.coroutines.coroutineContext
import kotlin.time.TimeSource

/**
 * Wall-clock floor between AI moves in demo (AI-vs-AI) mode so fast
 * turns stay watchable at the lower difficulties. Applied at the top
 * of the AI-turn `LaunchedEffect` before the thinking indicator
 * appears; longer natural thinking times pass through unchanged.
 */
private const val MIN_AI_MOVE_INTERVAL_MS: Long = 500L

/**
 * Classical Ultimate game screen. Sibling to `GameScreen`; the shape
 * is nearly the same (top bar with undo / redo, snackbar for illegal
 * moves, back-confirm dialog, AI progress bar, keyboard shortcuts)
 * but there is no collapse-picker path -- classical play never has a
 * pending collapse -- so the layout is always board-only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassicalGameScreen(
    setup: GameSetup,
    onExit: () -> Unit,
    loadedInitial: ClassicalGameState? = null,
) {
    val viewModel =
        remember(setup, loadedInitial) {
            ClassicalGameViewModel(
                initial = loadedInitial ?: ClassicalRules.initial(setup.variant),
                aiPlayers = setup.players.aiPlayers(),
            )
        }
    val snackbarHostState = remember { SnackbarHostState() }
    val illegalReason = viewModel.illegalReason
    val focusRequester = remember { FocusRequester() }

    var showBackConfirm: Boolean by remember { mutableStateOf(false) }
    var thinkingProgress: Float by remember { mutableStateOf(0f) }
    var paused: Boolean by remember { mutableStateOf(false) }

    val demoMode = setup.players == PlayersConfig.AI_VS_AI

    // File-picker actions only render when the platform supports
    // them (web only today). See `AdvancedActionsMenu`.
    val fileOps = gameFileOps
    val fileOpsScope = rememberCoroutineScope()

    // Route a platform back gesture (Android system Back, browser Back
    // on Wasm) through the same confirmation dialog as the in-app Back
    // button so the user can't lose a game in progress by accident.
    // No-op on desktop and iOS.
    SystemBackGuard { showBackConfirm = true }

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    LaunchedEffect(illegalReason) {
        if (illegalReason != null) {
            snackbarHostState.showSnackbar(message = illegalReason.displayMessage())
            viewModel.dismissIllegalReason()
        }
    }

    val state = viewModel.current

    // AI turns. Re-keyed on `current` so undo / new moves cancel any
    // in-flight AI run; the `finally` always clears the thinking flag.
    LaunchedEffect(state, setup.players, paused) {
        if (state.isGameOver) return@LaunchedEffect
        if (setup.players.kindFor(state.nextPlayer) != PlayerKind.AI) return@LaunchedEffect
        if (paused) return@LaunchedEffect
        // In demo mode (both seats AI) pace each move against a floor so
        // fast AI turns don't blur past the eye. A pause at the top of
        // the effect body cancels cleanly on any re-key (pause, back,
        // undo) without touching the thinking indicator or progress bar.
        if (demoMode) delay(MIN_AI_MOVE_INTERVAL_MS)
        val maxIterations = setup.difficulty.maxMoveIterations
        val maxTimeMs = setup.difficulty.maxMoveTimeMs
        viewModel.thinking = true
        val start = TimeSource.Monotonic.markNow()
        val progressJob =
            launch {
                while (true) {
                    val frac =
                        (start.elapsedNow().inWholeMilliseconds.toFloat() / maxTimeMs)
                            .coerceIn(0f, 1f)
                    thinkingProgress = frac
                    delay(50)
                }
            }
        try {
            val move = chooseClassicalMove(state, maxIterations, maxTimeMs)
            coroutineContext.ensureActive()
            // Guard against the tiny window between the effect scope
            // being cancelled (via undo) and the coroutine seeing the
            // cancellation: if `viewModel.current` no longer equals
            // the state we computed for, the move is stale and
            // applying it would crash `applyAiMove`'s legality check.
            if (viewModel.current == state) {
                viewModel.applyAiMove(move)
            }
        } finally {
            progressJob.cancel()
            thinkingProgress = 0f
            viewModel.thinking = false
        }
    }

    // Sending-rule highlight: the mini-board the previous move sends to.
    // Only shown when the send is actually enforceable -- a closed
    // target (free play) doesn't get the ring.
    val sentBoards: Pair<Int, Int>? =
        state.effectiveRequiredBoard?.let { Pair(it, it) }

    Scaffold(
        modifier =
            Modifier
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    // Space toggles pause / resume in demo mode.
                    // Checked before the Ctrl gate below because it
                    // has no modifier requirement, matching the
                    // universal video-playback convention.
                    if (demoMode && event.key == Key.Spacebar) {
                        paused = !paused
                        return@onKeyEvent true
                    }
                    if (!event.isCtrlPressed) return@onKeyEvent false
                    // Ctrl+Z / Ctrl+Y / Ctrl+Shift+Z drain the entire
                    // AI-vs-AI history in one click (the loops chain
                    // through AI states); mirror the top-bar
                    // behaviour and swallow the keystroke in demo.
                    if (demoMode) return@onKeyEvent false
                    when (event.key) {
                        Key.Z -> {
                            if (event.isShiftPressed) viewModel.redo() else viewModel.undo()
                            true
                        }
                        Key.Y -> {
                            viewModel.redo()
                            true
                        }
                        else -> false
                    }
                },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(setup.variant.displayName()) },
                    navigationIcon = {
                        TextButton(onClick = { showBackConfirm = true }) { Text("Back") }
                    },
                    actions = {
                        if (demoMode) {
                            TextButton(onClick = { paused = !paused }) {
                                Text(if (paused) "Resume" else "Pause")
                            }
                        } else {
                            TextButton(
                                onClick = viewModel::undo,
                                enabled = viewModel.canUndo,
                            ) { Text("Undo") }
                            TextButton(
                                onClick = viewModel::redo,
                                enabled = viewModel.canRedo,
                            ) { Text("Redo") }
                        }
                        if (fileOps != null) {
                            AdvancedActionsMenu(
                                onSave = { fileOpsScope.launch { fileOps.saveGame(viewModel.current) } },
                                onExportTikz = { fileOpsScope.launch { fileOps.exportTikz(viewModel.current) } },
                            )
                        }
                    },
                )
                // Always in the layout tree so the topBar's height (and
                // therefore the Scaffold's content padding) is constant;
                // `alpha` toggles visibility without a size change, so
                // the board doesn't jitter each time the AI starts or
                // stops thinking.
                LinearProgressIndicator(
                    progress = { thinkingProgress },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .alpha(if (viewModel.thinking) 1f else 0f),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).padding(8.dp)) {
            BoardArea(
                view = state.toBoardView(),
                selection = null,
                onSquareTap = viewModel::onSquareTap,
                modifier = Modifier.fillMaxSize(),
                illegalEndpoints = viewModel.illegalEndpoints,
                sentBoards = sentBoards,
            )
        }

        if (state.isGameOver) {
            ClassicalGameOverDialog(
                state = state,
                onUndo = viewModel::undo,
                onPlayAgain = { viewModel.reset(ClassicalRules.initial(setup.variant)) },
                onMainMenu = onExit,
                demoMode = demoMode,
            )
        }

        if (showBackConfirm) {
            AlertDialog(
                onDismissRequest = { showBackConfirm = false },
                title = { Text("Leave game?") },
                text = { Text("The current game will be lost.") },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { showBackConfirm = false }) { Text("Stay") }
                        TextButton(onClick = {
                            showBackConfirm = false
                            onExit()
                        }) { Text("Leave") }
                    }
                },
            )
        }
    }
}

@Composable
private fun ClassicalGameOverDialog(
    state: ClassicalGameState,
    onUndo: () -> Unit,
    onPlayAgain: () -> Unit,
    onMainMenu: () -> Unit,
    demoMode: Boolean = false,
) {
    val title =
        when {
            state.isSharedWin -> "Shared win"
            state.winner != null -> "${state.winner} wins"
            else -> "Draw"
        }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = { Text("") },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!demoMode) {
                    TextButton(onClick = onUndo) { Text("Undo") }
                }
                TextButton(onClick = onPlayAgain) {
                    Text(if (demoMode) "New demo" else "Play again")
                }
                TextButton(onClick = onMainMenu) { Text("Main menu") }
            }
        },
    )
}

private fun ClassicalIllegalReason.displayMessage(): String =
    when (this) {
        ClassicalIllegalReason.SQUARE_OCCUPIED -> "That square already has a mark."
        ClassicalIllegalReason.NOT_YOUR_TURN -> "It is not your turn."
        ClassicalIllegalReason.WRONG_SENT_BOARD ->
            "The previous move sent you to a specific mini-board."
        ClassicalIllegalReason.BOARD_CLOSED ->
            "That mini-board is already decided."
        ClassicalIllegalReason.GAME_OVER -> "The game is over."
    }
