# Mimir M5a-3a — Shell Part 1: Browse + HeroPane Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The new face, part 1: a HeroPane (full-bleed hero art + SGDB logo + glass metadata pills) above a BrowseScreen (per-system cover grid + system tabs + alphabet rail + list toggle + search), select-then-launch interaction, ambient palette following the SELECTED game, and the crowded header replaced by a gear + overflow menu. Single-screen vertical stack per spec §3.6 (M5b moves HeroPane to the Thor's top display).

**Interaction model (from the approved mockups):** tapping a game SELECTS it (HeroPane + ambient update, selection ring); tapping the selected game again LAUNCHES it; long-press keeps the Play-with sheet. Alphabet rail appears only when the active system has > 24 games; tab row appears when > 1 system.

**Conventions:** Repo `~/Development/mimir`, branch from `master` (v0.8.0-m5a2, 80 tests). JAVA_HOME export as always. Co-Authored-By trailer. Spec: docs/superpowers/specs/2026-06-11-m5-theme-system-design.md §3.3.

---

### Task 1: Browse pure logic (TDD, app unit tests)

**Files:** `app/src/main/kotlin/dev/mimir/app/BrowseLogic.kt`, test `app/src/test/kotlin/dev/mimir/app/BrowseLogicTest.kt`

- [ ] **Step 1: Failing tests** — `BrowseLogicTest.kt`:
```kotlin
package dev.mimir.app

import kotlin.test.Test
import kotlin.test.assertEquals

class BrowseLogicTest {
    @Test
    fun `alpha sections map first letters to first index, digits and symbols bucket to hash`() {
        val titles = listOf("1080 Snowboarding", "Banjo", "banjo 2", "GoldenEye", "Zelda")
        val sections = BrowseLogic.alphaSections(titles)
        assertEquals(0, sections["#"])
        assertEquals(1, sections["B"])
        assertEquals(3, sections["G"])
        assertEquals(4, sections["Z"])
        assertEquals(null, sections["A"])
    }

    @Test
    fun `search filter is case and punctuation insensitive`() {
        val titles = listOf("GoldenEye 007", "Mario Kart 64", "Banjo-Kazooie")
        assertEquals(listOf("Banjo-Kazooie"), BrowseLogic.filter(titles, "banjo kaz"))
        assertEquals(listOf("GoldenEye 007"), BrowseLogic.filter(titles, "golden eye").ifEmpty { BrowseLogic.filter(titles, "goldeneye") })
        assertEquals(titles, BrowseLogic.filter(titles, ""))
    }

    @Test
    fun `rail letters are the fixed a-z plus hash`() {
        assertEquals(27, BrowseLogic.RAIL.size)
        assertEquals("#", BrowseLogic.RAIL.first())
        assertEquals("Z", BrowseLogic.RAIL.last())
    }
}
```
Run `:app:testDebugUnitTest` → FAIL.
- [ ] **Step 2: Implement** `BrowseLogic.kt`:
```kotlin
package dev.mimir.app

/** Pure helpers behind the browse screen: alphabet rail indexing and search filtering. */
object BrowseLogic {
    val RAIL: List<String> = listOf("#") + ('A'..'Z').map { it.toString() }

    /** Maps each rail letter to the first index in [sortedTitles] starting with it (digits/symbols -> "#"). */
    fun alphaSections(sortedTitles: List<String>): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        sortedTitles.forEachIndexed { index, title ->
            val first = title.firstOrNull()?.uppercaseChar()
            val key = if (first != null && first in 'A'..'Z') first.toString() else "#"
            out.putIfAbsent(key, index)
        }
        return out
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("""[^a-z0-9]+"""), "")

    /** Case/punctuation-insensitive contains filter; blank query returns everything. */
    fun filter(titles: List<String>, query: String): List<String> {
        if (query.isBlank()) return titles
        val q = norm(query)
        return titles.filter { q in norm(it) }
    }
}
```
Run → PASS (app = 4). Commit `feat(app): browse logic — alpha rail sections and search filter`.

---

### Task 2: ViewModel — selection, focus-driven ambient, browse state

**Files:** `app/src/main/kotlin/dev/mimir/app/MainViewModel.kt`

