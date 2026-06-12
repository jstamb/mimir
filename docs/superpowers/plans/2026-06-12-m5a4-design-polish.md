# Mimir M5a-4 — Design Polish: Layered Layout, Glass, Motion

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Jordan's design feedback made concrete: on a single screen, hero art becomes the FULL-SCREEN background (Cocoon's layered formatting — logo on top, grid floating on a glass panel over the lower screen), glass surfaces get real backdrop blur (Haze), and the UI moves — route transitions, hero crossfades, card press/selection animation.

**Layering (single-screen; M5b will split layers across two displays):**
```
Box(fillMaxSize)
├── L1  Hero art background — Crossfade(artUrl) { AsyncImage Crop } + Modifier.hazeSource(hazeState)
├── L2  Scrims — top: statusbar protection; bottom 65%: vertical fade to theme.scrim
├── L3  HeroOverlay — game logo (or styled title) + glass pills, anchored ~bottom of top 38%
├── L4  Glass content panel — bottom ~62%, RoundedCornerShape(top 28dp), Modifier.hazeEffect(hazeState)
│       └── AnimatedContent(route) { Home | Browse | Settings | Report }
└── L5  Top-right controls (gear, overflow) — unchanged
```
`HeroPane` (full version with its own art) is KEPT intact for M5b's top display; single-screen now uses L1+L3 instead. Extract the logo+pills block into `HeroOverlay(hero, platformName, emulatorName)` shared by both.

**Design latitude:** this is a polish milestone — exact dp/alpha/duration values in this plan are starting points; the implementer may tune within taste (report tunings). Structure, layering, and API choices are binding.

**Conventions:** Repo `~/Local Sites/mimir`, branch from `main` (v1.0.0-beta1, 84 tests, PUBLIC repo — pushes go to github.com/jstamb/mimir). JAVA_HOME export. Co-Authored-By trailer.

---

### Task 1: Haze + layered layout restructure

**Files:** `gradle/libs.versions.toml`, `core/theme/build.gradle.kts` or app-only (decide: app-only — glass is a shell concern), `app/build.gradle.kts`, `app/src/main/kotlin/dev/mimir/app/{MainActivity,HeroPane}.kt`

- [ ] **Step 1:** Resolve the latest STABLE Haze from https://github.com/chrisbanes/haze/releases (2.x current as of May 2026; if 2.x stable exists use it + the `haze-blur` module and `blurEffect {}` syntax; if only 1.x is stable use it with `hazeSource`/`hazeEffect` + HazeStyle). Catalog:
```toml
haze = "<resolved>"
haze = { group = "dev.chrisbanes.haze", name = "haze", version.ref = "haze" }
# plus haze-blur if 2.x
```
`app/build.gradle.kts`: implementation(libs.haze) (+ blur module if 2.x). Report the resolved version + which API form.
- [ ] **Step 2:** Extract `HeroOverlay` from HeroPane's logo+pills block (HeroPane keeps using it internally — zero behavior change to HeroPane itself).
- [ ] **Step 3:** MainActivity Library branch → the 5-layer Box above. Key skeleton:
```kotlin
val hazeState = remember { HazeState() }
Box(Modifier.fillMaxSize()) {
    Crossfade(targetState = heroState.heroUrl ?: heroState.boxartUrl, animationSpec = tween(600), label = "heroArt") { art ->
        if (art != null) AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().hazeSource(hazeState))
        else Box(Modifier.fillMaxSize().background(theme.scrim).hazeSource(hazeState))
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
        0f to Color.Black.copy(alpha = 0.35f), 0.18f to Color.Transparent,
        0.40f to Color.Transparent, 0.95f to theme.scrim)))
    HeroOverlay(heroState, viewModel::platformName, viewModel::resolvedEmulatorName,
        modifier = Modifier.align(Alignment.TopStart).padding(top = 0.dp).fillMaxHeight(0.38f))
    Surface( // L4 glass panel
        color = Color.Transparent,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxHeight(0.62f).fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .hazeEffect(hazeState) { /* 2.x: blurEffect { blurRadius = 24.dp }; tint theme.glow at low alpha */ },
    ) {
        AnimatedContent(targetState = route, transitionSpec = {
            (slideInVertically { it / 8 } + fadeIn(tween(250))) togetherWith fadeOut(tween(150))
        }, label = "route") { r -> /* existing when(r) content */ }
    }
    /* L5: existing gear/overflow row, top-right */
}
```
(HeroOverlay anchors its own content to ITS bottom-start so logo+pills sit just above the glass panel's top edge. The hazeEffect lambda/params differ between Haze 1.x and 2.x — use whichever matches the resolved version; fallback styling (no blur support < API 31/32) is handled by Haze's scrim fallback + our tint.) NeedsFolder/Error branches: keep full-screen but give them the ambient gradient background for consistency.
- [ ] **Step 4:** Build + visually sanity-check via screenshot on the emulator (install, screencap, READ it: background art fills screen, glass panel rounded top, logo above panel). This task alone should already look dramatically different. Tests still 84. Commit `feat(app): layered single-screen layout — full-bleed hero, glass content panel`.

