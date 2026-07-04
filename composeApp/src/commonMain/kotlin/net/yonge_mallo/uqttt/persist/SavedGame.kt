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

// On-disk save format for a paused game. The persisted file is a
// small JSON document with a stable schema so future engine changes
// don't silently break existing saves.
//
// Layout:
//
//   {
//     "type":          "quantum" | "classical",
//     "schemaVersion": 1,
//     "state":         { ...engine `GameState` or `ClassicalGameState`... }
//   }
//
// The `type` discriminator is emitted by `kotlinx.serialization` for
// the sealed `SavedGame` union; `schemaVersion` lets `decodeSavedGame`
// surface a specific error when a save is from a future or
// incompatible build rather than crashing halfway through parsing.
package net.yonge_mallo.uqttt.persist

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import net.yonge_mallo.uqttt.engine.ClassicalGameState
import net.yonge_mallo.uqttt.engine.GameState

/**
 * A persisted game, one variant per subtype. Bumped
 * `SCHEMA_VERSION` whenever a state shape change would render older
 * saves unreadable (a new required field on `GameState`,
 * a semantic redefinition of `wonBoards`, and so on). Renames handled
 * by `@SerialName` don't need a bump.
 */
@Serializable
sealed interface SavedGame {
    val schemaVersion: Int

    @Serializable
    @SerialName("quantum")
    data class Quantum(
        val state: GameState,
        override val schemaVersion: Int = SCHEMA_VERSION,
    ) : SavedGame

    @Serializable
    @SerialName("classical")
    data class Classical(
        val state: ClassicalGameState,
        override val schemaVersion: Int = SCHEMA_VERSION,
    ) : SavedGame

    companion object {
        /**
         * The version this build knows how to read. Any save with a
         * different `schemaVersion` is rejected by `decodeSavedGame`
         * instead of being reinterpreted.
         */
        const val SCHEMA_VERSION: Int = 1
    }
}

/**
 * Package a live `GameState` or `ClassicalGameState` into its
 * `SavedGame` envelope. Called at the file-save boundary; the
 * envelope is what actually gets serialized.
 */
fun GameState.toSavedGame(): SavedGame = SavedGame.Quantum(this)

fun ClassicalGameState.toSavedGame(): SavedGame = SavedGame.Classical(this)

/**
 * The stable JSON codec used everywhere in this package. Pretty
 * printing is on so hand-inspection and diff review of a save
 * remains cheap; `ignoreUnknownKeys = false` (default) so trailing
 * garbage or a mis-typed field surfaces at parse time instead of
 * silently dropping data. `allowStructuredMapKeys = true` opts in
 * to the alternating-array encoding for `Map<K, V>` where `K` isn't
 * a primitive -- the state stores `Map<Square, Player>` and
 * `Map<QuantumMark, Square>`, both of which JSON's string-keyed
 * object form can't represent.
 */
@OptIn(ExperimentalSerializationApi::class)
private val json =
    Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        allowStructuredMapKeys = true
    }

/**
 * Serialize a `SavedGame` to its persisted JSON form. The output
 * is stable across runs on the same build: the same state always
 * produces the same bytes, which makes diffing and golden tests
 * meaningful.
 */
fun encodeSavedGame(saved: SavedGame): String = json.encodeToString(SavedGame.serializer(), saved)

/**
 * The outcome of trying to read a saved game. A caller (the file-
 * picker composable) uses this to decide whether to start a game
 * from the loaded state or surface an error to the user.
 */
sealed interface LoadResult {
    data class Ok(val saved: SavedGame) : LoadResult

    data class Failed(val reason: String) : LoadResult
}

/**
 * Read a `SavedGame` from its persisted JSON form.
 *
 * Failure modes and how they surface:
 * - Not valid JSON, or the envelope shape doesn't match: caught by
 *   `SerializationException` from `Json.decodeFromString`.
 * - `schemaVersion` doesn't match this build's `SCHEMA_VERSION`:
 *   caller sees `Failed(...)`, never a partial state.
 * - State field values violate an engine invariant (e.g., an
 *   `Entanglement` whose endpoints don't share a move number):
 *   the engine data classes' `init` blocks throw
 *   `IllegalArgumentException`. Caught and reported.
 */
fun decodeSavedGame(source: String): LoadResult {
    val saved =
        try {
            json.decodeFromString(SavedGame.serializer(), source)
        } catch (e: SerializationException) {
            return LoadResult.Failed("The file isn't a valid saved game (${e.message ?: "malformed JSON"}).")
        } catch (e: IllegalArgumentException) {
            return LoadResult.Failed(
                "The saved game has an invalid state (${e.message ?: "engine invariant violated"}).",
            )
        }
    if (saved.schemaVersion != SavedGame.SCHEMA_VERSION) {
        val readable = SavedGame.SCHEMA_VERSION
        return LoadResult.Failed(
            "This save is from a different build (schema ${saved.schemaVersion}, this build reads $readable).",
        )
    }
    return LoadResult.Ok(saved)
}
