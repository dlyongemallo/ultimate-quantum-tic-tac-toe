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

// The `expect val gameFileOps: GameFileOps?` gate for the save /
// restore and TikZ export actions. Non-null only on the web build --
// mobile and desktop users don't get file I/O for these features, and
// the UI is expected to hide the entry points when the value is null.
package net.yonge_mallo.uqttt.persist

import net.yonge_mallo.uqttt.engine.ClassicalGameState
import net.yonge_mallo.uqttt.engine.GameState

/**
 * The file-I/O surface for expert features. All calls are
 * suspending because the underlying browser APIs
 * (`showSaveFilePicker`, `showOpenFilePicker`) are user-driven and
 * asynchronous. A load returns `null` when the user cancels the
 * picker rather than throwing, so callers don't need to distinguish
 * "cancel" from "error" via exception handling.
 */
interface GameFileOps {
    /**
     * Prompt the user for a location and write the quantum save
     * envelope there as JSON. Suggested filename encodes the
     * variant so a directory of saves stays browsable.
     */
    suspend fun saveGame(state: GameState)

    /** Classical sibling of `saveGame(state: GameState)`. */
    suspend fun saveGame(state: ClassicalGameState)

    /**
     * Prompt the user for a file, decode it into a `SavedGame`, and
     * hand it back. Returns `null` when the user cancels the
     * picker. Returns a `LoadResult.Failed` inside a wrapper when
     * the file is unreadable or its schema doesn't match.
     */
    suspend fun openGame(): LoadResult?

    /**
     * Prompt the user for a location and write the TikZ picture
     * source there. Suggested filename ends `.tex`.
     */
    suspend fun exportTikz(state: GameState)

    /** Classical sibling of `exportTikz(state: GameState)`. */
    suspend fun exportTikz(state: ClassicalGameState)
}

/**
 * Platform's file-ops surface, or `null` when the platform doesn't
 * offer one. Read by the UI layer: a null value hides the save /
 * load / export entry points entirely. Kept as a top-level `expect
 * val` rather than an `expect fun` so it stays a compile-time
 * constant, cheap to check inside a `@Composable`.
 */
expect val gameFileOps: GameFileOps?