- [ ] **Step 1:** Add selection state (after the prefs/flows, before `state`):
```kotlin
    private val selectedGameUri = MutableStateFlow<String?>(null)

    fun selectGame(game: GameEntity) { selectedGameUri.value = game.uri }

    /** Tap behavior: first tap selects, second tap on the same game launches. */
    fun onGameTapped(game: GameEntity) {
        if (selectedGameUri.value == game.uri) launchGame(game) else selectGame(game)
    }
```
- [ ] **Step 2:** Rework `ambient` to follow selection with last-played fallback, and expose hero/logo art. Replace the existing `ambient` property with:
```kotlin
    /** uri of the game driving the hero pane: selected, else most recently played. */
    private val focusUri: Flow<String?> =
        combine(selectedGameUri, repo.playStates) { selected, plays ->
            selected ?: plays.maxByOrNull { it.lastPlayedAt }?.gameUri
        }

    data class HeroArt(val game: GameEntity?, val heroUrl: String?, val logoUrl: String?, val boxartUrl: String?, val lastPlayedAt: Long?)

    val heroArt: StateFlow<HeroArt> =
        combine(focusUri, repo.games, repo.media, repo.playStates) { uri, games, media, plays ->
            val game = games.firstOrNull { it.uri == uri }
            HeroArt(
                game = game,
                heroUrl = media.firstOrNull { it.gameUri == uri && it.kind == "hero" }?.boxartUrl,
                logoUrl = media.firstOrNull { it.gameUri == uri && it.kind == "logo" }?.boxartUrl,
                boxartUrl = media.firstOrNull { it.gameUri == uri && it.kind == "boxart" }?.boxartUrl,
                lastPlayedAt = plays.firstOrNull { it.gameUri == uri }?.lastPlayedAt,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, HeroArt(null, null, null, null, null))

    val ambient: StateFlow<AmbientPalette> =
        heroArt.map { hero ->
            if (themeConfig.value.paletteMode == PaletteMode.FIXED)
                AmbientPalette.from(themeConfig.value.fixedSeedArgb)
            else paletteExtractor.extract(hero.heroUrl ?: hero.boxartUrl)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, AmbientPalette.from(AmbientPalette.FALLBACK_ARGB))
```
(Declaration order: heroArt/ambient AFTER repo/themeConfig/paletteExtractor — same init-order rule as before.)
- [ ] **Step 3:** Expose a resolved-emulator-name helper for the pills:
```kotlin
    fun resolvedEmulatorName(game: GameEntity): String =
        resolver(prefsState.value).resolve(game.uri, game.platformId)?.name ?: "none"
```
And expose `platformNames` lookup: `fun platformName(id: String): String = platformNames[id] ?: id`.
- [ ] **Step 4:** Compile check (`:app:compileDebugKotlin`) — MainActivity may still reference the old ambient only; expect SUCCESS (ambient kept same name/type). No commit yet (Tasks 2+3+4 commit together).

---

### Task 3: HeroPane composable

**Files:** Create `app/src/main/kotlin/dev/mimir/app/HeroPane.kt`

- [ ] **Step 1:**
```kotlin
package dev.mimir.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.mimir.theme.LocalMimirTheme
import java.text.DateFormat
import java.util.Date

/** Full-bleed showcase for the focused game: hero art, logo, glass metadata pills. */
@Composable
fun HeroPane(
    hero: MainViewModel.HeroArt,
    platformName: (String) -> String,
    emulatorName: (dev.mimir.data.GameEntity) -> String,
    modifier: Modifier = Modifier,
) {
    val theme = LocalMimirTheme.current
    Box(modifier.fillMaxWidth().background(theme.scrim)) {
        val art = hero.heroUrl ?: hero.boxartUrl
        if (art != null) {
            AsyncImage(
                model = art,
                contentDescription = hero.game?.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(0.92f),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Transparent, 0.55f to Color.Transparent, 1f to theme.scrim)
            )
        )
        val game = hero.game
        if (game == null) {
            Text(
                "Mimir",
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                if (hero.logoUrl != null) {
                    AsyncImage(
                        model = hero.logoUrl,
                        contentDescription = game.title,
                        modifier = Modifier.heightIn(max = 64.dp).widthIn(max = 280.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(
                        game.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassPill(platformName(game.platformId))
                    GlassPill("▶ ${emulatorName(game)}")
                    hero.lastPlayedAt?.let {
                        GlassPill("Last played ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it))}")
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassPill(text: String) {
    Surface(
        color = Color.White.copy(alpha = 0.12f),
        contentColor = Color.White.copy(alpha = 0.92f),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
    }
}
```
(Plain `Date`/`DateFormat` — no extra deps. No commit yet.)

---

### Task 4: BrowseScreen + MainActivity restructure

