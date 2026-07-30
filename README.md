# Kibitz

A native Android chess **training** app. A *kibitzer* is the onlooker who watches your game and
tells you what you did — which is the whole idea: Kibitz judges every move you make and builds
a picture of how you actually play.

Kotlin + Jetpack Compose, with Stockfish 18 as the engine.

## What works today

- **Board** drawn on a single Compose `Canvas` — tap-to-move or drag, legal-move hints,
  check/checkmate/stalemate, castling, en passant, promotion, board flip, animated moves.
- **Rules engine** written from scratch and verified against the standard perft suite.
- **Stockfish 18**, compiled from source with the NDK and driven over UCI.
- **Live evaluation bar** beside the board, with graduations at ±1/2/3/5 pawns.
- **Onboarding + level check** — enter your name, play a short game, get an estimated rating
  band with an explicit confidence caveat.
- **Per-move coaching** — every move priced in centipawns and labelled from *Best move* to
  *Blunder*, with what would have been stronger.
- **Training history** — every judged move stored in Room with the position it was played
  from, so past mistakes can be replayed as puzzles.
- **Play Kibitz at your level** — an opponent ladder from Beginner to full strength, limited
  properly through Stockfish's own `UCI_Elo` rather than by making it blunder on purpose. The
  level nearest your estimated rating is preselected. Either colour, or random.
- **Games end properly** — checkmate, stalemate, the fifty-move rule, insufficient material and
  threefold repetition, with a result stated from your side of the board.
- **Automatic post-game review** — every game you finish is analysed move by move and filed, so
  the mistakes in it become training material rather than vanishing.

- **Practise your own mistakes** — every blunder becomes a puzzle from the exact position it
  happened in, unsolved-hardest first, with attempts tracked so solved ones sink down the queue.

- **Your games** — every recorded game with its result and accuracy, the annotated move list for
  each, and whether accuracy is actually improving rather than just an average.
- **Survives being killed** — an unfinished game against the engine is restored by replaying its
  moves, so closing the app mid-game does not lose it.

The whole loop runs on the phone with no network: play → the game ends → it is reviewed → its
mistakes become drills → play again.

Not done yet: the LLM coaching layer, deliberately deferred.

## How the level bands were calibrated

The rating bands are measured, not guessed. Stockfish limited to a known `UCI_Elo` stands in for
a player of that strength, plays the same level check the app plays against the same Club-level
opponent, and every move is judged at full strength with the depth and cap the app uses. Fitting
rating against the resulting mean capped centipawn loss gives

    rating = 6401 - 1295 * ln(loss)          R² = 0.977 over 1350–2900 Elo

Sample length turned out not to be a free parameter. Discrimination between a 1350 and a 2900
player, as a ratio of the difference to the per-game noise:

| player moves assessed | signal / noise | R²    | monotonic |
|-----------------------|----------------|-------|-----------|
| 12                    | 1.03           | 0.600 | no        |
| 20                    | 1.85           | 0.652 | no        |
| **30**                | **2.89**       | 0.977 | yes       |

At twelve moves the gap between a weak and a strong player *is* the noise — openings are
forgiving and the signal lives in the middlegame. That is why the level check is thirty moves
and why `LevelCalibration.CALIBRATED_SAMPLE_MOVES` and the thresholds must move together.

Two limits stated plainly: the reference is engine play held to a rating rather than human play,
and an engine capped at 1500 drifts mildly where a 1500-rated human misses tactics outright — so
the shape of the curve is better evidence than its absolute position. Below 1350 the curve is
extrapolated, because `UCI_Elo` bottoms out at 1320. Validating against players of known rating
is still worth doing.

## Building

The Android SDK, JDK and NDK are expected in these locations (adjust to your machine):

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME="$HOME/android-sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

Requires NDK `27.3.13750724` and CMake `3.22.1`:

```bash
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "ndk;27.3.13750724" "cmake;3.22.1"
```

Then:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

### Neural networks are fetched, not committed

Stockfish cannot evaluate without its two NNUE networks, and the big one is 104 MB — above
GitHub's 100 MB per-file limit. The `fetchStockfishNetworks` Gradle task downloads them into
`app/src/main/assets` on first build, from the same server Stockfish's own Makefile uses, and
verifies each one: Stockfish names a network after the first 12 hex digits of its SHA-256, so
the filename is the checksum. A corrupted or substituted download fails the build.

To fetch them without building anything else:

```bash
./gradlew :app:fetchStockfishNetworks
```

### Release builds

`assembleRelease` runs R8 with shrinking on. Two things there are load-bearing:

