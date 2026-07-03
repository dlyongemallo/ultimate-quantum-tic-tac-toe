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

import androidx.compose.runtime.Composable

/**
 * Intercepts the platform-level "back" gesture while an in-progress
 * game is on screen and routes it to `onBackRequested`, which the game
 * screens wire to the same "Leave game?" confirmation dialog as the
 * in-app Back button. Prevents an accidental gesture or button from
 * silently discarding a game.
 *
 * The intercept is scoped to the composable's lifetime -- attach on
 * mount, detach on dispose -- so the menu screen never claims the
 * back gesture.
 *
 * Wired on Android (system Back via `BackHandler`) and Wasm (browser
 * Back via `history` / `popstate`). Desktop has no back gesture; iOS's
 * edge-swipe lives on `UINavigationController`, which the current
 * `ComposeUIViewController` scaffold doesn't nest inside, so both are
 * no-ops.
 */
@Composable
expect fun SystemBackGuard(onBackRequested: () -> Unit)