**Files:** Create `app/src/main/kotlin/dev/mimir/app/BrowseScreen.kt`; modify `MainActivity.kt`

- [ ] **Step 1: `BrowseScreen.kt`:**
```kotlin
package dev.mimir.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.mimir.data.GameEntity
import dev.mimir.theme.LocalMimirTheme
import kotlinx.coroutines.launch

private const val RAIL_THRESHOLD = 24

@Composable
fun BrowseScreen(
    library: UiState.Library,
    selectedUri: String?,
    onGameTap: (GameEntity) -> Unit,
    onGameLongPress: (GameEntity) -> Unit,
) {
    val systems = library.gamesByPlatform.keys.toList()
    var activeSystem by rememberSaveable(systems) { mutableStateOf(systems.firstOrNull() ?: "") }
    var listMode by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val theme = LocalMimirTheme.current

    val allGames = library.gamesByPlatform[activeSystem].orEmpty()
    val visibleTitles = BrowseLogic.filter(allGames.map { it.title }, query).toSet()
    val games = allGames.filter { it.title in visibleTitles }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val sections = remember(games) { BrowseLogic.alphaSections(games.map { it.title }) }

    Column(Modifier.fillMaxSize()) {
        if (systems.size > 1) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LazyColumn { } // placeholder removed — see horizontal row below
            }
        }
        // System tabs (horizontal scroll when many)
        androidx.compose.foundation.lazy.LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(systems, key = { it }) { system ->
                val on = system == activeSystem
                Surface(
                    color = if (on) theme.glow else Color.White.copy(alpha = 0.05f),
                    contentColor = if (on) Color.White else Color.White.copy(alpha = 0.6f),
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.clickable { activeSystem = system },
                ) {
                    Text(
                        "$system  ·  ${library.gamesByPlatform[system].orEmpty().size}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        // Search + view toggle row
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search ${allGames.size} games") },
                singleLine = true,
                modifier = Modifier.weight(1f).heightIn(max = 56.dp),
            )
            TextButton(onClick = { listMode = !listMode }) { Text(if (listMode) "⊞ Grid" else "≡ List") }
        }
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                if (listMode) {
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                        items(games, key = { it.uri }) { game ->
                            ListItem(
                                headlineContent = { Text(game.title) },
                                supportingContent = { Text(library.art[game.uri]?.let { "" } ?: "no art", style = MaterialTheme.typography.labelSmall) },
                                leadingContent = {
                                    coil3.compose.AsyncImage(
                                        model = library.art[game.uri], contentDescription = null,
                                        modifier = Modifier.size(width = 34.dp, height = 46.dp),
                                    )
                                },
                                modifier = Modifier
                                    .clickable { onGameTap(game) }
                                    .background(if (game.uri == selectedUri) theme.glow else Color.Transparent),
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 120.dp),
                        state = gridState,
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(games, key = { it.uri }) { game ->
                            GameCard(
                                game = game,
                                artUrl = library.art[game.uri],
                                selected = game.uri == selectedUri,
                                onClick = { onGameTap(game) },
                                onLongClick = { onGameLongPress(game) },
                            )
                        }
                    }
                }
            }
            if (!listMode && games.size > RAIL_THRESHOLD) {
                AlphaRail(
                    sections = sections,
                    onJump = { index -> scope.launch { gridState.scrollToItem(index) } },
                )
            }
        }
    }
}

@Composable
private fun AlphaRail(sections: Map<String, Int>, onJump: (Int) -> Unit) {
    val theme = LocalMimirTheme.current
    var railHeightPx by remember { mutableStateOf(1f) }
    Column(
        Modifier
            .fillMaxHeight()
            .width(22.dp)
            .padding(vertical = 8.dp)
            .onSizeChangedCompat { railHeightPx = it }
            .pointerInput(sections) {
                detectVerticalDragGestures { change, _ ->
                    val fraction = (change.position.y / railHeightPx).coerceIn(0f, 0.999f)
                    val letter = BrowseLogic.RAIL[(fraction * BrowseLogic.RAIL.size).toInt()]
                    sections[letter]?.let(onJump)
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        for (letter in BrowseLogic.RAIL) {
            Text(
                letter,
                style = MaterialTheme.typography.labelSmall,
                color = if (letter in sections) theme.primary else Color.White.copy(alpha = 0.25f),
                modifier = Modifier.clickable(enabled = letter in sections) { sections[letter]?.let(onJump) },
            )
        }
    }
}

/** Small compat shim: capture height in px without pulling extra APIs. */
private fun Modifier.onSizeChangedCompat(onHeight: (Float) -> Unit): Modifier =
    androidx.compose.ui.layout.onGloballyPositioned.let { _ ->
        this.then(Modifier).let { m -> m } // implementer: replace with Modifier.onGloballyPositioned { onHeight(it.size.height.toFloat()) }
    }
```
**Implementer note:** the `onSizeChangedCompat` stub at the bottom is deliberately wrong-as-written — replace the whole helper with a direct `Modifier.onGloballyPositioned { railHeightPx = it.size.height.toFloat() }` at the call site (import `androidx.compose.ui.layout.onGloballyPositioned`) and DELETE the helper. Also remove the stray empty `if (systems.size > 1) { ... LazyColumn{} placeholder ... }` block — the LazyRow below it is the real tab row; gate THAT row with `if (systems.size > 1)`. (These two cleanups are part of the task, called out so they're not transcribed literally.) `GameCard` gains a `selected: Boolean` param rendering a 2dp `theme.primary` border (`Modifier.border(2.dp, theme.primary, shape)`) when selected — update its existing definition and call sites.

- [ ] **Step 2: MainActivity restructure.** Replace `LibraryGrid` usage: the `is UiState.Library` branch becomes a `Column`: `HeroPane(hero, ..., modifier = Modifier.weight(0.4f))` over a content `Box(Modifier.weight(0.6f))` hosting `when { showReport -> ScanReportScreen(...); showSettings -> EmulatorSettingsScreen(...); else -> BrowseScreen(library, selectedUri, viewModel::onGameTapped, { sheetGame = it }) }`. Above/overlaying the hero, top-right: an icon-ish row — `TextButton("⚙")` → showSettings, and an overflow `Box` with `DropdownMenu` items: Rescan / Fetch artwork / Import ES-DE / Scan report (N skipped) / Change ROM folder — moving ALL the old header buttons there. Keep scanning `LinearProgressIndicator` and the error banner ABOVE the browse content (inside the 0.6 pane, top). Collect `val heroState by viewModel.heroArt.collectAsState()` and `selectedUri` via `heroState.game?.uri`... NO — selection ring needs the raw selection; add `val selected by viewModel...` — expose `selectedGameUri` as `val selectedUri: StateFlow<String?>` in the VM (rename the private field accessor: keep private set, expose `val selectedUriState: StateFlow<String?> = selectedGameUri`). NeedsFolder/Error branches unchanged. Delete the now-unused `LibraryGrid` composable.
- [ ] **Step 3:** Build + tests: `./gradlew :app:assembleDebug test` → 83 tests (app 4 incl. RegistryConsistency... recount: app = 1 existing + 3 BrowseLogic = 4; total 83). Expected: **83 tests**, 0 failures. Commit Tasks 2+3+4: `feat(app): hero pane and browse shell — select-to-focus, ambient follows selection`.

---

### Task 5: E2E + docs

- [ ] **Step 1: E2E** (Pixel_10_Pro; leave running): install over v0.8.0; verify: hero pane shows last-played game's HERO art (SGDB heroes exist from M5a-2) + logo + pills incl. emulator name; tap a DIFFERENT game once → selection ring + hero/ambient switch to it (screencap before/after, READ both); tap the SAME game again → launches (fake emulator); long-press still opens Play-with sheet; system tabs switch platforms; search filters; list toggle works; rail hidden (only 5 games — verify by absence per RAIL_THRESHOLD), overflow menu contains the 5 actions and they work (run Rescan from it); settings/report reachable; logcat clean. Screenshots: `img/m5a3a-hero.png`, `img/m5a3a-selected.png`. Evidence → `m5a3a-verification.md`. Commit.
- [ ] **Step 2:** README status: `**Status: M5a-3a — the new shell, part 1.** Hero showcase (SGDB hero art + logo + glass metadata) over a scalable browse: system tabs, search, grid/list toggle, alphabet rail for big libraries, select-to-focus with ambient color following your selection. Next: home shelf, sound + haptics (M5a-3b), then the dual-screen deck on Thor hardware (M5b).` versionCode 9 / `0.9.0-m5a3a`. Final --rerun-tasks 83. Commit.

## Out of scope (M5a-3b)
HomeScreen shelf, sound/haptic engine, backup export, wallpapers, L1/R1 hardware keys (Thor), settings reskin beyond what exists.