- The JNI bridge is linked **by name** — the C++ symbols encode the fully-qualified Java class
  and method. `proguard-rules.pro` keeps `engine.stockfish.Stockfish` and its native methods, or
  the engine fails to start in release builds only. Verified by checking the shipped dex.
- Signing applies only when `release.keystore` and `keystore.properties` are present, so
  `assembleRelease` works without them and produces an unsigned artifact. No key material is
  generated or committed; both files are gitignored.

Only `arm64-v8a` and `x86_64` are built. `armeabi-v7a` compiles cleanly and the CMake branch for
it is there, but it is not shipped: Stockfish selects its instruction set at compile time with no
runtime dispatch, so a wrong flag is a SIGILL rather than a slow search, and there is no 32-bit
ARM device or emulator here to verify it on. Play's ABI targeting means 32-bit-only devices are
simply not offered the app, which is better than being offered one that crashes.

### Size

The networks dominate: **77 MB packaged** (APK) / **78 MB** as an App Bundle, plus about
**108 MB extracted** at first launch, because Stockfish opens them as ordinary files and cannot
read an Android asset directly. So roughly 185 MB of device storage.

The assets are stored **compressed**. They were originally excluded from compression on the
assumption that neural-network weights are high-entropy; measured, the big net deflates
104 MB -> 70 MB, and leaving it uncompressed cost ~34 MB of download — enough to push the APK
past Play's 100 MB limit — to avoid inflating it once during a copy the app performs anyway.
Both nets are SHA-256 verified against their own filenames as they are extracted.

If a much smaller install is ever wanted, the mechanism is Play Asset Delivery, not a
self-hosted download: Play hosts asset packs at no cost, and `tests.stockfishchess.org` is the
Stockfish project's testing infrastructure, not a CDN to point an app's users at.

## Architecture

```
chess/    rules — immutable Position, move generation, FEN, SAN
engine/   ChessEngine interface, Stockfish (UCI over JNI), Kotlin fallback, eval snapshots
coach/    per-move assessment and level estimation
data/     Room history — games and every judged move, with the position it came from
profile/  the player's name and estimated level
ui/       Compose board, onboarding, level check, result
```

Two engines sit behind one `ChessEngine` interface. Stockfish does the real work; the built-in
Kotlin engine answers during the ~3 s Stockfish needs to load its network, covers devices where
the native library will not load, and is the only engine available to JVM unit tests. Every
assessment records which of the two judged it, because their centipawn scales are not
comparable.

## Testing

```bash
./gradlew :app:testDebugUnitTest
```

Unit tests cover the rules engine (perft node counts against published values), notation,
evaluation-bar mapping, level estimation, and UCI output parsing. Stockfish itself is native
and cannot run in JVM unit tests — that is one of the reasons the fallback engine exists.

## Licence

Kibitz is licensed under the **GNU General Public License v3** — see [LICENSE](LICENSE).

    Copyright (C) 2026 Kibitz authors

    This program is free software: you can redistribute it and/or modify it under the terms
    of the GNU General Public License as published by the Free Software Foundation, either
    version 3 of the License, or (at your option) any later version.

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
    without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
    See the GNU General Public License for more details.

GPLv3 is not a preference here, it is a consequence: this repository vendors
[Stockfish](https://github.com/official-stockfish/Stockfish) (`app/src/main/cpp/stockfish`),
which is GPLv3, and a work combining it must be licensed compatibly. A permissive licence such
as MIT is not available for the project as a whole, because it cannot be applied to Stockfish's
code. Stockfish's own licence text is retained at `app/src/main/cpp/stockfish/COPYING.txt`.

Practical consequence: **a closed-source release of this app is not possible in this
configuration.** Stockfish is GPLv3 and not AGPL, so it has no network-use clause — running the
engine behind a server rather than inside the app would keep GPL obligations off the client.
Everything already talks to the `ChessEngine` interface rather than to Stockfish directly, so
that move stays a contained change if it is ever wanted.

This is a summary, not legal advice.

## Known limitations

- The rating bands are fitted against engine play held to a known Elo, not against humans, and
  are extrapolated below 1350. The UI deliberately shows a range and a confidence caveat rather
  than a number.
- ABIs are `arm64-v8a` and `x86_64` only. No `armeabi-v7a`, so very old 32-bit devices fall back
  to the Kotlin engine.
- Built without `-march=armv8.2-a+dotprod`. It would be faster, but crashes with SIGILL on older
  arm64 cores (Cortex-A53/A57).
- Threefold-repetition draws are not detected; that needs the position history arriving with
  persistence.
