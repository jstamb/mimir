# Mimir

An open-source (GPLv3), dual-screen-first emulation frontend for Android gaming
handhelds — built for the AYN Thor.

**Status: M2a — persistent library + fast scanning.** Bulk SAF scanning (one
query per directory), Room-backed library with instant cold-start, incremental
rescans (no duplicates, no losses), a full scan-report screen for skipped
files, and graceful error states for lost folder access. Next (M2b): emulator
auto-detection, per-platform/per-game emulator choice, and custom player
definitions.

## Why another launcher?

- **Open emulator model** — any emulator, any fork, any custom intent. No
  curated whitelist dead-ends.
- **Forgiving library scanning** — folder-name aliases (`GameCube` = `gc` =
  `ngc`, any casing) and a diagnostic report for every skipped file.
- **Dual-screen-first** — designed around the Thor's two displays, not adapted
  to them.
- **GPLv3** — this project can outlive its maintainer.

## Build

Android SDK 36 required. The Gradle daemon JVM (21) auto-provisions via the
committed toolchain config — any JDK able to launch Gradle works.

    ./gradlew :app:assembleDebug        # the launcher
    ./gradlew test                      # unit tests (pure-JVM core modules)
    ./gradlew :tools:fake-emulator:assembleDebug   # intent-catching test fixture

## Module map

| Module | What it is |
|---|---|
| `:app` | Android shell — Compose UI, SAF scanning, intent dispatch |
| `:core:scanner` | Pure JVM — platform registry + file→platform matcher |
| `:core:launcher` | Pure JVM — player (emulator) registry + intent templates |
| `:core:data` | Android library — Room persistence, diff-sync repository |
| `:tools:fake-emulator` | Test fixture APK that displays any VIEW intent it receives |

Platform and player registries are JSON (`core/*/src/main/resources/`) — PRs to
add systems and emulators are the easiest way to contribute.
