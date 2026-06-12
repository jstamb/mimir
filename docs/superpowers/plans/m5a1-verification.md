# M5a-1 Theme Foundation — End-to-End Verification

- **Date:** 2026-06-11
- **Device:** AVD `Pixel_10_Pro`, already running headless as `emulator-5554` (verified via `adb -s emulator-5554 emu avd name`); left running
- **Builds:**
  - v0.6.0 baseline: `:app:assembleDebug` from `master` @ `083d432` (built in a throwaway worktree), installed with `adb install -r` over the device's existing install to guarantee a true `0.6.0-m4a` / DB v4 starting state (confirmed via `dumpsys package` + pulled DB `PRAGMA user_version` = 4)
  - M5a-1: `:app:assembleDebug` from `m5a1-theme-foundation` @ `661cf0d`, installed with `adb install -r` **over** the v0.6.0 install
- **Starting state (under v0.6.0):** 4 games (Wind Waker / GoldenEye / Mario Kart 64 / Mario Kart DS), 4 two-column `media` rows (MK64 holding the magenta ES-DE `content://` cover from M4a, others libretro URLs), `platform_prefs` = `nds|melonds`, `gc|custom-com.android.chrome`
- **Driving method:** headless — `uiautomator dump` for bounds, `input tap`, `screencap` read back, DB pulled via `run-as ... cat databases/mimir.db{,-wal}` and queried locally with sqlite3. `logcat -c` before the m5a1 install.

## Verification points

### 1. Migration v4→v5 preserves games / art / prefs
Pulled DB after first launch of the m5a1 build:

```
PRAGMA user_version = 5
media: 4 rows, all kind=boxart, all source=libretro (backfill), URLs byte-identical
       to the v4 rows — including MK64's ES-DE content:// cover
play_state: table exists, 0 rows
platform_prefs: nds|melonds, gc|custom-com.android.chrome (intact)
games: 4 (intact)
```
Table-recreate migration (`media` → composite PK `(gameUri, kind)` + `source`) ran on real data with no loss. **PASS**

### 2. Launching a game stamps `play_state`
Tapped the Mario Kart 64 tile → fake emulator (`dev.mimir.fakeemulator/.CatchActivity`) came to the foreground → back. Pulled DB:

```
play_state: ...n64%2FMario%20Kart%2064.z64 | 1781225328531
```
`launchGame` → `LaunchResult.Success` → `repo.stampPlayed` works end-to-end. **PASS**

### 3. Ambient wash visible on relaunch (vs baseline)
- Baseline (`img/m5a1-baseline.png`, taken under v0.6.0 before the update): near-black background throughout, default purple accent on the action buttons.
- After `am force-stop` + relaunch of the m5a1 build (`img/m5a1-ambient.png`): a strong **magenta/purple gradient wash** from the top of the grid fading downward — extracted from the last-played game's boxart (MK64's magenta ES-DE cover) — and the action-button accent re-tinted magenta via `darkColorScheme(primary = LocalMimirTheme.current.primary)`. Both PNGs read back and visually compared: unmistakably different from the pure-black baseline. **PASS**
- Bonus confirmation: later in the session, stray E2E taps launched GoldenEye and then Wind Waker; on the next recomposition the wash re-tinted from magenta to the dark red/gold of the Wind Waker cover — the palette correctly follows whichever game was played most recently (3 `play_state` rows, `maxByOrNull(lastPlayedAt)` = Wind Waker).

### 4. ES-DE cover survives "Fetch artwork" (priority esde > libretro)
- The 4→5 backfill marks all migrated rows `source=libretro` (pre-v5 rows carry no provenance), so **Import ES-DE** was re-run first (SAF picker opened at `ES-DE/downloaded_media` → USE THIS FOLDER → ALLOW). DB after import: MK64 row `kind=boxart, source=esde`, magenta `content://` URI; other 3 rows libretro.
- Tapped **Fetch artwork**. DB after the scrape:

```
MK64: boxart | esde | content://...downloaded_media%2Fn64%2Fcovers%2FMario%20Kart%2064.png
```
Source column still `esde`, URI unchanged; the magenta tile still renders in the grid (screencap read back). Two layers enforce this: `gamesWithoutArt` is kind-scoped so games with a boxart row are never re-scraped, and `GameRepository.saveArt` filters through `ArtPriority.canReplace` (esde=3 > libretro=1) as the backstop — the backstop path is additionally locked by the `LibraryDaoTest` priority test (folder row survives an sgdb save). **PASS**

### 5. Logcat clean
`logcat -d | grep -c "FATAL EXCEPTION"` → **0** across install, migration, game launches, force-stop/relaunch, ES-DE re-import, and artwork fetch. Remaining matches are benign (uiautomator runtime init lines, input-channel teardown warnings from force-stop). **PASS**

## Notes
- The m5a1 APK still carried `versionCode 6` at install time (the version bump to 7 / `0.7.0-m5a1` is the docs-step commit that follows this file); `install -r` with an equal versionCode replaces fine, and `user_version = 5` proves the new binary ran the migration.
- The migrated DDL carries SQL `DEFAULT 'boxart'`/`DEFAULT 'libretro'` clauses that a fresh-install Room DDL omits; Room schema validation accepts this (entity-side defaults are null) — verified by the `MigrationTest` 4→5 test and by this on-device run.

## Evidence files
- `docs/superpowers/plans/img/m5a1-baseline.png` — v0.6.0 before the update: black background, 4 games, magenta MK64 tile
- `docs/superpowers/plans/img/m5a1-ambient.png` — m5a1 after relaunch: magenta ambient wash from the top of the grid, re-tinted accents, same library intact
