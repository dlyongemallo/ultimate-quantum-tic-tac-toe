# Ultimate Quantum Tic-Tac-Toe

A cross-platform implementation of three related games.

**Quantum Tic-Tac-Toe**[^goff2006][^orlin2022] is a version of Tic-Tac-Toe where each
move entangles two squares on the board, with a collapse when a loop is
formed.

**Quantum Tic-Tac-Toe Squared** extends the game to a 3-by-3 grid of 3-by-3
mini-boards, allowing entangling moves across mini-boards (with at most
one entanglement per pair of distinct mini-boards). Three won mini-boards in
a line wins the game.

**Ultimate Quantum Tic-Tac-Toe** adds the "sending" rule from the (classical)
Ultimate Tic-Tac-Toe[^orlin2022] game to the above: the cell positions of the pair placed
by one player determines the mini-board(s) that the opponent must play on next.


## How to run

- Desktop: `./gradlew :composeApp:run`
- Web:     `./gradlew :composeApp:wasmJsBrowserDevelopmentRun`
- Android: `./gradlew :composeApp:assembleDebug` (APK lands in `composeApp/build/outputs/apk/debug/`)


[^goff2006]: Allan Goff, "Quantum tic-tac-toe: A teaching metaphor for
    superposition in quantum mechanics", *American Journal of Physics*,
    vol. 74, no. 11, pp. 962-973, 2006. ISSN 0002-9505.
    [doi:10.1119/1.2213635](https://doi.org/10.1119/1.2213635).
[^orlin2022]: Ben Orlin, "Math Games with Bad Drawings", Black Dog & Leventhal,
    2022. ISBN 978-0-7624-9986-1.
