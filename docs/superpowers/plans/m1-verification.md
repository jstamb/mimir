# M1 Launch Loop — End-to-End Verification

- **Date:** 2026-06-10
- **Device:** AVD `mimir-test` (pixel_5 profile), `system-images;android-34;google_apis;arm64-v8a` — Android 14 / API 34, headless (`-no-window -gpu swiftshader_indirect`), `emulator-5554`
- **Builds:** `:app:installDebug` + `:tools:fake-emulator:installDebug` from branch `m1-launch-loop` (BUILD SUCCESSFUL; both packages confirmed via `pm list packages`)
- **Fixture data:** `/sdcard/Roms/` seeded with `n64/Mario Kart 64.z64`, `n64/GoldenEye.z64`, `GameCube/Wind Waker.rvz`, `nds/Mario Kart DS.nds`, `notes.txt`
- **Driving method:** headless — `uiautomator dump` to read the hierarchy, `input tap` / `input keyevent` to act

## Verification points

### 1. App launch (empty state)
`am start dev.mimir.app/.MainActivity` → UI dump shows the empty state:

```
text='Mimir' ... bounds=[427,1062][653,1172]
text='Choose ROM folder' ... (clickable button at [306,1216][774,1348])
```

**PASS**

### 2–3. SAF folder grant
Tapped "Choose ROM folder" → system picker (`com.android.documentsui`) opened directly at the root of internal storage (`Files on sdk_gphone64_arm64`) with the `Roms` folder card visible — no root-drawer navigation needed. Tapped `Roms` (breadcrumb confirmed `sdk_gphone64_arm64 > Roms`, header "Files in Roms"), tapped `USE THIS FOLDER`, then `ALLOW` on the consent dialog:

```
text='Allow Mimir to access files in Roms?' id=...documentsui:id/alertTitle
text='ALLOW' id=android:id/button1
```

**PASS**

### 4. Automatic scan & library contents
Immediately after the grant, Mimir scanned and rendered the library:

```
text='4 games'
text='GameCube'      → card 'Wind Waker'
text='Nintendo 64'   → cards 'Mario Kart 64', 'GoldenEye'
text='Nintendo DS'   → card 'Mario Kart DS'
text='1 files skipped — first: notes.txt (no platform registered for extension .txt and no platform folder in path)'
```

All 4 games present, grouped under correct platform headers, with the skipped-files line. **PASS**

### 5. Launch into Fake Emulator
Tapped the "Mario Kart 64" card → CatchActivity opened and displayed:

```
FAKE EMULATOR — intent received
action: android.intent.action.VIEW
data:   content://com.android.externalstorage.documents/tree/primary%3ARoms/document/primary%3ARoms%2Fn64%2FMario%20Kart%2064.z64
flags:  0x10000001
extras: (none)
```

`dumpsys activity activities` confirms it is foreground:

```
topResumedActivity=ActivityRecord{ab6d0ad u0 dev.mimir.fakeemulator/.CatchActivity t9}
Intent { act=android.intent.action.VIEW dat=content://...Mario%20Kart%2064.z64 typ=*/* flg=0x10000001 cmp=dev.mimir.fakeemulator/.CatchActivity }
```

**PASS**

### 6. Idempotent rescan
`input keyevent 4` (back) returned to the library (still "4 games"). Tapped "Rescan" → after scan, dump shows the identical library: "4 games", same three platform headers, same four cards, same single skipped line. No duplicates. **PASS**

### 7. Skipped-files diagnostics (bonus)
The skipped line names the file and gives an extension-based reason:

```
1 files skipped — first: notes.txt (no platform registered for extension .txt and no platform folder in path)
```

**PASS**

## Logcat sanity
`adb logcat -d | grep -iE "AndroidRuntime|FATAL" | grep -i mimir` → no matches (no crashes from either package during the run).

## Deviations encountered & resolutions
- `sdkmanager` failed under the default JDK 1.8 ("This tool requires JDK 17 or later") — resolved by exporting `JAVA_HOME=/opt/homebrew/opt/openjdk@21/...` for all SDK tooling, not just gradle.
- First `avdmanager create avd` raced the still-downloading system image ("Package path is not valid") — resolved by retrying after the install completed.
- Host Python 3.14's `pyexpat` is broken (dlopen symbol error), so the UI-dump parser was rewritten with regex instead of `xml.etree`.
- The SAF picker opened directly at the internal-storage root with `Roms` visible, so no hamburger/root-drawer navigation was required.

## Evidence files
- `docs/superpowers/plans/img/m1-library.png` — library grid (4 games + skipped line)
- `docs/superpowers/plans/img/m1-fake-emulator.png` — Fake Emulator catch screen