---

### Task 2: Glass components + card polish

**Files:** `app/src/main/kotlin/dev/mimir/app/{BrowseScreen,MainActivity,HomeScreen}.kt`

- [ ] **Step 1:** System tabs + search field + list rows: restyle onto glass — tabs become `Surface(color = Color.White.copy(alpha = if (on) 0.18f else 0.07f), border = BorderStroke(1.dp, Color.White.copy(alpha = if (on) 0.35f else 0.10f)))` with the active tab additionally tinted `theme.glow`; search field uses `OutlinedTextFieldDefaults.colors` with translucent container. (These sit ON the blurred panel — translucency over blur reads as glass without nested hazeEffect cost.)
- [ ] **Step 2:** GameCard polish:
  - 1.dp glass border always (`Color.White.copy(alpha = 0.14f)`), selection animates: `val borderColor by animateColorAsState(if (selected) theme.primary else Color.White.copy(alpha = 0.14f), tween(250))` + `val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring())` with `interactionSource.collectIsPressedAsState()`, applied via `Modifier.graphicsLayer { scaleX = scale; scaleY = scale }`.
  - Selected card gets a soft glow: `Modifier.shadow(elevation = if (selected) 16.dp else 2.dp, shape = shape, ambientColor = theme.primary, spotColor = theme.primary)` (animate elevation with animateDpAsState).
- [ ] **Step 3:** Home shelf cards get the same press-scale + glass border; section labels stay. Grid/shelf items gain `Modifier.animateItem()` where the Lazy APIs support it (grid item placement animation on filter/sort changes; if the API isn't available on this Compose version for grids, report and skip gracefully).
- [ ] **Step 4:** Build, screenshot sanity check, tests 84. Commit `feat(app): glass component styling with animated selection and press states`.

---

### Task 3: Motion pass

**Files:** `app/src/main/kotlin/dev/mimir/app/{MainActivity,HeroPane,HomeScreen,BrowseScreen}.kt`

- [ ] **Step 1:** HeroOverlay content animates on game change: wrap logo+pills in `AnimatedContent(targetState = hero.game?.uri, transitionSpec = { (fadeIn(tween(350)) + slideInVertically { it / 6 }) togetherWith fadeOut(tween(150)) })`.
- [ ] **Step 2:** Route transitions already added (Task 1) — extend: SETTINGS/REPORT slide in from the right (`slideInHorizontally { it / 6 } + fadeIn`), HOME↔BROWSE crossfade+slide-up. Use `AnimatedContent`'s targetState to branch transitionSpec by route pair.
- [ ] **Step 3:** Tab switch: content inside Browse animates — wrap the grid/list region in `Crossfade(targetState = activeSystem, animationSpec = tween(200))` (cheap, no layout thrash).
- [ ] **Step 4:** Build, tests 84, screenshot. Commit `feat(app): motion pass — hero, route, and tab transitions`.

---

### Task 4: E2E + docs + publish

- [ ] **Step 1: E2E** (Pixel_10_Pro; leave running): install over beta1; verify visually with screencaps (READ each): (1) full-bleed hero art background with glass panel + rounded top + logo floating above it (`img/m5a4-layered.png`); (2) select a different game → hero background crossfades + logo animates + ambient re-tints (before/after caps); (3) blur present behind panel (zoom a crop where art meets panel — blurred art visible through glass; if device < blur API, scrim fallback acceptable — note which); (4) route transition Home→Browse→Settings smooth, no flicker (cap mid-transition if possible, else note); (5) card press scale + selection glow visible; (6) tabs/search glass styling; (7) functional regression sweep: launch, long-press sheet, search, list toggle, scan report, backup export still work; (8) logcat clean + no jank warnings (`gfxinfo` optional). Evidence `m5a4-verification.md` + screenshots. Commit.
- [ ] **Step 2:** README: refresh `assets/browse.png` with the new layered screenshot (m5a4-layered) — the README screenshots should show the new design (home.png refresh waits on Jordan's real ROMs; swap browse now since it's the hero shot). Version `versionCode 11` / `1.0.0-beta2`. Final --rerun-tasks 84. Commit. Then merge to main and `git push` (repo is public — this ships the new look).
