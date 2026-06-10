# Mimir

An open-source (GPLv3), dual-screen-first emulation frontend for Android gaming
handhelds — built for the AYN Thor.

**Status: M1 — end-to-end launch loop.** Scan a ROM folder, browse the grid,
launch games in your emulators. Dual-screen shell, scraping, and theming land in
M2–M5 (see `docs/superpowers/specs/`).

## Why another launcher?

- **Open emulator model** — any emulator, any fork, any custom intent. No
  curated whitelist dead-ends.
- **Forgiving library scanning** — folder-name aliases (`GameCube` = `gc` =
  `ngc`, any casing) and a diagnostic report for every skipped file.
- **Dual-screen-first** — designed around the Thor's two displays, not adapted
  to them.
- **GPLv3** — this project can outlive its maintainer.

## Build

JDK 17 + Android SDK 36 required.

    ./gradlew :app:assembleDebug        # the launcher
    ./gradlew test                      # unit tests (pure-JVM core modules)
    ./gradlew :tools:fake-emulator:assembleDebug   # intent-catching test fixture

## Module map

| Module | What it is |
|---|---|
| `:app` | Android shell — Compose UI, SAF scanning, intent dispatch |
| `:core:scanner` | Pure JVM — platform registry + file→platform matcher |
| `:core:launcher` | Pure JVM — player (emulator) registry + intent templates |
| `:tools:fake-emulator` | Test fixture APK that displays any VIEW intent it receives |

Platform and player registries are JSON (`core/*/src/main/resources/`) — PRs to
add systems and emulators are the easiest way to contribute.
