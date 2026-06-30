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

/**
 * How many MCTS workers `chooseAction` runs in parallel for root
 * parallelisation. Set per target: multi-core JVM hosts (desktop,
 * Android) get one worker per logical CPU minus one (leave a core
 * for the UI / OS); single-threaded targets (Wasm, iOS in its
 * current scaffold) get 1.
 *
 * Root parallelisation runs N independent MCTS trees with distinct
 * RNG seeds and sums their root-child visit counts at the end. The
 * per-call iteration budget set by the caller is the *total* across
 * all workers; `chooseAction` divides it evenly so a difficulty label
 * corresponds to the same total exploration on every platform.
 * Parallelism therefore buys faster wall-clock at the same strength
 * rather than more strength at the same wall-clock; on a single core
 * (Wasm, iOS) the strength is identical to any other target at the
 * same label, capped only by the per-call time budget on slow hosts.
 */
internal expect val defaultMctsWorkers: Int
