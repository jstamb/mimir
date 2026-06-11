# M4a ES-DE Media Import — End-to-End Verification

- **Date:** 2026-06-11
- **Device:** AVD `Pixel_10_Pro`, already running headless as `emulator-5554` (verified via `adb -s emulator-5554 emu avd name`)
- **Starting state:** real data from prior milestones — 4 games (Wind Waker / GoldenEye / Mario Kart 64 / Mario Kart DS), 4 `media` rows all holding **libretro thumbnail URLs**; screencap confirmed all four cards rendering real boxart before the import
- **Build:** `:app:installDebug` from branch `m4a-esde-import` @ `57df664`
- **Fixture:** fake ES-DE tree on-device, with a generated solid-magenta 200×280 PNG (visually unmistakable vs the libretro art):

```
/sdcard/ES-DE/downloaded_media/n64/covers/Mario Kart 64.png   (magenta, 787 bytes)
/sdcard/ES-DE/downloaded_media/n64/videos/Mario Kart 64.mp4   (empty — count-only path)
```

- **Driving method:** headless — `uiautomator dump` for button bounds, `input tap` to act, `screencap` read for visual confirmation. Flow: tap **Import ES-DE** → SAF picker → breadcrumb to storage root → `ES-DE` → `downloaded_media` → **USE THIS FOLDER** → **ALLOW**.

## Verification points

### 1. Snackbar message
Immediately after ALLOW: **"Imported 1 covers from ES-DE — 1 videos found (video support comes with theming)"** — exact plan string, covers count 1 (the matched MK64 cover), video count 1 (the `.mp4` matched a library title; counted, not imported). **PASS**

### 2. Imported cover visibly replaces libretro art
Screencap read back (`img/m4a-imported.png`): the **Mario Kart 64 card is solid magenta** — the generated ES-DE cover, not the libretro Mario Kart boxart it showed before the import. GoldenEye, Wind Waker, and the Nintendo DS section are unchanged. The swap happened **without restart** (reactive chain: `saveArt` → Room `media()` Flow → `libraryData` combine → `UiState.Library.art` → Coil `AsyncImage`). **PASS**

### 3. DB row holds the ES-DE SAF content URI
Pulled `databases/mimir.db` + WAL via `run-as` and queried the `media` table — MK64's row was **REPLACEd** with the ES-DE content URI; the other three rows still hold libretro URLs:

```
...primary%3ARoms%2Fn64%2FMario%20Kart%2064.z64 |
  content://com.android.externalstorage.documents/tree/primary%3AES-DE%2Fdownloaded_media/
    document/primary%3AES-DE%2Fdownloaded_media%2Fn64%2Fcovers%2FMario%20Kart%2064.png
GoldenEye / Wind Waker / Mario Kart DS → https://thumbnails.libretro.com/... (unchanged)
```
**PASS**

### 4. Restart persistence
`am force-stop dev.mimir.app` + relaunch → Mario Kart 64 card is **still magenta** on a cold process (fresh Coil load of the `content://` URI proves the **persisted READ permission** works, fresh DB read proves the row survived). Other cards unchanged. **PASS**

### 5. Logcat clean
`logcat -c` was run before the whole flow; after import + restart: `grep -c FATAL` → **0**. Only benign force-stop teardown warnings (input channel closed / `dispatchAppVisibility` on the EXITING window). **PASS**

## Notes
- The SAF picker opened at its last location (`Roms`); navigated via the breadcrumb to storage root, then `ES-DE/downloaded_media` — the import tree is picked at the `downloaded_media` level exactly as the plan's flow describes (system dirs must be the first path segment for alias resolution).
- `adb shell touch` with an unquoted spaced filename split into three args on first attempt (created a stray `Mario` file, removed); re-ran with the path quoted inside the shell string.

## Evidence files
- `docs/superpowers/plans/img/m4a-imported.png` — library grid right after import: snackbar "Imported 1 covers from ES-DE — 1 videos found (video support comes with theming)", Mario Kart 64 card solid magenta, GoldenEye/Wind Waker libretro art unchanged
