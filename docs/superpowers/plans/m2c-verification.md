# M2c Custom Player Definitions — End-to-End Verification

- **Date:** 2026-06-11
- **Device:** AVD `Pixel_10_Pro`, already running headless as `emulator-5554` (the M2b run used port 5556; same AVD, same disk image — verified via `adb emu avd name`)
- **Starting state:** v0.4.0-m2b (versionCode 4) installed with **real data**: Room DB at schema **v3**, 4 games, 4 boxart `media` rows. The melonDS NDS default from the M2b E2E was **no longer on-device** (`platform_prefs` pulled empty — the device had been used since), so the precondition was restored first: set NDS → melonDS through the v0.4.0-m2b UI, force-stop, and confirm `nds|melonds` in the pulled DB **before** installing the new build.
- **Build:** `:app:assembleDebug` from branch `m2c-custom-players` @ `e26f108`, installed **over** the existing app with `adb install -r` (upgrade path, data kept)
- **Driving method:** headless — `uiautomator dump` read via grep/regex (host pyexpat broken, same M1/M2a/M2b workaround), `input tap` to act, `screencap` for visual confirmation

## Verification points

### 1. Non-destructive migration 3→4 preserves prefs
Installed the M2c build over v0.4.0-m2b, launched (`am start -W`, TotalTime 1337 ms). Library intact on first dump: "4 games", GameCube / Nintendo 64 / Nintendo DS headers, all four cards; screenshot confirmed **boxart rendered** for all games. DB pulled after launch (db + WAL):

```
PRAGMA user_version → 4        (was 3 before the install)
tables: ... custom_players ... (new, 0 rows)
games  = 4   (preserved)
media  = 4   (preserved)
platform_prefs: nds|melonds    (preserved)
```

Settings → Emulators still shows **`Default: melonDS (not installed)`** on the Nintendo DS row — MIGRATION_3_4 ran (not the destructive fallback) and the pref survived. **PASS**

### 2. Add custom emulator via UI
App used as the custom emulator: **Chrome (`com.android.chrome`)** — present on this google_apis image. Settings → "Add custom emulator" → "Pick the app" dialog listed launchable apps (Calendar, Camera, Chrome, Clock, Contacts, Drive, …) with package names → tapped **Chrome** → "Pick its systems" → checked **GameCube** → **Add**. The custom card appeared instantly (reactive flow, no restart): name **Chrome**, subtitle **`com.android.chrome — gc`**, with a Remove button. The GameCube row flipped to `Auto: Chrome` (first installed claimant) and its Change dropdown now lists **`Dolphin (not installed)` / `Chrome` / `Mimir Fake Emulator`**. **PASS**

### 3. Custom as platform default + real handoff
Set GameCube default = Chrome → row shows `Default: Chrome`; DB confirms `platform_prefs: gc|custom-com.android.chrome` and the `custom_players` row `custom-com.android.chrome|Chrome|com.android.chrome|gc`. Tapped **Wind Waker** → Chrome opened. Logcat (ActivityTaskManager):

```
START u0 {act=android.intent.action.VIEW
  dat=content://com.android.externalstorage.documents/...
  typ=*/* flg=0x10000001 pkg=com.android.chrome
  cmp=com.android.chrome/com.google.android.apps.chrome.IntentDispatcher}
  from uid 10227 (dev.mimir.app) ... result code=0
```

Chrome then showed its FirstRunActivity (first launch of Chrome on this image) — the handoff itself is what's under test, and it landed: Mimir resolved gc → the custom player and delivered the content-URI VIEW intent to Chrome. **PASS**

Screenshot of the settings screen with the custom card: `img/m2c-custom.png` (all 7 platforms, NDS `Default: melonDS (not installed)`, GameCube `Default: Chrome`, Custom emulators section with the Chrome card + Remove, Add custom emulator button).

### 4. Remove custom → dangling-pref fall-through, no crash
Tapped **Remove** on the Chrome card → card disappeared immediately and the GameCube dropdown no longer lists Chrome (only `Dolphin (not installed)` / `Mimir Fake Emulator`). The platform default still points at the deleted id — DB shows `gc|custom-com.android.chrome` with `custom_players` empty (**dangling pref**). Tapped **Wind Waker** → no crash: resolution's platform-default tier missed (`byId` lookup returns null for the deleted id), fell through to the installed-claimant tier, and **Mimir Fake Emulator** caught it:

```
FAKE EMULATOR — intent received
action: android.intent.action.VIEW
data:   content://.../primary%3ARoms%2FGameCube%2FWind%20Waker.rvz
flags:  0x10000001
```

`topResumedActivity=...dev.mimir.fakeemulator/.CatchActivity`, `logcat -d | grep -c FATAL` → 0. **Known-acceptable finding:** the dangling `custom-…` pref row is left in `platform_prefs` after deletion; the settings row then renders the *effective* pick labeled `Default: Mimir Fake Emulator`. Behavior is safe (graceful fall-through), just not garbage-collected. **PASS**

### 5. Restart persistence
`am force-stop dev.mimir.app` + relaunch → Settings → Emulators: zero Chrome mentions anywhere (custom player stayed removed), NDS still `Default: melonDS (not installed)`, Custom emulators section back to just the Add button. Logcat after a cleared buffer for the whole relaunch flow: **0 FATAL**, no `dev.mimir` error lines. **PASS**

## Notes & deviations
- The emulator was already running as **emulator-5554** rather than the expected 5556 — same `Pixel_10_Pro` AVD and disk state, confirmed before use.
- The melonDS NDS pref had to be **re-seeded via the old build's UI** before the upgrade install (see Starting state); the migration check is still genuine — the pref demonstrably existed in the v3 DB pulled pre-install and survived to v4.
- On-device versionName during this run was still `0.4.0-m2b` — the M2c version bump is part of this Task 4 docs commit, which lands after the E2E build was cut from `e26f108`. Irrelevant to every check (all key off DB schema + UI behavior).

## Evidence files
- `docs/superpowers/plans/img/m2c-custom.png` — Emulators settings: 7 platforms, NDS `Default: melonDS (not installed)`, GameCube `Default: Chrome`, custom card `Chrome / com.android.chrome — gc` with Remove
