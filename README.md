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

Not done yet: no engine opponent outside the level check, no stored game history, no LLM
coaching layer.

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

### Size

The networks make the app large: roughly **120 MB packaged**, plus about **108 MB extracted**
at first launch, because Stockfish opens them as ordinary files and cannot read Android assets
directly. That is ~230 MB of device storage. Delivering the big network on demand instead of
bundling it is the obvious pre-release optimisation; the engine code would not change.

## Architecture

```
chess/    rules — immutable Position, move generation, FEN, SAN
engine/   ChessEngine interface, Stockfish (UCI over JNI), Kotlin fallback, eval snapshots
coach/    per-move assessment and level estimation
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

- The rating bands in `LevelCalibration` have **not** been validated against players of known
  rating. They follow the published relationship between average centipawn loss and rating, but
  are not fitted. The UI deliberately shows a range and a confidence caveat rather than a
  number.
- ABIs are `arm64-v8a` and `x86_64` only. No `armeabi-v7a`, so very old 32-bit devices fall back
  to the Kotlin engine.
- Built without `-march=armv8.2-a+dotprod`. It would be faster, but crashes with SIGILL on older
  arm64 cores (Cortex-A53/A57).
- Threefold-repetition draws are not detected; that needs the position history arriving with
  persistence.
