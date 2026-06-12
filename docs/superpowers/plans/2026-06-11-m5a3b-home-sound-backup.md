# Mimir M5a-3b — Shell Part 2: Home, Sound, Backup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish M5a: the continue-playing HomeScreen (default route), the sound/haptic engine with a generated default sound set, the "Back up art & themes" export, and the SGDB exact-match fix (GoldenEye: Source bug).

**Conventions:** Repo `~/Local Sites/mimir`, branch from `master` (v0.9.0-m5a3a, 83 tests). JAVA_HOME export as always. Co-Authored-By trailer. Spec §3.3 (Home), §3.5 (sound), §2.4 (backup).

---

### Task 1: SgdbClient exact-match preference (TDD)

**Files:** `core/scraper/src/main/kotlin/dev/mimir/scraper/SgdbClient.kt`, test `SgdbClientTest.kt`

- [ ] **Step 1: Failing test** (add to SgdbClientTest — extend the existing fake fetcher's search response):

Change the fake fetcher's autocomplete response for "GoldenEye%20007" (add a new test fetcher locally in the test):
```kotlin
    @Test
    fun `prefers exact normalized name over first hit, then shortest startsWith`() {
        fun clientWithHits(hitsJson: String) = SgdbClient("k", fetcher = { url, _ ->
            when {
                "search/autocomplete/" in url -> """{"success":true,"data":$hitsJson}"""
                "grids/game/7" in url -> """{"success":true,"data":[{"id":1,"url":"https://cdn/right.png"}]}"""
                else -> """{"success":true,"data":[]}"""
            }
        })
        // exact normalized match wins over earlier hits
        val exact = clientWithHits("""[{"id":5,"name":"GoldenEye: Source"},{"id":7,"name":"GoldenEye 007"}]""")
        assertEquals("https://cdn/right.png", exact.artFor("GoldenEye 007")?.gridUrl)
        // no exact: shortest normalized startsWith wins ("GoldenEye" -> 007 beats Source)
        val starts = clientWithHits("""[{"id":5,"name":"GoldenEye: Source"},{"id":7,"name":"GoldenEye 007"}]""")
        assertEquals("https://cdn/right.png", starts.artFor("GoldenEye")?.gridUrl)
        // nothing related: falls back to first hit (id 5 -> no grids -> null gridUrl but non-null result)
        val fallback = clientWithHits("""[{"id":5,"name":"Something Else"}]""")
        assertEquals(null, fallback.artFor("GoldenEye")?.gridUrl)
    }
```
Run → FAIL (current code picks first hit; "exact" case fetches grids/game/5 → empty → null gridUrl).
- [ ] **Step 2: Fix** — in `SgdbClient.artFor`, replace `val gameId = search.data.firstOrNull()?.id ?: return null` with:
```kotlin
        val wanted = normalize(title)
        val gameId = (
            search.data.firstOrNull { normalize(it.name) == wanted }
                ?: search.data.filter { normalize(it.name).startsWith(wanted) }.minByOrNull { it.name.length }
                ?: search.data.firstOrNull()
            )?.id ?: return null
```
with a private helper (same normalize semantics as the other matchers):
```kotlin
    private fun normalize(s: String): String = s.lowercase().replace(Regex("""[^a-z0-9]+"""), " ").trim()
```
Run → PASS (scraper 24). Commit `fix(scraper): SGDB picks exact-name match before first autocomplete hit`.

---

### Task 2: Recents + route plumbing in the ViewModel

**Files:** `app/src/main/kotlin/dev/mimir/app/MainViewModel.kt`

- [ ] **Step 1:** Add (after heroArt/ambient, respecting declaration order):
```kotlin
    data class RecentEntry(val game: GameEntity, val artUrl: String?, val lastPlayedAt: Long)

    val recents: StateFlow<List<RecentEntry>> =
        combine(repo.playStates, repo.games, repo.media) { plays, games, media ->
            plays.sortedByDescending { it.lastPlayedAt }
                .mapNotNull { play ->
                    games.firstOrNull { it.uri == play.gameUri }?.let { game ->
                        RecentEntry(
                            game = game,
                            artUrl = media.firstOrNull { it.gameUri == game.uri && it.kind == "boxart" }?.boxartUrl,
                            lastPlayedAt = play.lastPlayedAt,
                        )
                    }
                }
                .take(8)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** System display names with counts for the home system row, sorted. */
    fun systemRow(library: UiState.Library): List<Pair<String, Int>> =
        library.gamesByPlatform.map { (name, games) -> name to games.size }
```
- [ ] **Step 2:** Compile check. No commit yet.

---

### Task 3: HomeScreen + route restructure

**Files:** Create `app/src/main/kotlin/dev/mimir/app/HomeScreen.kt`; modify `MainActivity.kt`

- [ ] **Step 1: `HomeScreen.kt`:**
```kotlin
package dev.mimir.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.mimir.data.GameEntity
import dev.mimir.theme.LocalMimirTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    recents: List<MainViewModel.RecentEntry>,
    systems: List<Pair<String, Int>>, // display name to count
    onPlay: (GameEntity) -> Unit,
    onGameLongPress: (GameEntity) -> Unit,
    onOpenSystem: (String) -> Unit,
    onBrowseAll: () -> Unit,
) {
    val theme = LocalMimirTheme.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        SectionLabel("Continue playing")
        if (recents.isEmpty()) {
            Text(
                "Play something and it'll show up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(recents, key = { it.game.uri }) { entry ->
                    Card(
                        Modifier
                            .size(width = 132.dp, height = 178.dp)
                            .combinedClickable(onClick = { onPlay(entry.game) }, onLongClick = { onGameLongPress(entry.game) }),
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            if (entry.artUrl != null) {
                                AsyncImage(
                                    model = entry.artUrl, contentDescription = entry.game.title,
                                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Box(
                                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    entry.game.title, style = MaterialTheme.typography.labelMedium,
                                    color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            SectionLabel("Systems", modifier = Modifier.weight(1f))
            Text(
                "Browse all ›",
                style = MaterialTheme.typography.labelMedium,
                color = theme.primary,
                modifier = Modifier.clickable(onClick = onBrowseAll).padding(horizontal = 16.dp),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(systems, key = { it.first }) { (name, count) ->
                Surface(
                    color = Color.White.copy(alpha = 0.06f),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.clickable { onOpenSystem(name) },
                ) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(name, style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.9f))
                        Text(
                            "$count ${if (count == 1) "game" else "games"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.45f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = Color.White.copy(alpha = 0.55f),
        letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}
```
(Shelf tap = LAUNCH directly — it's "continue playing", Switch semantics; long-press = sheet. Browse keeps select-then-launch.)
- [ ] **Step 2: MainActivity routes.** Replace `showReport`/`showSettings` booleans with:
```kotlin
    enum class Route { HOME, BROWSE, SETTINGS, REPORT }
```
(file-level, in MainActivity.kt) and in MainScreen: `var route by rememberSaveable { mutableStateOf(Route.HOME) }` plus `var browseSystem by rememberSaveable { mutableStateOf<String?>(null) }`. The Library branch's content `when (route)`:
- HOME → `HomeScreen(recents, viewModel.systemRow(s), onPlay = viewModel::launchGame, onGameLongPress = { sheetGame = it }, onOpenSystem = { browseSystem = it; route = Route.BROWSE }, onBrowseAll = { browseSystem = null; route = Route.BROWSE })` (collect `val recents by viewModel.recents.collectAsState()`).
- BROWSE → existing BrowseScreen + new `initialSystem = browseSystem` param: in BrowseScreen change `var activeSystem by rememberSaveable(systems) { mutableStateOf(systems.firstOrNull() ?: "") }` to seed from a new `initialSystem: String?` parameter (`mutableStateOf(initialSystem ?: systems.firstOrNull() ?: "")`).
- SETTINGS/REPORT → existing screens with `onBack = { route = Route.HOME }`... keep back-to-previous simple: both return to BROWSE if they were opened from browse? Keep simple: onBack always → Route.HOME if recents nonempty else BROWSE. Simpler still and acceptable: onBack → Route.BROWSE. Use that, consistently.
- Gear button → `route = Route.SETTINGS`; overflow "Scan report" → `route = Route.REPORT`.
- Add `BackHandler(enabled = route != Route.HOME) { route = if (route == Route.BROWSE) Route.HOME else Route.BROWSE }` (import `androidx.activity.compose.BackHandler`).
- NeedsFolder/Error full-screen branches unchanged.
- [ ] **Step 3:** Build + tests → 84 tests (scraper 24). Commit Tasks 2+3: `feat(app): continue-playing home shelf with system row as the default route`.

---

### Task 4: Sound + haptic engine

**Files:** Create `scripts/generate_sounds.py`, `app/src/main/res/raw/{nav_tick,select,launch,back}.wav`, `app/src/main/kotlin/dev/mimir/app/SoundEngine.kt`; modify `MimirApp.kt`, `MainViewModel.kt`, `MainActivity.kt`

- [ ] **Step 1: Generate the default sound set** — `scripts/generate_sounds.py` (host python3 stdlib only — wave+math+struct; pyexpat breakage irrelevant):
```python
#!/usr/bin/env python3
"""Generates Mimir's default UI sounds: short clean sine blips with exp decay. 44.1kHz mono 16-bit WAV."""
import math, struct, wave, os

SR = 44100
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")

def tone(path, freqs, ms, gain=0.5, sweep=False):
    n = int(SR * ms / 1000)
    frames = bytearray()
    for i in range(n):
        t = i / SR
        env = math.exp(-6.0 * i / n)                      # exponential decay
        if sweep:
            f = freqs[0] + (freqs[1] - freqs[0]) * (i / n)  # linear sweep
            s = math.sin(2 * math.pi * f * t)
        else:
            s = sum(math.sin(2 * math.pi * f * t) for f in freqs) / len(freqs)
        frames += struct.pack("<h", int(32767 * gain * env * s))
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(bytes(frames))
    print("wrote", path, len(frames) // 2, "samples")

os.makedirs(OUT, exist_ok=True)
tone(os.path.join(OUT, "nav_tick.wav"), [1800], 22, gain=0.25)
tone(os.path.join(OUT, "select.wav"), [880, 1320], 45, gain=0.35)
tone(os.path.join(OUT, "launch.wav"), [440, 1760], 140, gain=0.4, sweep=True)
tone(os.path.join(OUT, "back.wav"), [520], 30, gain=0.25)
```
Run `python3 scripts/generate_sounds.py` → 4 wav files (each < 15KB). Commit them (binary assets, intentional).
- [ ] **Step 2: `SoundEngine.kt`:**
```kotlin
package dev.mimir.app

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import dev.mimir.theme.HapticLevel
import dev.mimir.theme.ThemeConfig

/** Fire-and-forget UI sounds from the active theme's sound slot (bundled default set for now). */
class SoundEngine(context: Context) {
    enum class Cue { NAV, SELECT, LAUNCH, BACK }

    @Volatile var config: ThemeConfig = ThemeConfig()

    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val ids = mapOf(
        Cue.NAV to pool.load(context, R.raw.nav_tick, 1),
        Cue.SELECT to pool.load(context, R.raw.select, 1),
        Cue.LAUNCH to pool.load(context, R.raw.launch, 1),
        Cue.BACK to pool.load(context, R.raw.back, 1),
    )

    fun play(cue: Cue) {
        val volume = config.soundVolume.coerceIn(0f, 1f)
        if (volume <= 0f) return
        ids[cue]?.let { pool.play(it, volume, volume, 1, 0, 1f) }
    }

    /** Whether haptics should fire, per the active theme. */
    fun hapticsEnabled(): Boolean = config.haptics != HapticLevel.OFF
}
```
- [ ] **Step 3: Wire.** MimirApp: `val soundEngine by lazy { SoundEngine(this) }`. MainViewModel: `private val sound = (app as MimirApp).soundEngine` + in init collect themeConfig into it: `viewModelScope.launch { themeStore.config.collect { sound.config = it } }` + expose `fun play(cue: SoundEngine.Cue) = sound.play(cue)` and `fun hapticsEnabled() = sound.hapticsEnabled()`. Cue calls:
- `onGameTapped`: selection branch → `play(SoundEngine.Cue.SELECT)`; launch path (in `launchGame` on Success) → `play(SoundEngine.Cue.LAUNCH)`.
- MainActivity: tab clicks in BrowseScreen — pass `onCue: (SoundEngine.Cue) -> Unit` down? Simpler: BrowseScreen gains `onSystemChange: () -> Unit = {}` invoked when activeSystem changes (call `viewModel.play(NAV)` there); BackHandler → `play(BACK)`; HomeScreen onOpenSystem/onBrowseAll → NAV. Haptics in MainActivity via `val haptic = LocalHapticFeedback.current`: on select/launch when `viewModel.hapticsEnabled()` → `haptic.performHapticFeedback(HapticFeedbackType.LongPress)` for launch, `.TextHandleMove` for select (imports `androidx.compose.ui.hapticfeedback.HapticFeedbackType`, `androidx.compose.ui.platform.LocalHapticFeedback`). Wire haptics at the same call sites as cues (in the lambdas MainActivity passes).
- [ ] **Step 4:** Build + tests (84). Commit `feat(app): sound and haptic engine with generated default cue set`.

---

### Task 5: Backup export

**Files:** Create `app/src/main/kotlin/dev/mimir/app/BackupWriter.kt`; modify `LibraryDao.kt`/`GameRepository.kt` (snapshot queries), `MainViewModel.kt`, `MainActivity.kt`

- [ ] **Step 1:** DAO snapshots (add): `@Query("SELECT * FROM platform_prefs") suspend fun platformPrefsOnce(): List<PlatformPrefEntity>`, same for `gamePrefsOnce()`, `customPlayersOnce()`, `playStatesOnce()` (mediaOnce exists). Repo passthroughs.
- [ ] **Step 2: `BackupWriter.kt`:**
```kotlin
package dev.mimir.app

import dev.mimir.data.GameRepository
import dev.mimir.theme.ThemeConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes a mimir-backup.zip: art rows, prefs, play state, custom players, theme config. */
object BackupWriter {
    @Serializable data class MediaRow(val gameUri: String, val url: String, val kind: String, val source: String)
    @Serializable data class PrefRow(val key: String, val value: String)
    @Serializable data class PlayRow(val gameUri: String, val lastPlayedAt: Long)
    @Serializable data class CustomPlayerRow(val id: String, val name: String, val packageName: String, val activityClass: String?, val action: String, val platformIds: String)

    private val json = Json { prettyPrint = true }

    suspend fun write(out: OutputStream, repo: GameRepository, theme: ThemeConfig) {
        val media = repo.mediaSnapshot().map { MediaRow(it.gameUri, it.boxartUrl, it.kind, it.source) }
        val platformPrefs = repo.platformPrefsSnapshot().map { PrefRow(it.platformId, it.playerId) }
        val gamePrefs = repo.gamePrefsSnapshot().map { PrefRow(it.gameUri, it.playerId) }
        val plays = repo.playStatesSnapshot().map { PlayRow(it.gameUri, it.lastPlayedAt) }
        val customs = repo.customPlayersSnapshot().map {
            CustomPlayerRow(it.id, it.name, it.packageName, it.activityClass, it.action, it.platformIds)
        }
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry()
            }
            entry("media.json", json.encodeToString(ListSerializer(MediaRow.serializer()), media))
            entry("platform_prefs.json", json.encodeToString(ListSerializer(PrefRow.serializer()), platformPrefs))
            entry("game_prefs.json", json.encodeToString(ListSerializer(PrefRow.serializer()), gamePrefs))
            entry("play_state.json", json.encodeToString(ListSerializer(PlayRow.serializer()), plays))
            entry("custom_players.json", json.encodeToString(ListSerializer(CustomPlayerRow.serializer()), customs))
            entry("theme.json", json.encodeToString(ThemeConfig.serializer(), theme))
        }
    }
}
```
(Repo snapshot fns named `platformPrefsSnapshot` etc. — add matching the DAO additions. `:app` needs the serialization PLUGIN — check app/build.gradle.kts: NOT applied yet; add `alias(libs.plugins.kotlin.serialization)` to its plugins + `implementation(libs.serialization.json)`.)
- [ ] **Step 3: Wire.** MainViewModel:
```kotlin
    fun exportBackup(out: java.io.OutputStream) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { BackupWriter.write(out, repo, themeConfig.value) } }
                .onSuccess { _message.value = "Backup saved — art, emulator choices, playtime, theme" }
                .onFailure { _message.value = "Backup failed: ${it.message}" }
        }
    }
```
MainActivity: `private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) contentResolver.openOutputStream(uri)?.let(viewModel::exportBackup) }`; overflow menu gains "Back up art & themes" → `createBackup.launch("mimir-backup.zip")`.
- [ ] **Step 4:** Build + tests (84). Commit `feat(app): art and theme backup export to zip`.

---

### Task 6: E2E + docs

- [ ] **Step 1: E2E** (Pixel_10_Pro; leave running): install over v0.9.0. Checks: (1) boots to HOME — continue-playing shelf shows recents with art (screencap+READ → `img/m5a3b-home.png`); (2) shelf tap launches directly (fake emulator); (3) system tile → Browse opens with that system's tab active; (4) Browse all → Browse; back gesture → Home; (5) sounds: `adb logcat -d | grep -i soundpool` shows loads, no errors (audio output can't be heard headless — load evidence + no crash is the bar; also toggle check: nothing in settings yet, skip); (6) backup: overflow → Back up art & themes → SAF save to Downloads → pull the zip, unzip, verify 6 entries with plausible JSON (media.json has the esde/folder/sgdb rows — paste counts in evidence); (7) SGDB fix: clear hero/logo rows for GoldenEye via... simplest: from Settings re-run "Fetch heroes & logos" after deleting GoldenEye media rows via sqlite on the pulled DB? Can't write device DB easily — instead verify the FIX via unit test (already done) + on-device: add a NEW rom `/sdcard/Roms/n64/GoldenEye 007.z64`, rescan, fetch SGDB → its hero/logo should be the 007 game not Source (check media URLs differ from the old GoldenEye row's; screencap hero). (8) logcat clean. Evidence `m5a3b-verification.md`. Commit.
- [ ] **Step 2:** README status: `**Status: M5a complete — the new shell.** Continue-playing home, hero showcase, scalable browse, ambient theming with per-slot mixing, sounds + haptics, full art pipeline (folder/ES-DE/SteamGridDB/libretro), backup export. Next: M5b — the dual-screen deck, when the AYN Thor arrives.` versionCode 10 / `1.0.0-beta1` (M5a completes the v1 feature set minus dual-screen — beta1 is honest). Final --rerun-tasks 84. Commit.

## Out of scope
Backup IMPORT (restore) — follow-up; sound-pack loading from user folders (slot exists, default-only for now); wallpaper slots UI; theme settings UI beyond what exists; M5b.
