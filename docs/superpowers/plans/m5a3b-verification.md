# M5a-3b E2E Verification — Shell Part 2 (Home, Sound, Backup)

- **Date:** 2026-06-11 · **Device:** Pixel_10_Pro emulator (emulator-5554, 1280×2856, API 36), left running
- **Build:** app-debug from branch `m5a3b-home-sound-backup` (post-1f6f1e8 + one review fix folded), `adb install -r` over the resident M5a-3a build. Note: the resident binary self-reported `versionName=0.8.0-m5a2` because the 0.9.0 version chore (6594925) landed *after* the m5a3a E2E install — the code on device was the m5a3a shell. Library DB (5 games / 3 systems, SGDB heroes+logos, 3 play states) carried over intact.

### 1. Boots to HOME — continue-playing shelf with art
Cold launch → **Home is the default route**: persistent hero up top (GoldenEye — still showing the old wrong *GoldenEye: Source* art at this point, which becomes the check-7 baseline), then **CONTINUE PLAYING** shelf with 3 recent cards in last-played order — GoldenEye (folder-art green PNG), Wind Waker (SGDB Zelda boxart), Mario Kart 64 (ES-DE magenta) — each with a bottom-gradient title scrim, then **SYSTEMS** row: GameCube · 1 game / Nintendo 64 · 3 games / Nintendo DS · 1 game + "Browse all ›". Capture: `img/m5a3b-home.png`. **PASS**

### 2. Shelf tap launches directly (no select-first)
Single tap on the GoldenEye shelf card → straight to the fake emulator: `topResumedActivity=ActivityRecord{… dev.mimir.fakeemulator/.CatchActivity}`. No intermediate selection state — Switch "continue playing" semantics as planned. **PASS**

### 3. System tile opens Browse on that system
Home → tapped the **Nintendo DS** tile → Browse rendered with the **Nintendo DS · 1** tab active (not the first system), grid showing only Mario Kart DS, "Search 1 games". `initialSystem` seeding works. **PASS**

### 4. Browse-all + back gesture semantics
Back gesture from the DS-seeded Browse → **Home** (shelf + systems re-rendered). "Browse all ›" → Browse on the first system (GameCube · 1 active). Back again → Home. Also observed: Settings "Back" → Browse (the chosen onBack→BROWSE semantics). **PASS**

### 5. Sound engine load + playback evidence (headless — no audible check)
`dumpsys audio` player log:
```
19:02:38 new player piid:47 uid/pid:10227/24829 package:dev.mimir.app type:android.media.SoundPool
         attr: usage=USAGE_ASSISTANCE_SONIFICATION content=CONTENT_TYPE_SONIFICATION
19:03:04 player piid:47 event:started   ← NAV cue (system tile tap)
19:03:18 player piid:47 event:started   ← BACK cue (back gesture)
```
SoundPool registered once at app start with the planned audio attributes, and `event:started` fired exactly at cue call sites. Logcat additionally shows the app pid spinning up 4 `audio/raw` CCodec decoders at 19:02:38 — the four bundled wav cues (nav_tick 1.9KB / select 3.9KB / launch 12.1KB / back 2.6KB) being decoded; modern Android logs SoundPool work via CCodec, no "SoundPool" tag lines exist. Zero soundpool/audio errors. **PASS**

### 6. Backup export via SAF
Overflow ⋮ → **Back up art & themes** (sits between Scan report and Change ROM folder) → SAF CreateDocument sheet pre-filled `mimir-backup.zip` → navigated to **Downloads** → Save → snackbar **"Backup saved — art, emulator choices, playtime, theme"**. Pulled `/sdcard/Download/mimir-backup.zip` (1,731 bytes), unzipped — exactly **6 entries**, all valid JSON:

| entry | contents |
|---|---|
| `media.json` | **15 rows** — by source: **sgdb 13 / esde 1 / folder 1**; by kind: boxart 5 / hero 5 / logo 5 |
| `platform_prefs.json` | 2 rows (`nds→melonds`, `gc→custom-com.android.chrome`) |
| `game_prefs.json` | 0 rows (none set on device) |
| `play_state.json` | 3 rows with real content-URIs + epoch-ms timestamps |
| `custom_players.json` | 0 rows |
| `theme.json` | `{}` — all-default ThemeConfig; kotlinx omits defaults (correct round-trip; restore yields the same defaults) |

**PASS**

### 7. SGDB exact-match fix on device (the GoldenEye: Source bug)
Baseline (pulled DB, pre-fix rows kept by design — fetch only fills games missing art):
```
GoldenEye.z64      hero  sgdb  …/hero/b80ba73857eed2a36dc7640e2310055a.png   (GoldenEye: Source)
GoldenEye.z64      logo  sgdb  …/logo/74765968c67007219b197f4d9aafb4e2.png   (GoldenEye: Source)
```
Added `/sdcard/Roms/n64/GoldenEye 007.z64` → overflow Rescan (N64 tab → 4 games) → Settings → **Fetch heroes & logos** (stored SGDB key). Pulled DB again — the new game resolved to the *correct* SGDB entry:
```
GoldenEye 007.z64  boxart sgdb …/grid/f0971d67a49ade9bab406f7664e8129d.png
GoldenEye 007.z64  hero   sgdb …/hero/d91d1b4d82419de8a614abce9cc0e6d4.jpg   ≠ old hero
GoldenEye 007.z64  logo   sgdb …/logo/c77cfd5563c8ec4bfcde94c09098ba84.png   ≠ old logo
```
On screen: selecting the new card swaps the hero pane from the *Source* art to the Pierce-Brosnan **GoldenEye 007** hero + proper 007 logo, gold selection ring + gold ambient wash. Capture: `img/m5a3b-sgdb-fix.png`. (Exact/startsWith/fallback tiers also unit-tested — scraper 24.) **PASS**

### 8. Logcat clean
Full session dump (5,682 lines): `FATAL EXCEPTION` → 0 · `AndroidRuntime.*dev.mimir` → 0 · app-tagged exceptions → 0 · coil errors → 0. Only E-level lines from the app pid: 4× `Failed to query component interface for required system resources: 6` at 19:02:38 — the emulator Codec2 HAL's known benign complaint during the 4 wav decodes (decode + playback subsequently verified in check 5). **PASS**

## Review fix folded into this commit
1. **`exportBackup` stream leak** (review item, minor): the SAF `OutputStream` was only closed by `ZipOutputStream.use{}` *inside* `BackupWriter.write` — if a Room snapshot query threw before the zip was constructed, the stream leaked. Now `out.use { BackupWriter.write(it, …) }` in `MainViewModel.exportBackup`; double-close on the success path is a safe no-op.

### Evidence files
- `img/m5a3b-home.png` — cold launch: HOME default route, continue-playing shelf (3 recents with art), systems row, Browse all
- `img/m5a3b-sgdb-fix.png` — "GoldenEye 007" selected: correct 007 hero art + logo in the hero pane, new SGDB boxart on the card, old green-PNG GoldenEye untouched beside it

### Known nits (non-blocking)
- `theme.json` exports `{}` for an all-default config (kotlinx `encodeDefaults=false`). Harmless for restore; consider `encodeDefaults = true` when backup IMPORT lands so the file is self-describing.
- Backup runs on whatever theme/library state is loaded at tap time (snapshot queries) — fine; no progress UI for very large libraries (export here was instant).
