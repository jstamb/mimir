# M5a-2 E2E Verification — Art Pipeline (folder art + SteamGridDB)

- **Date:** 2026-06-11 · **Device:** Pixel_10_Pro emulator (emulator-5554, 1280×2856, API 36)
- **Build:** app-debug from branch `m5a2-art-pipeline` (88ad6e8), installed with `adb install -r`
- **Fixture:** generated solid-green 200×280 PNG (925 bytes) pushed as the sibling-convention path:

```
/sdcard/Roms/n64/GoldenEye.png        (solid green — visually unmistakable)
```

### 1. Folder art picked up on rescan
App launch triggers the init rescan; the GoldenEye card turned **solid green** immediately — the sibling PNG, not the libretro GoldenEye boxart it showed in M5a-1. An explicit in-app **Rescan** tap re-confirmed (screencap read back: `img/m5a2-folderart.png` — GoldenEye green, Mario Kart 64 still the magenta M4a ES-DE cover, Wind Waker untouched). **PASS**

DB row (pulled `databases/mimir.db` + WAL via `run-as`, queried locally):

```
boxart | folder | ...primary%3ARoms%2Fn64%2FGoldenEye.z64 |
    content://com.android.externalstorage.documents/tree/primary%3ARoms/
        document/primary%3ARoms%2Fn64%2FGoldenEye.png
```

### 2. Folder beats libretro
Tapped **Fetch artwork** (libretro scrape) → GoldenEye card **still green**; the folder row was not downgraded (`ArtPriority`: folder=4 > libretro=1). **PASS**

### 3. SteamGridDB live fetch (real network, real key)
Key read from gitignored `local.properties` (32 chars, **REDACTED** — never echoed to logs, docs, or source; logcat contains 0 occurrences of "steamgriddb" key material). Entered through Settings → Art sources → key field via `adb input text`, **Save** → SharedPreferences `sgdbApiKey` byte-identical to local.properties (compared programmatically, printed only MATCH).

> E2E note: the emulator's "Try out your stylus" IME onboarding sheet was intercepting the first injected keystrokes (field stayed empty, `saved ""`). Dismissed it + `settings put secure stylus_handwriting_enabled 0`; after that `input text` delivered normally. App behavior was correct throughout — the empty-save round-trip actually exercised `setSgdbApiKey("")` and the disabled-Fetch-button state (button greyed when field blank).

**Fetch heroes & logos** → live SGDB API (Bearer auth) returned art for all 4 games; second run after adding a 5th ROM (`Star Fox 64.z64` + Rescan) produced the snackbar **"SteamGridDB: saved 3 art items"** (N=3 > 0, captured: `img/m5a2-sgdb-snackbar.png`). **PASS**

DB media table after both fetches:

```
source | kind   | count
-------+--------+------
folder | boxart | 1     (GoldenEye — kept, folder=4)
esde   | boxart | 1     (Mario Kart 64 — kept, esde=3)
sgdb   | boxart | 3     (Wind Waker, Mario Kart DS, Star Fox 64 — upgraded over libretro)
sgdb   | hero   | 5     (all games)
sgdb   | logo   | 5     (all games)
```

All URLs are `https://cdn2.steamgriddb.com/{grid|hero|logo}/...`. The `missingHero` filter held: the second fetch only queried the one game without a hero row (snackbar count 3 = hero+logo+grid for Star Fox 64 alone, not 15). Priority chain **folder > esde > sgdb > libretro** fully honored — no folder/esde row was overwritten by SGDB. **PASS**

### 4. Restart persistence
`am force-stop` + relaunch → grid repaints with GoldenEye green, MK64 magenta, Wind Waker's SGDB grid art, Star Fox 64 art — all from Room + Coil cache. **PASS**

### 5. Logcat clean
Full-session `logcat -d` (11.4k lines, from pre-launch `-c`):
`FATAL EXCEPTION|AndroidRuntime.*dev.mimir` → 0 · `coil.*(error|fail)` → 0 · `steamgriddb` → 0 (key never logged). **PASS**

### Evidence files
- `img/m5a2-folderart.png` — library after Rescan: GoldenEye solid green (folder art), MK64 magenta (ES-DE), Wind Waker libretro (pre-SGDB)
- `img/m5a2-sgdb-snackbar.png` — settings screen, snackbar "SteamGridDB: saved 3 art items", key field showing 32 masked dots
