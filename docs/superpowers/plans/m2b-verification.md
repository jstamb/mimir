# M2b Emulator Management — End-to-End Verification

- **Date:** 2026-06-11
- **Device:** AVD `Pixel_10_Pro`, headless (`-no-window -gpu swiftshader_indirect`), `emulator-5556`
- **Starting state:** device had the previous app build installed (versionCode 2 on disk — the M3 E2E install predated the M3 version-bump commit) with **real data**: Room DB at schema **v2**, 4 games, 4 boxart `media` rows (verified by pulling `databases/mimir.db` and querying `PRAGMA user_version` + row counts before install)
- **Build:** `ANDROID_SERIAL=emulator-5556 ./gradlew :app:installDebug` from branch `m2b-emulator-management` @ `b2e649a` — installed **over** the existing app (upgrade path, data kept)
- **Driving method:** headless — `uiautomator dump` read via grep/regex (host pyexpat broken, same M1/M2a workaround), `input tap` / `input swipe` to act

## Verification points

### 1. Non-destructive migration (MIGRATION_2_3 on real data)
Installed the new build over the old one, launched (`am start -W`, TotalTime 1263 ms). The very first UI dumps showed the full library with **no wipe and no rescan needed**: "4 games", platform headers GameCube / Nintendo 64 / Nintendo DS, all four cards (Wind Waker, GoldenEye, Mario Kart 64, Mario Kart DS), and the "1 files skipped — view scan report" line. Screenshot confirmed **boxart rendered immediately** for all four games — i.e. the `media` table survived.

DB pulled again after launch:

```
PRAGMA user_version → 3        (was 2 before the install)
tables: games, skipped_files, media, room_master_table, platform_prefs, game_prefs
games  = 4   (preserved)
media  = 4   (preserved)
platform_prefs = 0, game_prefs = 0   (new tables, empty as expected)
```

MIGRATION_2_3 ran (not the destructive fallback) and the two prefs tables were created with all existing data intact. **PASS**

### 2. Settings flow + persistence
Tapped "Emulators" in the grid header → settings screen listed **all 7 platforms** (NES, SNES, N64, GBA, NDS, GameCube, PlayStation), each with an Auto/Default line. With no prefs set every row showed `Auto: Mimir Fake Emulator` (first **installed** claimant — only the fake is installed on this device).

Tapped **Change** on Nintendo DS → dropdown showed `melonDS  (not installed)` and `Mimir Fake Emulator`. **DraStic is absent by design** — it was omitted from the registry in Task 1 (delisted from the Play Store in 2025; package id had no live primary source), documented in commit `7979880`. NDS stays covered by melonDS, so the registry-coverage test holds.

Selected **melonDS** → row flipped to `Default: melonDS (not installed)` (error color). Then `am force-stop dev.mimir.app`, relaunched, reopened Emulators → still `Default: melonDS (not installed)` — the pref persisted through process death via Room. Screenshot: `img/m2b-settings.png` (taken after the relaunch, showing the persisted Default). **PASS**

### 3. Guided not-installed failure
Tapped the "Mario Kart DS" card → snackbar:

```
melonDS is not installed — install it or pick another emulator in Settings
```

No crash, no fake-emulator launch (`topResumedActivity` stayed `dev.mimir.app/.MainActivity`) — the explicit per-platform pref wins even though melonDS isn't installed, on purpose. **PASS**

### 4. Per-game override via long-press
Long-pressed "Mario Kart DS" → "Play with…" sheet appeared listing `melonDS  (not installed)` and `Mimir Fake Emulator` (screenshot `img/m2b-sheet.png`). Picked **Mimir Fake Emulator** → Fake Emulator opened **immediately on the first pick** (override + launch in one tap — the stale-resolution race fixed in `b2e649a` did not reproduce):

```
FAKE EMULATOR — intent received
action: android.intent.action.VIEW
data:   content://com.android.externalstorage.documents/tree/primary%3ARoms/document/primary%3ARoms%2Fnds%2FMario%20Kart%20DS.nds
flags:  0x10000001
```

`topResumedActivity=...dev.mimir.fakeemulator/.CatchActivity`. Back; long-pressed again → sheet shows `Mimir Fake Emulator  ✓ current` and the `Clear override — use system default` row. **PASS**

### 5. Resolution sanity for unset platforms
Tapped "Mario Kart 64" (n64 — no platform default, no override) → Fake Emulator caught it (only installed claimant for n64):

```
data: content://.../primary%3ARoms%2Fn64%2FMario%20Kart%2064.z64
```

Logcat clean throughout the whole run: `logcat -d | grep -c "FATAL"` → **0**; the only AndroidRuntime lines are uid-2000 `uiautomator` Launcher RuntimeInit entries from the dump tooling itself. **PASS**

## Notes & deviations
- **DraStic omitted from the NDS dropdown** — registry-level deviation from the plan's expected dropdown contents, documented at Task 1 (`7979880`): delisted/closed-source, package id unverifiable from a primary source.
- The NDS row's pre-pref state was `Auto: Mimir Fake Emulator` (fake is the first *installed* claimant on this device), not `Auto: melonDS` — consistent with the settings row's effective-pick logic (selected → first installed → first claimant).
- Device's reported old versionName was `0.2.0-m2a` despite carrying M3 data — the M3 E2E install came from the m3 branch before its version-bump commit. Irrelevant to the migration check, which keys off DB schema v2 + real rows (both confirmed).

## Evidence files
- `docs/superpowers/plans/img/m2b-settings.png` — Emulators settings, all 7 platforms, NDS `Default: melonDS (not installed)` persisted after force-stop
- `docs/superpowers/plans/img/m2b-sheet.png` — long-press "Play "Mario Kart DS" with…" sheet over the boxart grid
