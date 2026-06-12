# M5a-3a E2E Verification — Shell Part 1 (HeroPane + Browse)

- **Date:** 2026-06-11 · **Device:** Pixel_10_Pro emulator (emulator-5554, 1280×2856, API 36)
- **Build:** app-debug from branch `m5a3a-shell-browse` (post-263004b + review fixes), `adb install -r` over the resident M5a-2 build — library DB (5 games / 3 systems, SGDB heroes+logos, play states) carried over intact.
- **Library:** GameCube ·1 (Wind Waker), Nintendo 64 ·3 (GoldenEye, Mario Kart 64, Star Fox 64), Nintendo DS ·1 (Mario Kart DS).

### 1. Hero pane shows the last-played game
Cold launch (no selection) → hero = **Wind Waker**: full-bleed SGDB *hero* art (Toon Link close-up — not the boxart), SGDB **Zelda logo PNG** (not styled text), glass pills **GameCube · ▶ Mimir Fake Emulator · Last played 6/11/26**. Emulator name resolved live in the pill. Capture: `img/m5a3a-hero.png`. **PASS**

### 2. Tap a different game → ring + hero/ambient switch
Switched to the Nintendo 64 tab (hero correctly *unchanged* — tabs don't move focus), tapped **GoldenEye** once: did **not** launch; hero swapped to GoldenEye's SGDB hero + logo, pills → Nintendo 64, and the card grew the 2dp `theme.primary` selection ring (verified by pixel crop — ring on GoldenEye, none on neighbors). Ambient followed: the ring/wash recolored to the grey palette extracted from GoldenEye's hero art (previously Wind Waker teal). Later, selecting **Mario Kart 64** washed the entire UI (tabs, search outline, toggles, list highlight) in MK64 gold — ambient demonstrably follows the *selected* game. Captures: `img/m5a3a-hero.png` (before) / `img/m5a3a-selected.png` (after, both read back). **PASS**

### 3. Tap the same game again → launch
Second tap on the selected GoldenEye card → **Mimir Fake Emulator** (`dev.mimir.fakeemulator/.CatchActivity` confirmed as `topResumedActivity`) received the `...Roms%2Fn64%2FGoldenEye.z64` URI. **PASS**

### 4. Long-press → Play-with sheet
Long-press on GoldenEye → ModalBottomSheet "Play "GoldenEye" with…" listing Mupen64Plus FZ (not installed) + Mimir Fake Emulator. Selection/hero state survived the sheet. **PASS**

### 5. System tabs (shown at >1 system) switch platforms
Three tabs with counts (GameCube ·1 / Nintendo 64 ·3 / Nintendo DS ·1) — rendered because systems > 1. Tapping Nintendo 64 swapped the grid to its 3 games and the search placeholder to "Search 3 games". **PASS**

### 6. Search filters
Typed `star` → grid reduced to **Star Fox 64** only; clearing restored all 3. (BrowseLogic.filter: case/punctuation-insensitive, unit-tested.) **PASS**

### 7. Grid/list toggle
"≡ List" → rows with leading thumbnail art (folder-green GoldenEye, ES-DE-magenta MK64, SGDB Star Fox); button flips to "⊞ Grid". First tap on a list row **selects** (no launch) and the row highlights with `theme.glow` via `ListItemDefaults.colors(containerColor=…)`. **PASS** — see fix note below.

### 8. Alphabet rail absent under threshold
5 games total / max 3 per system — rail (RAIL_THRESHOLD = 24) absent in every capture, as designed. Rail drag math reviewed: fraction clamped to 0.999 → max index 26 = "Z", no off-by-one. **PASS (by absence)**

### 9. Overflow menu — all 5 actions
⚙ and ⋮ live top-right over the hero. Menu contains exactly: **Rescan · Fetch artwork · Import ES-DE · Scan report (2 skipped) · Change ROM folder**. Ran **Rescan** from it: scan completed, 5 games / skip-count / selection / list-mode all intact. **PASS**

### 10. Settings + Scan report reachable
⚙ → Emulators settings rendered in the 0.6 content pane *under the persistent hero*; Back returns to browse. Overflow → "Scan report (2 skipped)" → report lists `n64/GoldenEye.png` (extension not registered) + `notes.txt`; Back returns. **PASS**

### 11. Logcat clean
Full-session `logcat -d` from pre-launch `-c` (6,081 lines): `FATAL EXCEPTION|AndroidRuntime.*dev.mimir` → 0 · `coil.*(error|fail)` → 0 · app exceptions → 0. **PASS**

## Review fixes folded into this commit
1. **List-mode selection highlight was invisible** (found in E2E): `Modifier.background(...)` sat *behind* ListItem's own opaque container, so the selected row looked identical to the rest. Moved the highlight into `ListItemDefaults.colors(containerColor = theme.glow / Transparent)`. Re-verified on device — MK64 row visibly glow-tinted when selected.
2. **`supportingContent` oddity** (review item): `art?.let { "" } ?: "no art"` rendered an empty supporting line under every row with art. Now `supportingContent` is non-null only when art is missing.
3. **`mutableStateOf(1f)` → `mutableFloatStateOf(1f)`** in AlphaRail (avoids autoboxing; lint-preferred).

### Evidence files
- `img/m5a3a-hero.png` — cold launch: Wind Waker hero art + Zelda logo + pills (last-played focus), 3 system tabs, no rail
- `img/m5a3a-selected.png` — GoldenEye selected: hero/logo/pills switched, selection ring on card, palette re-extracted

### Known nits (non-blocking, noted for M5a-3b)
- List rows take tap only — long-press Play-with is grid-only (plan-as-written).
- `resolvedEmulatorName` queries PackageManager per hero change; fine at this scale, snapshot-cache if it ever shows in traces.
- Games come from Room `ORDER BY title` (BINARY collation) — an all-lowercase title would sort after "Z" and mis-bucket in the rail; pre-existing, consider `COLLATE NOCASE` later.
