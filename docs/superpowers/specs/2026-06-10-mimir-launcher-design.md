# Mimir — Open-Source Dual-Screen Launcher for the AYN Thor

**Date:** 2026-06-10
**Status:** Design approved (Jordan, 2026-06-10)
**Working title:** Mimir (Odin's wise counsel — name vetted clean against the launcher/emulation space 2026-06-10)
**License:** GPLv3
**Stack:** Kotlin + Jetpack Compose, Android multi-display (Presentation API)

---

## 1. Background & research findings

The original plan was to fork Cocoon. Research (2026-06-10) killed that premise and reshaped the project.

### Cocoon cannot be forked

Cocoon ("Cocoon Shell," formerly CocoonFE) is closed source. Its GitHub repo (github.com/inssekt/CocoonFE) contains only release APKs and platform-definition JSONs — no app code, no license (GitHub API: `license: null`). The solo dev ("inssekt"/Moth) stated: *"I never had intentions to open-source it, simply because it's my baby"*; source releases only on project abandonment (cocoon-shell.com/news/post-2-0/). APK inspection confirms it is native Kotlin + Jetpack Compose. Press claims that it is "open source" are incorrect.

Cocoon was built for the dev's own AYN Thor, is free (Ko-Fi-funded), shipped 12 releases in 4 months (beta-1.0 Dec 2025 → beta-2.2 Mar 2026), and is the community-favorite Thor launcher. It is the incumbent to compete with, not a base to build on.

### Nothing modern in this space is forkable

- **ES-DE:** desktop core is MIT, but the Android port's platform layer is copyrighted and closed (FAQ-ANDROID.md); the Android app is paid. Not forkable for Android.
- **Daijisho:** closed source (repo is assets only); dev on indefinite break; maintenance mode.
- **Beacon, iiSU, Console Launcher, LaunchBox, Argon, RESET Collection:** all closed.
- **Pegasus:** the only mature open frontend (GPLv3) — but C++/Qt/QML, no dual-screen support, pending Qt6 migration, and the worst per-system emulator UX on Android (hand-edited `am start` strings).
- **Titanius Launcher:** MIT, Flutter, abandoned Jan 2024 — useful as a small readable reference for intent/SAF launching.
- **Lemuroid:** GPLv3, Kotlin, very active — an all-in-one emulator, not a frontend, but the best open Kotlin reference for controller input, library scanning, and storage plumbing. GPLv3 code is copyable since Mimir is GPLv3.

**Conclusion:** greenfield Kotlin + Compose, harvesting designs (and where licenses allow, code) rather than forking. No maintained open-source project occupies this slot as of June 2026.

### Ranked Cocoon complaints (from ~1,200 mined YouTube comments, changelogs, dev roadmap)

1. **Library scanning failures** (loudest): exact-lowercase folder names required; large SD cards scan to zero; ES-DE-style folder names choke it; incremental rescans miss new ROMs or duplicate the library.
2. **Emulator selection**: per-platform/per-game pickers exist but were buried (hidden settings entry until 2.01) and — the persistent core gripe — only emulators in Cocoon's curated whitelist appear. Forks/renamed emulators (Azahar Plus) and specific RetroArch cores (FBNeo) are unselectable; no custom-intent escape hatch as of beta-2.2.
3. **Settings discoverability** (largely fixed in 2.01).
4. **Scraping**: slow, requires user ScreenScraper/SteamGridDB API keys, mismatch-prone, and **no video snaps** — the #1 reason power users stay on ES-DE.
5. **Closed source**: loud, principled minority; abandonment fear (top comment on official trailer, 67 upvotes).
6. **Organization limits** with large libraries (partly fixed in 2.1).
7. Process jank — exit left emulators running (fixed 2.1).
8. **No button remapping** (Xbox/PS layouts) — unfixed as of 2.2.
9. Single-screen devices feel second-class.

### What users love about Cocoon (preserve the bar)

3DS-style aesthetic + sound/haptic design; best-in-class dual-screen integration (Now Playing, Flutterkey); free with no Play Store; "customizable but simple"; responsive devs.

### AYN Thor platform facts

- Top: 6.0" AMOLED 1920×1080 touch, 60/120 Hz. Bottom: 3.92" AMOLED 1240×1080 touch, **60 Hz only**, near-square (~1.148:1). Android 13, near-stock + AYN button/control panel.
- Bottom screen is a **standard Android secondary display** (Presentation API path); top screen is primary (opposite of AYANEO Pocket DS).
- Dual-screen emulators are mostly SapphireRhodonite's forks: melonDS dual-screen fork, AzaharDS (3DS), CemuDS (Wii U); DraStic needs a community build for bottom-screen touch.
- Quirks to design around: 120/60 Hz mismatch causes tearing hacks (don't assume 120 on bottom); gamepad input follows last-touched screen; measurable perf penalty with both screens on; dual-screen frontends today are Cocoon, ES-DE 3.4 (+companion app), Console Launcher 2.0 betas, iiSU alpha.

---

## 2. Product thesis

A GPLv3, Kotlin + Jetpack Compose frontend with an **original modern-console-OS identity** — Switch/PS5-grade fluidity, motion design, and haptics rather than retro skeuomorphism — built **dual-screen-first** for the AYN Thor.

It wins by inverting Cocoon's four structural weaknesses:

| Cocoon weakness | Mimir answer |
|---|---|
| Closed source, abandonment fear (#5) | GPLv3, public repo, community PRs |
| Whitelisted emulators, buried settings (#2) | Open Player model: auto-detect + any custom intent |
| Brittle scanning (#1) | Alias-dictionary scanner + scan diagnostics |
| No video snaps, API-key hazing (#4) | Keyless tier + optional videos + ES-DE media import |

Distribution: GitHub releases + Obtainium. Funding: Ko-Fi/Patreon. Same zero-cost model the community already trusts.

**Non-goals for v1:** single-screen-device optimization (Thor-first; graceful fallback only), Windows/Linux ports, layout-swapping themes (v2), built-in emulation cores (we launch other apps; we are not an emulator).

---

## 3. Architecture (modular Gradle project)

### `:core:data`
Room database: `Game`, `Platform`, `Player` (emulator config), `Media`, `Collection`, play sessions. **Platform definitions are data-driven JSON shipped in-repo** (id, display name, folder aliases, file extensions, default player candidates, theme hints) so the community can PR new systems — the proven pattern from Cocoon's `platforms/` and ES-DE's `es_systems.xml`.

### `:core:launcher` — the emulator engine (the moat, part 1)
Combines the two best designs in the field:

- **Daijisho's two-tier Player/Platform model:** a *Player* is an intent template (package, activity/action, extras, argument template incl. RetroArch core params); a *Platform* binds ROM folders to an ordered list of Players with one default.
- **ES-DE's intent mechanics:** SAF URIs, FileProvider temporary grants, variable substitution (`%ROM%`, `%ROMSAF%`-equivalents).

Resolution order: per-game override → per-platform default → wizard prompt.

**No whitelist dead-ends, ever:**
- Auto-detect installed emulators via `PackageManager` matched against a community-maintained JSON **player registry** (data, not code — an emulator rename like Azahar Plus is a one-line PR).
- Manual escape hatch: user can register ANY app with a custom intent/argument template from the UI.
- Emulator management is a top-level settings section and a first-run wizard step, not buried.

Launch failures resolve to guided fixes ("melonDS not installed — Install / Pick another / Edit intent"), never silent bounces.

### `:core:scanner` — library scanning (the moat, part 2)
- SAF-tree scanner with per-platform **alias dictionaries**: `GameCube` = `gc` = `ngc` = `gamecube`, any casing.
- Native compatibility with **ES-DE and Daijisho folder conventions** — switchers' existing libraries just work.
- Incremental rescan via tree diffing: new files appear, nothing duplicates.
- **Scan report screen**: every skipped file listed with the reason ("extension `.rvz` not registered for folder `wii-stuff`; nearest platform: Wii") — the diagnostic Cocoon users beg for.
- Multi-location libraries (internal + SD) from day one.

### `:core:scraper`
Tiered, zero-setup-first:
1. **Keyless tier:** libretro-thumbnails (no account, instant) for boxart — first-run works with zero API hazing.
2. **ScreenScraper login (optional):** unlocks **video snaps** and richer metadata. (Constraint: ScreenScraper ToS requires user accounts for video — same constraint ES-DE has. Stated honestly in UI.)
3. **SteamGridDB (optional):** hero/banner art.
4. **ES-DE media importer:** point at an existing `ES-DE/downloaded_media` tree and inherit everything, including videos.

Scrape jobs are resumable, throttled, and crash-isolated (a bad match never kills the run).

### `:feature:shell` — top-screen UI
Compose. Library views (grid + rows + list), game detail, collections/smart folders, search, settings, first-run wizard (storage → scan → emulator detection → theme). Controller-first navigation with full touch parity; ABXY remap presets (Nintendo/Xbox/PS) — fixing Cocoon complaint #8.

### `:feature:deck` — bottom-screen UI
Rendered via **Presentation API** on the secondary display, hosting Compose:
- **Browsing:** context panel — art, metadata, playtime, quick actions for the highlighted game.
- **In-game:** Now Playing panel — session timer, art, return-to-launcher dock.
- **Per-game launch-target choice** (top/bottom) for emulators that want the bottom screen.
- Battery-saver mode blanks the deck.
- Single-screen fallback: app runs fine with no secondary display (deck features hidden) — not optimized, just never broken.

### `:core:theme`
v1 themes are JSON **design-token packs**: palettes, typography scale, shapes, per-screen wallpapers, sound sets, haptic profiles, grid density. Every Compose surface reads tokens through a theme provider **from day one** — the format exists immediately for creators, and we never have to retrofit theming into hardcoded styles. One excellent default theme ships. Layout-swapping themes (Pegasus-class) are explicitly v2.

---

## 4. Dual-screen behavior rules

- Top = primary activity, bottom = Presentation (matches Thor's display topology).
- Deck animations budget for **60 Hz**; shell may use 120 Hz.
- The shell never steals input focus from the top screen (Thor routes gamepad input to the last-touched display).
- Both-screens-on costs performance and battery: deck dims/blanks on idle and during heavy emulation if the user opts in.

---

## 5. Error handling & testing

- Scanner, alias matching, and intent-template substitution live in pure-logic modules with JUnit coverage.
- Compose screenshot tests for shell + deck surfaces.
- Launch path integration-tested against a fake emulator app (test fixture that records received intents).
- Pre-hardware development on the Android emulator with a **simulated secondary display** (natively supported) — the weeks before the Thor arrives are foundation weeks.
- On-device validation pass when the Thor lands: input focus quirk, refresh behavior, real emulator handoffs (melonDS fork, AzaharDS, RetroArch).

---

## 6. Staged delivery inside v1

1. **M1 — Launch loop:** scan one folder, show a grid, launch a game via intent. Ugly but real, end-to-end.
2. **M2 — The moat:** full scanner (aliases, diagnostics, incremental) + emulator management (registry, auto-detect, custom players, wizard).
3. **M3 — Dual-screen shell:** deck Presentation, Now Playing, motion/haptic polish, theme-token plumbing under everything.
4. **M4 — Scraping:** keyless tier, ScreenScraper videos, SteamGridDB, ES-DE media import.
5. **M5 — Theme packs + public beta:** theme JSON documented, GitHub release, Obtainium manifest.

---

## 7. Risks

- **The incumbent is good.** Cocoon is free, beloved, Thor-native, and fast (12 releases / 4 months). Its audio/haptic/mascot polish took a small team months. Mimir's wedge is structural — open source and an open emulator model — things Cocoon's architecture and the dev's stated stance prevent them from matching.
- **Scope.** Four differentiators in v1 is a multi-month solo build; the M1–M5 staging is the control. Each milestone is independently demoable.
- **Hardware gap.** Thor arrives in weeks; emulator-simulated dual display de-risks but doesn't eliminate on-device surprises (input focus, refresh, emulator handoff edge cases).
- **GPLv3 choice** forecloses a paid closed build later. Accepted deliberately: open source IS the differentiator.

---

## 8. Key sources

- github.com/inssekt/CocoonFE (+releases) · cocoon-shell.com (/news/post-2-0/, /wiki/) · feedback.cocoon-shell.com/roadmap
- retrogamecorps.com/2025/10/27/dual-screen-android-handheld-guide/ · retrohandhelds.gg Thor setup guide & Cocoon coverage
- gitlab.com/es-de/emulationstation-de (FAQ-ANDROID.md, ANDROID.md) · github.com/TapiocaFox/Daijishou · github.com/mmatyas/pegasus-frontend · github.com/dsolonenko/titanius-launcher · github.com/Swordfish90/Lemuroid
- github.com/SapphireRhodonite (dual-screen emulator forks) · github.com/azahar-emu/azahar/pull/1341 · github.com/theswest/DualCPY
- ayntec.com/products/ayn-thor · droix.net Thor reviews · YouTube comment corpora (RGC sTs4sHhPwEY, official LODA_MARhUo, RetroPup, Graves, EZ167, Sizzyl, ET Land)
