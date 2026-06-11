# M2a Persistence + Scanner v2 — End-to-End Verification

- **Date:** 2026-06-11
- **Device:** AVD `mimir-test` (pixel_5 profile), `system-images;android-34;google_apis;arm64-v8a` — Android 14 / API 34, headless (`-no-window -gpu swiftshader_indirect`), `emulator-5554`
- **Builds:** `:app:installDebug` + `:tools:fake-emulator:installDebug` from branch `m2a-persistence-scanner` (BUILD SUCCESSFUL; both packages confirmed via `pm list packages`). `dev.mimir.app` was **uninstalled first** for a clean slate (fresh SAF grant flow).
- **Fixture data:** `/sdcard/Roms/` intact from M1: `n64/Mario Kart 64.z64`, `n64/GoldenEye.z64`, `GameCube/Wind Waker.rvz`, `nds/Mario Kart DS.nds`, `notes.txt`
- **Driving method:** headless — `uiautomator dump` to read the hierarchy (regex-parsed), `input tap` / `input keyevent` to act

## Verification points

### 2. Cold-start library + persistence
Fresh install opened to the empty state (`Mimir` / `Choose ROM folder`). Tapped the button → SAF picker opened directly inside `Roms` (breadcrumb `sdk_gphone64_arm64 > Roms`, header "Files in Roms") → `USE THIS FOLDER` → `ALLOW` on the consent dialog. The library rendered with the M1 set:

```
text='4 games'
text='GameCube'      → card 'Wind Waker'
text='Nintendo 64'   → cards 'GoldenEye', 'Mario Kart 64'
text='Nintendo DS'   → card 'Mario Kart DS'
text='1 files skipped — view scan report'
```

Then `am force-stop dev.mimir.app && am start -W dev.mimir.app/.MainActivity` (TotalTime: 685 ms) and dumped the UI **immediately on resume**: the very first dump already showed the full grid — "4 games", all three platform headers, all four cards, the skipped line — i.e. the library rendered straight from Room before any rescan could finish (a background rescan also runs on init; the point is the grid is visible at once, not gated on it). No empty/NeedsFolder flash. **PASS**

### 3. Incremental rescan (add + remove)
`adb shell touch "/sdcard/Roms/n64/Banjo-Kazooie.z64"` → tapped Rescan → dump shows:

```
text='5 games'
text='Banjo-Kazooie'   (under Nintendo 64, alongside GoldenEye / Mario Kart 64)
```

Exactly one `Banjo-Kazooie` node in the hierarchy; all four original cards still present exactly once — **no duplicates**. Screenshot captured (`img/m2a-library.png`). Then `adb shell rm ".../Banjo-Kazooie.z64"` → Rescan → "4 games", Banjo-Kazooie gone, original four intact. **PASS**

### 4. Scan report screen
Tapped "1 files skipped — view scan report" → report screen:

```
text='Scan report — 1 skipped'
text='notes.txt'
text='no platform registered for extension .txt and no platform folder in path'
```

Screenshot captured (`img/m2a-report.png`). Tapped `Back` → returned to the grid ("4 games" / Rescan visible). **PASS**

### 5. Error state (revoked/lost folder access)
`adb shell mv /sdcard/Roms /sdcard/Roms.bak` → tapped Rescan. **Observed behavior: the Error screen appeared** (the `TreeAccessException` path — the document provider returned a null cursor for the missing tree):

```
text="Something's wrong"
text="Can't read the ROM folder (document provider returned no result for
      content://com.android.externalstorage.documents/tree/primary%3ARoms/document/primary%3ARoms).
      Pick it again or check the storage."
buttons: 'Retry' / 'Change folder'
```

No crash: `adb logcat -d | grep -E "FATAL|AndroidRuntime"` shows no FATAL entries and no app-process AndroidRuntime crashes (only uid-2000 `uiautomator` Launcher RuntimeInit lines from the dump tooling itself). Restored with `adb shell mv /sdcard/Roms.bak /sdcard/Roms`, tapped Retry → "4 games" returned with all cards and the skipped line. **PASS**

### 6. Launch still works
Tapped the "Mario Kart 64" card → Fake Emulator catch screen:

```
FAKE EMULATOR — intent received
action: android.intent.action.VIEW
data:   content://com.android.externalstorage.documents/tree/primary%3ARoms/document/primary%3ARoms%2Fn64%2FMario%20Kart%2064.z64
flags:  0x10000001
extras: (none)
```

`dumpsys activity activities` → `topResumedActivity=ActivityRecord{... dev.mimir.fakeemulator/.CatchActivity ...}`. **PASS**

## Notes & deviations
- The SAF picker opened directly inside `Roms` (documentsui remembered the last location from M1), so only `USE THIS FOLDER` + `ALLOW` were needed — same consent dialog as M1.
- Step 5 produced the **Error screen** (not the graceful empty-library alternative): on this emulator the externalstorage provider answers the child query for a removed directory with a null cursor, which `DocumentsTreeSource` maps to `TreeAccessException`.
- Host Python 3.14 `pyexpat` remains broken; UI dumps parsed with regex (same M1 workaround).

## Evidence files
- `docs/superpowers/plans/img/m2a-library.png` — library grid with 5 games (Banjo-Kazooie added) and skipped-files link
- `docs/superpowers/plans/img/m2a-report.png` — scan report screen (`notes.txt` + full reason)
