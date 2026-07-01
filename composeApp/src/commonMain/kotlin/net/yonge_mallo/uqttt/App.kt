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

package net.yonge_mallo.uqttt

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import net.yonge_mallo.uqttt.engine.Variant
import net.yonge_mallo.uqttt.ui.GameScreen
import net.yonge_mallo.uqttt.ui.GameSetup
import net.yonge_mallo.uqttt.ui.MenuScreen
import net.yonge_mallo.uqttt.ui.PlayersConfig
import net.yonge_mallo.uqttt.ui.Screen

/**
 * App entry point used by every platform launcher. Platforms that can
 * supply a system-derived `ColorScheme` (Android 12+ via
 * `dynamicLight/DarkColorScheme`) pass one in; everywhere else falls
 * through to the fixed Material 3 palette.
 */
@Composable
fun App(colorScheme: ColorScheme? = null) {
    // Surface the build identifiers on startup so power users can
    // verify which build they're running. Lands in the browser
    // devtools console on Wasm, in logcat on Android, and on stderr
    // on the desktop binary.
    LaunchedEffect(Unit) {
        println("UQTTT build ${BuildInfo.GIT_HASH} (${BuildInfo.COMMIT_DATE})")
    }
    val darkTheme = isSystemInDarkTheme()
    val scheme = colorScheme ?: if (darkTheme) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = scheme) {
        Surface(modifier = Modifier.fillMaxSize()) {
            var screen: Screen by remember { mutableStateOf(Screen.Menu) }
            // Remembered across Menu -> Game -> Menu navigation so the menu
            // re-opens on whichever variant / players combo was last picked.
            var lastSetup: GameSetup by remember {
                mutableStateOf(
                    GameSetup(
                        variant = Variant.ULTIMATE_QUANTUM_TIC_TAC_TOE,
                        players = PlayersConfig.HUMAN_VS_HUMAN,
                    ),
                )
            }
            when (val current = screen) {
                is Screen.Menu ->
                    MenuScreen(
                        initialSetup = lastSetup,
                        onStart = { setup ->
                            lastSetup = setup
                            screen = Screen.Game(setup)
                        },
                    )
                is Screen.Game ->
                    GameScreen(setup = current.setup, onExit = { screen = Screen.Menu })
            }
        }
    }
}
