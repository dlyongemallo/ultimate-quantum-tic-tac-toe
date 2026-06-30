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

package net.yonge_mallo.uqttt.ai

// Wasm in the browser shares the single JS event-loop thread, so
// `Dispatchers.Default` here is effectively cooperative single-thread
// execution. WebWorkers would need a separate worker per search with
// state serialisation, which is out of scope; run sequentially.
internal actual val defaultMctsWorkers: Int = 1
