# Mimir M5 — Theme System & UI Shell Design

**Date:** 2026-06-11
**Status:** Design approved (Jordan, via visual companion session — mockups archived in `.superpowers/brainstorm/`)
**Split:** M5a = single-screen shell now (emulator-testable) · M5b = dual-screen Presentation deck when the Thor arrives (reuses every M5a component)

---

## 1. Locked design decisions

| Decision | Choice |
|---|---|
| Visual identity | **Ambient Glass** — the UI has no fixed palette; it extracts colors from the focused game's art and washes the screen(s) with it. Glass (translucent, blurred) tiles float over the ambient glow. |
| Game browse (bottom screen / main) | **Cover grid + alphabet jump-rail + L1/R1 system tabs**, with a dense-list view one toggle away. Rail and tabs auto-hide for small systems (degrades to a pure Cocoon-style grid). Screen-swap is a **visible labeled control**, never a hidden button. |
| Hero canvas (top screen) | **Full-bleed art + game logo + glass metadata strip** — frosted pills: system · resolved emulator (which app will launch) · playtime · last played. Video-snap "living canvas" is a later theme token; pure-art is a "minimal" theme variant. |
| Home | **Continue-playing shelf** (recently played, big covers, playtime) + compact system row beneath. Boot shows your current game on the hero canvas immediately. |
| Theme composability | **Per-slot mixing from day one.** A theme pack is a preset of independently overridable slots. |
| Sound + haptics | **In scope for M5a** — navigation ticks, focus sounds, launch sting, haptic profile, all as theme tokens with one tasteful default set. |

## 2. Community findings driving the design (r/AynThor Cocoon 2.2 thread + prior research)

1. **Large libraries break cover-grid-only UIs** (+19/+12) → alpha rail + tabs + list toggle are first-class, not bolt-ons.
2. **Cocoon's screen-swap is loved but undiscoverable** → labeled swap control.
3. **Theme MIXING is the loved customization** → per-slot tokens.
4. **Update anxiety (losing art/customizations)** → settings gets an explicit "Back up art & themes" export (zip of media DB rows + theme config) — trust made visible.
5. **3DS cosplay polarizes** (+8 "there are 3DSes for that") → Ambient Glass default; retro skins arrive as community theme packs.
6. **SGDB taxonomy is the community lingua franca** → adopt hero/grid/logo vocabulary everywhere.

## 3. Architecture

### 3.1 `:core:theme` — token engine (new pure-ish module)
```
ThemeConfig (persisted, Room v5 or DataStore)
├── paletteMode: AMBIENT | FIXED(seedColor)
├── wallpaper: { topScreen: uri|none, bottomScreen: uri|none }   // under ambient glow
├── sounds: SoundPack(navTick, focus, launch, back, error) — bundled default + user packs (folder of .ogg)
├── haptics: HapticProfile(nav, confirm, launch) — intensity presets incl. OFF
├── tiles: { shape: rounded|sharp|pill, density: cozy|compact, glow: on|off }
└── packId provenance per slot (enables mixing + "reset slot to pack X")
```
Compose consumes everything through a `MimirTheme` CompositionLocal provider. A "theme pack" is a JSON manifest naming values for any subset of slots; applying a pack writes only the slots it defines (mixing falls out naturally).

### 3.2 Ambient palette engine
- `androidx.palette` (or Coil-extracted bitmap) pulls dominant + vibrant swatches from the focused game's boxart/hero.
- Output: `AmbientPalette(primary, glow, scrim)` exposed as state; animated `animateColorAsState` transitions (300–500ms) when focus changes.
- Palette extraction runs off-main, memoized per art URI in an LRU; fallback palette = Mimir slate-blue when art is missing.
- 60Hz bottom-screen budget (M5b): ambient washes are static gradients re-tinted on focus change, NOT continuous animation.

### 3.3 Screens (all Compose, all token-driven)
- **HomeScreen**: continue-playing shelf (`games ORDER BY lastPlayedAt DESC LIMIT 8` — requires a `lastPlayedAt`/`playSeconds` column: launch events already flow through `launchGame`; play sessions = launch timestamp now, duration tracking via usage-stats later) + compact system row + entry to browse/settings.
- **BrowseScreen**: the chosen grid; `LazyVerticalGrid` + side `AlphabetRail` (drag = jump via `scrollToItem`), system tab row (tap or L1/R1 keyevents), list-view toggle, search.
- **HeroPane**: full-bleed art (hero > boxart fallback), SGDB logo PNG (fallback: styled title), metadata pill row. M5a: rendered as the top portion of the single screen; M5b: becomes the main-display content while Browse moves to the Presentation deck.
- **Existing screens reskinned**: Emulators settings, scan report, sheets — same components, token-driven surfaces.

### 3.4 Art pipeline additions (feed the new surfaces)
- **SteamGridDB client** (`:core:scraper`): user-entered API key (settings field; Jordan's key in local.properties for dev — NEVER committed); fetch heroes/logos/grids by name search; store as new media kinds.
- **Media schema**: `media` table grows `kind` (boxart|hero|logo) — Room migration; existing rows become kind=boxart.
- **Folder-art auto-pickup** (scanner): sibling `<rom>.png/.jpg` and `covers|images|boxart|media/` subfolders matched during rescan → kind=boxart, highest priority (user's explicit choice > ES-DE/SGDB > libretro).
- Priority per kind: local folder > ES-DE > SGDB > libretro.

### 3.5 Sound + haptic engine
- `SoundPool`-based, fire-and-forget, volume token; assets are theme-slot content. One bundled default set (subtle, modern — NOT 3DS chimes). Haptics via `View.performHapticFeedback`/`VibratorManager` presets.

### 3.6 Single-screen layout (M5a) → dual-screen (M5b)
- M5a: vertical stack — HeroPane (top ~40%) above Browse/Home (bottom ~60%) on one display; identical component tree.
- M5b: HeroPane → main display, Browse/Home → Presentation on the Thor's bottom screen; swap control exchanges their displays. The component boundary IS the screen boundary, by construction.

## 4. Out of scope for M5a
Video snaps (needs ScreenScraper per-user accounts — shippable model confirmed: users sign in with their own free accounts; Jordan registers dev creds), community theme-pack store, layout-swapping themes, RetroArch core args, arcade DAT naming, M5b deck.

## 5. Open items carried
- Jordan: register ScreenScraper dev credentials (unblocks videos later).
- Thor back-button quirk: test on hardware in M5b.
