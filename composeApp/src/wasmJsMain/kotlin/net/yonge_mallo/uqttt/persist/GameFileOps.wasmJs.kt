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

// Web implementation of the expert file-I/O surface. Save uses a
// synthesized `<a download>` element with a Blob URL -- works
// everywhere and does not require the File System Access API (which
// is Chromium-only). Load uses `<input type="file">` and
// `FileReader` for the same reason. Both flows resolve suspending
// through `kotlin.js.Promise.await()`.
package net.yonge_mallo.uqttt.persist

import kotlinx.coroutines.await
import net.yonge_mallo.uqttt.engine.ClassicalGameState
import net.yonge_mallo.uqttt.engine.GameState
import net.yonge_mallo.uqttt.engine.Variant
import kotlin.js.Promise

private const val MIME_JSON: String = "application/json"
private const val SAVE_EXTENSION: String = "uqttt"

/**
 * The one shared implementation on the web build. Kept as a
 * top-level object so it can be assigned once to `gameFileOps` and
 * survive recomposition; nothing here is per-composable state.
 */
private object WebGameFileOps : GameFileOps {
    override suspend fun saveGame(state: GameState) {
        downloadText(saveFileName(state.variant), MIME_JSON, encodeSavedGame(state.toSavedGame()))
    }

    override suspend fun saveGame(state: ClassicalGameState) {
        downloadText(saveFileName(state.variant), MIME_JSON, encodeSavedGame(state.toSavedGame()))
    }

    override suspend fun openGame(): LoadResult? {
        val text = pickTextFile(".uqttt,.json,application/json") ?: return null
        return decodeSavedGame(text)
    }
}

actual val gameFileOps: GameFileOps? = WebGameFileOps

/**
 * A stable filename per variant so a directory of saves stays
 * browsable. Lowercased kebab-case; the user is free to rename in
 * the OS save dialog, this is only the suggestion.
 */
private fun saveFileName(variant: Variant): String {
    val slug = variant.name.lowercase().replace('_', '-')
    return "$slug.$SAVE_EXTENSION"
}

/**
 * Trigger a browser download by synthesizing an anchor element with
 * a Blob URL and clicking it programmatically. The URL is revoked
 * inside the same JS block so we don't leak memory when the user
 * saves many games in one session.
 */
private fun downloadText(
    name: String,
    mime: String,
    content: String,
) {
    downloadTextJs(name, mime, content)
}

@Suppress("UNUSED_PARAMETER")
private fun downloadTextJs(
    name: String,
    mime: String,
    content: String,
): Unit =
    js(
        """{
    const blob = new Blob([content], { type: mime });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = name;
    a.style.display = 'none';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(function () { URL.revokeObjectURL(url); }, 0);
}""",
    )

/**
 * Open a file picker for a text file whose extension matches
 * `accept`, return the file's contents as a string. Returns null if
 * the user cancels or the read fails. Some browsers surface cancel
 * as a `cancel` event; others simply never fire `change`, so the
 * returned Promise stays pending until GC. That's acceptable for
 * this feature -- the composable that awaits it is scoped to the
 * screen and cancels the coroutine when the screen leaves.
 */
private suspend fun pickTextFile(accept: String): String? {
    val result: JsString? = pickTextFileJs(accept).await()
    return result?.toString()
}

@Suppress("UNUSED_PARAMETER")
private fun pickTextFileJs(accept: String): Promise<JsString?> =
    js(
        """(function () {
    return new Promise(function (resolve) {
        const input = document.createElement('input');
        input.type = 'file';
        input.accept = accept;
        input.style.display = 'none';
        let settled = false;
        const settle = function (value) {
            if (settled) return;
            settled = true;
            try { document.body.removeChild(input); } catch (_) {}
            resolve(value);
        };
        input.addEventListener('change', function () {
            const file = input.files && input.files[0];
            if (!file) {
                settle(null);
                return;
            }
            const reader = new FileReader();
            reader.onload = function () { settle(reader.result); };
            reader.onerror = function () { settle(null); };
            reader.readAsText(file);
        });
        input.addEventListener('cancel', function () { settle(null); });
        document.body.appendChild(input);
        input.click();
    });
})()""",
    )
