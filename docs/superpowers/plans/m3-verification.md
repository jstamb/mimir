# M3 Boxart via libretro-thumbnails — End-to-End Verification

- **Date:** 2026-06-11
- **Device:** AVD `Pixel_10_Pro`, API 37, headless (`-no-window -gpu swiftshader_indirect`), `emulator-5556` — internet via host
- **Build:** `:app:installDebug` from branch `m3-artwork` (includes the review fixes: non-caching of failed listings, synchronous fetchArtwork guard)
- **Fixture data:** `/sdcard/Roms/` intact from M2a: `n64/Mario Kart 64.z64`, `n64/GoldenEye.z64`, `GameCube/Wind Waker.rvz`, `nds/Mario Kart DS.nds`, `notes.txt`; folder grant persisted
- **Driving method:** headless — `uiautomator dump` (regex-parsed), `input tap`, `screencap` with the PNG inspected for actual pixels

## Verification points

### 1. DB v2 destructive fallback + auto-rescan + auto-scrape
Installed over the v1 app (no uninstall). First launch (`am start -W`, TotalTime 1314 ms): Room's `fallbackToDestructiveMigration(dropAllTables = true)` wiped the v1 library, the init rescan repopulated it from the persisted SAF grant, and the artwork scrape auto-triggered. A dump ~12 s after launch showed the full grid ("4 games", all three platform headers, all four cards, "1 files skipped") with the header button reading **`Artwork 3/4`** — the scrape ran unprompted, with live progress. **PASS**

### 2. Boxart resolution + render
After the scrape, screencapped the grid and inspected the PNG (`img/m3-boxart.png`): all four cards show real boxart images with the title in a gradient scrim at the card bottom — not text placeholders. Resolved URLs pulled from the on-device `media` table (`run-as` + strings over `mimir.db`/`-wal`):

| Title | Resolved file | Tier |
|---|---|---|
| Mario Kart 64 | `Mario Kart 64 (USA).png` | exact |
| GoldenEye | `GoldenEye 007 (USA).png` | startsWith |
| Wind Waker | `Legend of Zelda, The - The Wind Waker (USA).png` | contains |
| Mario Kart DS | `Mario Kart DS (USA) (Demo) (Kiosk).png` | exact (see finding) |

**PASS** — all four render visible, correct artwork. The Coil ServiceLoader fallback from the plan was **not needed**: `coil-network-okhttp` auto-registered its fetcher and images loaded with the default singleton ImageLoader.

#### Finding: Mario Kart DS resolves the Demo/Kiosk variant
The live N. American DS listing contains both `Mario Kart DS (USA) (En,Fr,De,Es,It).png` (retail) and `Mario Kart DS (USA) (Demo) (Kiosk).png`. Both normalize to `mario kart ds` (parenthesized groups are stripped), so both land in the exact tier and both contain `(USA)`; the tie-break is shortest filename, and the Demo/Kiosk name is 2 characters shorter than the retail one. The rendered art is still proper Mario Kart DS box art, so this is cosmetic-only here — recorded per plan instructions, matcher left untouched. A future refinement could deprioritize `(Demo)`/`(Beta)`/`(Kiosk)`/`(Proto)` tags in the tie-break.

### 3. Re-tap "Fetch artwork" no-ops
With all art resolved, tapped **Fetch artwork** again: 3 s later the button already read "Fetch artwork" (enabled) again — `gamesWithoutArt()` is empty so `scrapeMissing` returns 0 immediately and the progress state resets. No duplicate rows, no errors, no stuck "Artwork x/y" label. **PASS**

### 4. Cold-start renders art instantly
`am force-stop dev.mimir.app && am start -W` (TotalTime 1276 ms); screencap ~2 s after launch shows the complete grid with **all four boxarts already painted** — Room media table + Coil disk cache, no network round-trip visible and no placeholder flash. **PASS**

### 5. Logcat clean
`adb logcat -d | grep -iE "FATAL|AndroidRuntime.*dev.mimir|coil.*(error|fail)"` → no matches across install, first scrape, re-tap, and cold start. **PASS**

## Evidence files
- `docs/superpowers/plans/img/m3-boxart.png` — library grid with boxart on all four cards (Wind Waker / GoldenEye 007 / Mario Kart 64 / Mario Kart DS), scrape complete, skipped-files link intact
