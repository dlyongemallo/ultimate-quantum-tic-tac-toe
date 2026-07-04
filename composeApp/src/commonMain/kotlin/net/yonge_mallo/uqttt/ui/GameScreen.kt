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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import net.yonge_mallo.uqttt.ai.chooseCollapse
import net.yonge_mallo.uqttt.ai.chooseMove
import net.yonge_mallo.uqttt.engine.CollapseChoice
import net.yonge_mallo.uqttt.engine.IllegalReason
import net.yonge_mallo.uqttt.engine.Rules
import net.yonge_mallo.uqttt.engine.Square
import net.yonge_mallo.uqttt.engine.Variant
import net.yonge_mallo.uqttt.ui.game.BoardArea
import net.yonge_mallo.uqttt.ui.game.CollapsePicker
import net.yonge_mallo.uqttt.ui.game.GameOverDialog
import net.yonge_mallo.uqttt.ui.game.GameViewModel
import net.yonge_mallo.uqttt.ui.game.SystemBackGuard
import net.yonge_mallo.uqttt.ui.game.differingSquares
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
 * The game screen. Scaffold provides the top bar (back to menu, undo,
 * redo, "Thinking..." indicator) and a SnackbarHost for illegal-move
 * feedback. The body is the board (square) sitting alongside a
 * `CollapsePicker` panel when the chooser is a human; the picker
 * lives beside the board in landscape and below it in portrait, so
 * the board itself is never obscured. Selecting a radio in the
 * picker switches the displayed board to `Rules.resolve(...)` of the
 * pending state, previewing the chosen outcome live. Ctrl+Z and
 * Ctrl+Shift+Z fire undo / redo on platforms that supply key events
 * to the focused Scaffold (desktop; Wasm / Android with a physical
 * keyboard).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreen(
    setup: GameSetup,
    onExit: () -> Unit,
) {
    val viewModel =
        remember(setup) {
            GameViewModel(
                initial = Rules.initial(setup.variant),
                aiPlayers = setup.players.aiPlayers(),
            )
        }
    val snackbarHostState = remember { SnackbarHostState() }
    val illegalReason = viewModel.illegalReason
    val focusRequester = remember { FocusRequester() }

    var previewChoice: CollapseChoice? by remember { mutableStateOf(null) }
    var showBackConfirm: Boolean by remember { mutableStateOf(false) }
    var thinkingProgress: Float by remember { mutableStateOf(0f) }
    var paused: Boolean by remember { mutableStateOf(false) }

    val demoMode = setup.players == PlayersConfig.AI_VS_AI

    // Route a platform back gesture (Android system Back, browser Back
    // on Wasm) through the same confirmation dialog as the in-app Back
    // button so the user can't lose a game in progress by accident.
    // No-op on desktop and iOS.
    SystemBackGuard { showBackConfirm = true }

    // Re-request focus whenever the picker is gone (initial mount and after
    // every collapse resolves). The picker's radio rows and Confirm button
    // pull focus away from the Scaffold root while they're on screen, and
    // without this re-request the Ctrl+Z / Ctrl+Y bindings stop firing until
    // the user clicks one of the top-bar buttons.
    val pendingForFocus = viewModel.current.pendingCollapse
    LaunchedEffect(pendingForFocus) {
        if (pendingForFocus == null) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    LaunchedEffect(illegalReason) {
        if (illegalReason != null) {
            snackbarHostState.showSnackbar(message = illegalReason.displayMessage())
            viewModel.dismissIllegalReason()
        }
    }

    val state = viewModel.current
    val pending = state.pendingCollapse

    // Clear the preview whenever the pending collapse changes (resolved,
    // auto-resolved, or a new one arrived); a stale preview from a prior
    // pending state would be applied to the wrong base state.
    LaunchedEffect(pending) { previewChoice = null }

    val generation = viewModel.generation

    // AI turns. Re-keyed on `viewModel.generation` (bumped by every
    // commit / reset / undo / redo) so any state mutation cancels an
    // in-flight AI run and re-fires this effect; the `finally` always
    // clears the thinking flag. Two guards protect against a stale
    // result: `ensureActive` catches the common case where the
    // LaunchedEffect scope has already been cancelled, and the
    // `viewModel.generation == generation` check closes the narrow race
    // where the coroutine returned before cancellation reached it, or
    // where a reset / undo-redo cycle landed on a structurally
    // identical position (structural equality on `current` would
    // falsely accept the stale move -- generation identity won't).
    // Applying a stale move would then hit `applyAiMove`'s illegal-move
    // assertion and crash. A sibling coroutine ticks
    // `thinkingProgress` so the linear progress bar under the top bar
    // can fill smoothly.
    LaunchedEffect(generation, setup.players, paused) {
        if (state.isGameOver) return@LaunchedEffect
        val toAct = pending?.chooser ?: state.nextPlayer
        if (setup.players.kindFor(toAct) != PlayerKind.AI) return@LaunchedEffect
        if (paused) return@LaunchedEffect
        // In demo mode (both seats AI) pace each move against a floor so
        // fast AI turns don't blur past the eye. A pause at the top of
        // the effect body cancels cleanly on any re-key (pause, back,
        // undo) without touching the thinking indicator or progress bar.
        if (demoMode) delay(MIN_AI_MOVE_INTERVAL_MS)
        val maxIterations =
            if (pending != null) {
                setup.difficulty.maxCollapseIterations
            } else {
                setup.difficulty.maxMoveIterations
            }
        val maxTimeMs =
            if (pending != null) {
                setup.difficulty.maxCollapseTimeMs
            } else {
                setup.difficulty.maxMoveTimeMs
            }
        viewModel.thinking = true
        val start = TimeSource.Monotonic.markNow()
        // Progress bar tracks wall-clock against the time cap; the AI may
        // exit early when its iteration cap fires, in which case `finally`
        // resets the bar to 0 and we move on.
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
            if (pending != null) {
                val choice = chooseCollapse(state, maxIterations, maxTimeMs)
                coroutineContext.ensureActive()
                if (viewModel.generation == generation) {
                    viewModel.resolveCollapse(choice)
                }
            } else {
                val move = chooseMove(state, maxIterations, maxTimeMs)
                coroutineContext.ensureActive()
                if (viewModel.generation == generation) {
                    viewModel.applyAiMove(move)
                }
            }
        } finally {
            progressJob.cancel()
            thinkingProgress = 0f
            viewModel.thinking = false
        }
    }

    val humanPicker = pending != null && setup.players.kindFor(pending.chooser) == PlayerKind.HUMAN
    val displayedState =
        if (humanPicker && previewChoice != null) {
            Rules.resolve(state, previewChoice!!)
        } else {
            state
        }
    val diff = if (humanPicker && pending != null) differingSquares(pending) else emptyList()
    val highlightedSquares = diff.toSet()
    // Prefer a triggering-move endpoint when it's in the diff -- it's the
    // square the chooser's eye is already on -- otherwise just the first
    // differing square in reading order. Auto-resolve guarantees diff is
    // non-empty whenever humanPicker is true.
    val indicatorSquare: Square? =
        if (humanPicker) {
            val triggering = setOfNotNull(state.lastMove?.a, state.lastMove?.b)
            diff.firstOrNull { it in triggering } ?: diff.firstOrNull()
        } else {
            null
        }
    // Sending-rule highlight: the (b, d) pair the previous move
    // constrains us to. Suppressed when the constraint isn't
    // satisfiable on the current state (the opponent has free play)
    // so the highlight doesn't lie about an unenforced rule.
    val sentBoards: Pair<Int, Int>? =
        state.requiredBoards
            ?.takeIf { state.variant == Variant.ULTIMATE_QUANTUM_TIC_TAC_TOE }
            ?.takeIf { Rules.hasLegalMoveWithin(state, it) }

    Scaffold(
        modifier =
            Modifier
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) {
                        return@onKeyEvent false
                    }
                    // Undo / redo drain the entire AI-vs-AI history in one
                    // click (the loops chain through AI states); mirror the
                    // top-bar behaviour and swallow the keystroke in demo.
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
            // `LinearProgressIndicator` lives below `TopAppBar`, not in
            // its `actions` slot, so the bar's `actions` no longer
            // squeeze the title. Conditional rendering means the bar
            // is only drawn while the AI is thinking; the small layout
            // shift when it appears is itself useful feedback.
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
                    },
                )
                if (viewModel.thinking) {
                    LinearProgressIndicator(
                        progress = { thinkingProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(padding).padding(8.dp),
        ) {
            val landscape = maxWidth > maxHeight
            if (humanPicker && pending != null) {
                val onConfirm: () -> Unit = {
                    previewChoice?.let(viewModel::resolveCollapse)
                }
                if (landscape) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        BoardArea(
                            view = displayedState.toBoardView(),
                            selection = viewModel.selection,
                            onSquareTap = viewModel::onSquareTap,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            illegalEndpoints = viewModel.illegalEndpoints,
                            highlightedSquares = highlightedSquares,
                            indicatorSquare = indicatorSquare,
                            sentBoards = sentBoards,
                        )
                        CollapsePicker(
                            pending = pending,
                            selected = previewChoice,
                            indicator = indicatorSquare,
                            onSelect = { previewChoice = it },
                            onConfirm = onConfirm,
                            // Fixed width avoids a constraint-loop with the
                            // Confirm button's fillMaxWidth that, with a
                            // `widthIn(min)`-only constraint, makes the Card
                            // expand to the full available width and starves
                            // the board's `weight(1f)` slot of zero pixels.
                            modifier =
                                Modifier
                                    .width(280.dp)
                                    .padding(start = 8.dp),
                        )
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        BoardArea(
                            view = displayedState.toBoardView(),
                            selection = viewModel.selection,
                            onSquareTap = viewModel::onSquareTap,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            illegalEndpoints = viewModel.illegalEndpoints,
                            highlightedSquares = highlightedSquares,
                            indicatorSquare = indicatorSquare,
                            sentBoards = sentBoards,
                        )
                        CollapsePicker(
                            pending = pending,
                            selected = previewChoice,
                            indicator = indicatorSquare,
                            onSelect = { previewChoice = it },
                            onConfirm = onConfirm,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                        )
                    }
                }
            } else {
                BoardArea(
                    view = displayedState.toBoardView(),
                    selection = viewModel.selection,
                    onSquareTap = viewModel::onSquareTap,
                    modifier = Modifier.fillMaxSize(),
                    illegalEndpoints = viewModel.illegalEndpoints,
                    sentBoards = sentBoards,
                )
            }
        }

        if (state.isGameOver) {
            GameOverDialog(
                state = state,
                onUndo = viewModel::undo,
                onPlayAgain = { viewModel.reset(Rules.initial(setup.variant)) },
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
                    // The user already chose "Back", so "Leave" is the
                    // confirming action and goes on the right (Material's
                    // primary-button slot); "Stay" cancels and sits on the
                    // left.
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

private fun IllegalReason.displayMessage(): String =
    when (this) {
        IllegalReason.SQUARE_IS_CLASSICAL -> "That square already has a classical mark."
        IllegalReason.DUPLICATE_PAIR -> "Those two squares are already entangled."
        IllegalReason.DUPLICATE_INTER_BOARD_ENTANGLEMENT ->
            "Those two mini-boards are already linked by an entanglement."

        IllegalReason.NOT_YOUR_TURN -> "It is not your turn."
        IllegalReason.PENDING_COLLAPSE_UNRESOLVED -> "Resolve the pending collapse first."
        IllegalReason.BOARD_NOT_IN_VARIANT -> "That mini-board is not in play for this variant."
        IllegalReason.GAME_OVER -> "The game is over."
        IllegalReason.WRONG_SENT_BOARDS ->
            "The previous move sent you to a specific pair of mini-boards."
    }
