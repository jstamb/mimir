# Mimir

An open-source (GPLv3), dual-screen-first emulation frontend for Android gaming
handhelds — built for the AYN Thor.

**Status: M2b — emulator management.** Per-system default emulators (front and
center in Settings → Emulators), per-game overrides via long-press, and
installed-aware resolution over a registry of real emulators — any emulator the
registry doesn't know can still be added (custom player definitions land next).
Boxart, persistent library, fast scanning, and scan diagnostics already in.

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
| `:core:scraper` | Pure JVM — libretro-thumbnails name rules + art matching |
| `:core:data` | Android library — Room persistence, diff-sync repository |
| `:tools:fake-emulator` | Test fixture APK that displays any VIEW intent it receives |

Platform and player registries are JSON (`core/*/src/main/resources/`) — PRs to
add systems and emulators are the easiest way to contribute.
