# M5a-4 E2E Verification — Design Polish (Layered Layout, Glass, Motion)

- **Date:** 2026-06-12 · **Device:** Pixel_10_Pro emulator (emulator-5554, 1280×2856, API 36), left running
- **Build:** app-debug from branch `m5a4-design-polish` (6617d5e), `adb install -r` over the resident beta1 (versionCode 10 / 1.0.0-beta1). Library DB (6 games / 3 systems, SGDB heroes+logos, play states) carried over intact.
- **Haze:** 1.7.2 (verified latest stable on the releases page; 2.0.0 is alpha-only through alpha03) — 1.x `hazeSource`/`hazeEffect` + `HazeStyle` API form.

### 1. Layered single-screen look
Cold launch → hero art (GoldenEye: Source planet) fills the **entire screen edge-to-edge behind the status bar**, game logo + platform pill float over it, and the lower ~62% is a **glass panel with 28dp rounded top corners** carrying the Home shelf + systems row. Panel geometry confirmed via uiautomator: panel children start at y=1121, panel top edge = 0.38 × 2856 = 1085. ✦ Browse equivalent (the flagship shot): `img/m5a4-layered.png` — Star Fox 64 red hero, STARFOX logo, glass tabs, translucent search, grid floating on glass. **PASS**

### 2. Hero crossfade + logo animation + ambient re-tint on selection
Browse N64, selected GoldenEye → tapped **GoldenEye 007** card. Mid-fade capture at ~350ms (`img/m5a4-hero-midfade.png`): both hero arts blended, 007 logo sliding/fading in. Settled (`img/m5a4-hero-after.png`): full Brosnan hero AND the **whole UI re-tinted neutral-gray → warm gold** (scrims, glass panel tint, tab/list accents). Repeated with Wind Waker (→ green/teal ambient) and Star Fox 64 (→ red). Before-state: `img/m5a4-hero-before.png`. **PASS**

### 3. Real blur through the glass (API 36)
2× crop of the art/panel boundary (`img/m5a4-blur-crop.png`, from the gold 007 state): above the panel edge the hero art shows sharp grain/texture; the same dark streak continues **below** the rounded edge as a diffuse, smoothed blob under the gold tint — genuine backdrop blur, not a scrim fallback (device is API 36 ≥ blur API; no fallback path engaged). **PASS**

### 4. Route transitions
- Home → Browse ("Browse all ›"), mid-frame captured: Browse grid sliding up + fading in over the outgoing shelf, hero layer rock-steady, no flicker.
- Browse → Settings (gear), mid-frame `img/m5a4-route-midtransition.png`: Emulators screen sliding in from the right over outgoing Browse, both legible mid-fade, no flash of empty panel.
- Settings "Back" → Browse, system back → Home — both smooth; hero/panel never re-composed visibly. **PASS**

### 5. Card press scale + selection glow
- Held a press on the Wind Waker card (capture mid-hold at ~450ms): card visibly shrunk (~0.96 scale) inside its grid slot.
- Selection (`img/m5a4-selection-glow.png`): selected card carries the animated primary-colored border + soft elevation glow; unselected cards keep the 1dp white glass border. List-mode selection shows the glow containerColor row highlight. **PASS**

### 6. Glass tabs + search styling
Tabs are translucent white pills (0.07/0.18 alpha) with 1dp glass borders; the active tab carries the ambient tint under its white wash (visibly gold in the 007 state, teal in Wind Waker state). Search field renders with translucent container + glass border on the blurred panel. Tab switch crossfades the grid region — mid-fade captured (`img/m5a4-tab-midfade.png`): outgoing GameCube card and incoming N64 grid blended. Note (accepted design point): each tab composes its own scroll state, so scroll position resets to top on tab switch — previous behavior leaked one tab's scroll offset into another, this is strictly better. **PASS**

### 7. Functional regression sweep
| Flow | Result |
|---|---|
| Launch (re-tap selected Star Fox 64) | `topResumedActivity=…dev.mimir.fakeemulator/.CatchActivity`, intent URI `…Roms%2Fn64%2FStar%20Fox%2064.z64` — **PASS** |
| Long-press sheet | "Play \"Wind Waker\" with…" — Dolphin (not installed) / Mimir Fake Emulator listed — **PASS** |
| Search | "star" filters 4 → Star Fox 64 only — **PASS** |
| List toggle | ≡ List → glass rows with per-row wash, ⊞ Grid restores — **PASS** |
| Scan report | Overflow → "Scan report (2 skipped)" → slides in with both skip reasons; play-stamp pill updated to "Last played 6/12/26" after launch — **PASS** |
| Backup export | Overflow → SAF → saved `mimir-backup (1).zip` (1,850 B), unzipped: 6 valid JSON entries (media 5433 B / platform_prefs / game_prefs / play_state / custom_players / theme) — **PASS** |
| Gear / overflow | Settings opens via ⚙; all 6 overflow items present and wired — **PASS** |

### 8. Logcat clean
Session dump since launch: `FATAL EXCEPTION` 0 · `AndroidRuntime.*dev.mimir` 0 · app-tagged exceptions 0. One `Choreographer: Skipped 30 frames` at 09:27:23 — cold start (first composition + library load), before any transition/blur work; zero frame-skip or Davey lines during the entire interaction session (crossfades, route transitions, blur all active). Remaining E-lines are the known benign emulator noise (Codec2 "system resources: 6" at startup, AppOps attributionTag from SAF). **PASS**

### Evidence files
- `img/m5a4-layered.png` — flagship layered shot: full-bleed Star Fox hero, floating logo+pills, glass tabs/search, grid on glass (also the new `assets/browse.png`)
- `img/m5a4-hero-before.png` / `img/m5a4-hero-midfade.png` / `img/m5a4-hero-after.png` — selection crossfade + gold ambient re-tint sequence
- `img/m5a4-blur-crop.png` — 2× boundary crop, blur-through-glass evidence
- `img/m5a4-route-midtransition.png` — Settings sliding in over Browse mid-transition
- `img/m5a4-selection-glow.png` — selected card border/glow + Wind Waker teal ambient
- `img/m5a4-tab-midfade.png` — tab-switch crossfade mid-fade

### Known nits (non-blocking)
- Panel content has no `navigationBarsPadding`; on gesture-nav the pill overlaps the last row's final pixels when scrolled to the very bottom. Invisible on the Thor's button layout; revisit in M5b insets work if needed.
- With `contentWindowInsets = WindowInsets(0)`, snackbars sit flush to the screen bottom (over the gesture area). Cosmetic.
