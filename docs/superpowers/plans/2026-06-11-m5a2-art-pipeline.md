# Mimir M5a-2 — Art Pipeline Implementation Plan (SGDB + folder art)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** SteamGridDB heroes/logos/grids behind a user-entered API key, and automatic local folder-art pickup on every rescan — filling the `hero`/`logo` media kinds M5a-1 created and honoring the priority chain (folder > esde > sgdb > libretro).

**Architecture:** `SgdbClient` lives in `:core:scraper` as pure request-builders + response-parsers with execution injected as a `fetcher: (url, headers) -> String?` lambda (fully unit-testable, no MockWebServer). `FolderArtMatcher` is a pure function over the scan's file list + matched games (sibling `<title>.<ext>` images and `covers|images|boxart|media/` subdirs). `:app` wires: key stored in the existing "mimir" SharedPreferences (user-entered — NEVER hardcode; Jordan's dev key is in gitignored local.properties for manual E2E entry), an "Art sources" section appended to the settings screen, folder art saved (source=folder) at the end of every rescan, SGDB fetch on demand.

**SGDB API (docs: steamgriddb.com/api/v2):** Bearer auth. `GET /api/v2/search/autocomplete/{term}` → `{success, data:[{id, name}]}`. `GET /api/v2/grids/game/{id}?dimensions=600x900` (portrait boxart-like), `/heroes/game/{id}`, `/logos/game/{id}` → `{success, data:[{id, url, ...}]}`. Implementer: verify these shapes against the live docs page before coding; report drift.

**Conventions:** Repo `~/Development/mimir`, branch from `master` (v0.7.0-m5a1, 73 tests). JAVA_HOME export as always. Co-Authored-By trailer.

---

### Task 1: `:core:scraper` — SgdbClient (TDD)

**Files:** `core/scraper/build.gradle.kts` (+ serialization plugin if absent — check; scraper module currently has NO serialization), `core/scraper/src/main/kotlin/dev/mimir/scraper/SgdbClient.kt`, test `SgdbClientTest.kt`

- [ ] **Step 1:** Check `core/scraper/build.gradle.kts`: it has only kotlin-jvm + scanner api. Add `alias(libs.plugins.kotlin.serialization)` to plugins and `implementation(libs.serialization.json)` to deps.
- [ ] **Step 2: Failing tests** — `SgdbClientTest.kt`:
```kotlin
package dev.mimir.scraper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SgdbClientTest {
    private val client = SgdbClient(apiKey = "k-test", fetcher = { url, headers ->
        assertEquals("Bearer k-test", headers["Authorization"])
        when {
            "search/autocomplete/Mario%20Kart%2064" in url ->
                """{"success":true,"data":[{"id":4263,"name":"Mario Kart 64"},{"id":99,"name":"Mario Kart 64 (Hack)"}]}"""
            "grids/game/4263" in url ->
                """{"success":true,"data":[{"id":1,"url":"https://cdn.sgdb/grid1.png"},{"id":2,"url":"https://cdn.sgdb/grid2.png"}]}"""
            "heroes/game/4263" in url ->
                """{"success":true,"data":[{"id":3,"url":"https://cdn.sgdb/hero1.png"}]}"""
            "logos/game/4263" in url ->
                """{"success":true,"data":[]}"""
            else -> null
        }
    })

    @Test
    fun `searches then fetches first asset per kind`() {
        val art = client.artFor("Mario Kart 64")
        assertEquals("https://cdn.sgdb/grid1.png", art?.gridUrl)
        assertEquals("https://cdn.sgdb/hero1.png", art?.heroUrl)
        assertNull(art?.logoUrl) // empty data array
    }

    @Test
    fun `no search hit returns null`() {
        assertNull(client.artFor("Zzz Nonexistent"))
    }

    @Test
    fun `fetch failure returns null not crash`() {
        val broken = SgdbClient("k", fetcher = { _, _ -> null })
        assertNull(broken.artFor("Mario Kart 64"))
    }

    @Test
    fun `malformed json returns null not crash`() {
        val garbage = SgdbClient("k", fetcher = { url, _ -> if ("search" in url) "<html>nope</html>" else null })
        assertNull(garbage.artFor("Mario Kart 64"))
    }
}
```
Run `:core:scraper:test` → FAIL.
- [ ] **Step 3: Implement** `SgdbClient.kt`:
```kotlin
package dev.mimir.scraper

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder

/** SteamGridDB v2 client. Execution is injected so the logic stays pure-JVM testable. */
class SgdbClient(
    private val apiKey: String,
    private val fetcher: (url: String, headers: Map<String, String>) -> String?,
) {
    @Serializable private data class SearchHit(val id: Long, val name: String)
    @Serializable private data class SearchResponse(val success: Boolean, val data: List<SearchHit> = emptyList())
    @Serializable private data class Asset(val id: Long, val url: String)
    @Serializable private data class AssetResponse(val success: Boolean, val data: List<Asset> = emptyList())

    data class GameArt(val gridUrl: String?, val heroUrl: String?, val logoUrl: String?)

    private val json = Json { ignoreUnknownKeys = true }
    private val base = "https://www.steamgriddb.com/api/v2"
    private fun headers() = mapOf("Authorization" to "Bearer $apiKey")

    fun artFor(title: String): GameArt? {
        val term = URLEncoder.encode(title, Charsets.UTF_8).replace("+", "%20")
        val search = get<SearchResponse>("$base/search/autocomplete/$term") ?: return null
        val gameId = search.data.firstOrNull()?.id ?: return null
        return GameArt(
            gridUrl = firstAsset("$base/grids/game/$gameId?dimensions=600x900"),
            heroUrl = firstAsset("$base/heroes/game/$gameId"),
            logoUrl = firstAsset("$base/logos/game/$gameId"),
        )
    }

    private fun firstAsset(url: String): String? = get<AssetResponse>(url)?.data?.firstOrNull()?.url

    private inline fun <reified T> get(url: String): T? =
        fetcher(url, headers())?.let { body -> runCatching { json.decodeFromString<T>(body) }.getOrNull() }
}
```
Run → PASS (scraper 20). Commit `feat(scraper): SteamGridDB client with injected fetcher`.

---

### Task 2: `:core:scraper` — FolderArtMatcher (TDD)

**Files:** `core/scraper/src/main/kotlin/dev/mimir/scraper/FolderArtMatcher.kt`, test `FolderArtMatcherTest.kt`

Rules: an image file (`png|jpg|jpeg|webp`) provides boxart for a game when (a) it sits in the SAME directory as the game's file and its name (minus extension) equals the game's title (exact, then normalized — reuse the normalize semantics from EsdeImportMatcher: lowercase, strip non-alphanumerics), or (b) it sits under an art subdir (`covers|images|boxart|media`, case-insensitive) of the game's directory with the same name matching. First match per game wins; (a) beats (b).

- [ ] **Step 1: Failing tests** — `FolderArtMatcherTest.kt`:
```kotlin
package dev.mimir.scraper

import dev.mimir.scanner.Game
import dev.mimir.scanner.ScannedFile
import kotlin.test.Test
import kotlin.test.assertEquals

class FolderArtMatcherTest {
    private val games = listOf(
        Game("Mario Kart 64", "uri-mk", "n64", "n64/Mario Kart 64.z64", 1),
        Game("GoldenEye", "uri-ge", "n64", "n64/GoldenEye.z64", 1),
        Game("Wind Waker", "uri-ww", "gc", "gc/Wind Waker.rvz", 1),
    )
    private fun f(path: String, uri: String = "content://$path") = ScannedFile(path, uri)

    @Test
    fun `sibling image matches by exact title`() {
        val art = FolderArtMatcher.match(listOf(f("n64/Mario Kart 64.png")), games)
        assertEquals(mapOf("uri-mk" to "content://n64/Mario Kart 64.png"), art)
    }

    @Test
    fun `art subdir matches normalized and case-insensitively`() {
        val art = FolderArtMatcher.match(listOf(f("gc/Covers/wind waker.JPG")), games)
        assertEquals(mapOf("uri-ww" to "content://gc/Covers/wind waker.JPG"), art)
    }

    @Test
    fun `sibling beats subdir and non-images plus wrong-dir images are ignored`() {
        val art = FolderArtMatcher.match(
            listOf(
                f("n64/boxart/GoldenEye.png"),
                f("n64/GoldenEye.jpg"),
                f("n64/GoldenEye.txt"),
                f("gc/GoldenEye.png"), // wrong directory for an n64 game
            ),
            games,
        )
        assertEquals(mapOf("uri-ge" to "content://n64/GoldenEye.jpg"), art)
    }
}
```
Run → FAIL.
- [ ] **Step 2: Implement** `FolderArtMatcher.kt`:
```kotlin
package dev.mimir.scraper

import dev.mimir.scanner.Game
import dev.mimir.scanner.ScannedFile

/** Picks up user-supplied art living next to the ROMs. Sibling images beat art-subdir images. */
object FolderArtMatcher {
    private val imageExts = setOf("png", "jpg", "jpeg", "webp")
    private val artDirs = setOf("covers", "images", "boxart", "media")

    fun match(files: List<ScannedFile>, games: List<Game>): Map<String, String> {
        data class Candidate(val dir: String, val stem: String, val uri: String, val sibling: Boolean)

        val candidates = files.mapNotNull { file ->
            val segments = file.relativePath.split('/')
            val name = segments.last()
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in imageExts) return@mapNotNull null
            val stem = name.substringBeforeLast('.')
            val parent = segments.dropLast(1)
            when {
                parent.isNotEmpty() && parent.last().lowercase() in artDirs ->
                    Candidate(parent.dropLast(1).joinToString("/"), stem, file.uri, sibling = false)
                else -> Candidate(parent.joinToString("/"), stem, file.uri, sibling = true)
            }
        }

        val out = mutableMapOf<String, String>()
        for (game in games) {
            val gameDir = game.relativePath.split('/').dropLast(1).joinToString("/")
            val inDir = candidates.filter { it.dir == gameDir }
            val match = inDir.filter { it.stem == game.title }.maxByOrNull { it.sibling }
                ?: inDir.filter { normalize(it.stem) == normalize(game.title) }.maxByOrNull { it.sibling }
            if (match != null) out[game.uri] = match.uri
        }
        return out
    }

    private fun normalize(s: String): String = s.lowercase().replace(Regex("""[^a-z0-9]+"""), " ").trim()
}
```
(`maxByOrNull { it.sibling }` — Boolean compares false<true, so sibling wins.) Run → PASS (scraper 23). Commit `feat(scraper): local folder-art matcher (sibling + art-subdir conventions)`.

---

### Task 3: `:app` — wiring + settings UI

**Files:** `MainViewModel.kt`, `EmulatorSettingsScreen.kt`, `MainActivity.kt`

- [ ] **Step 1: MainViewModel.**
```kotlin
    fun sgdbApiKey(): String = prefs.getString("sgdbApiKey", "") ?: ""
    fun setSgdbApiKey(key: String) { prefs.edit { putString("sgdbApiKey", key.trim()) } }

    private val sgdbProgress = MutableStateFlow<ArtScraper.Progress?>(null)
    val sgdbRunning: StateFlow<Boolean> = ... // simple Boolean StateFlow derived or its own MutableStateFlow

    fun fetchSgdbArt() {
        val key = sgdbApiKey()
        if (key.isBlank()) { _message.value = "Add your SteamGridDB API key first (it's free — steamgriddb.com/profile/preferences/api)"; return }
        if (sgdbProgress.value != null) return
        sgdbProgress.value = ArtScraper.Progress(0, 0)
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    val http = okhttp3.OkHttpClient()
                    val client = dev.mimir.scraper.SgdbClient(key) { url, headers ->
                        runCatching {
                            val req = okhttp3.Request.Builder().url(url)
                                .apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build()
                            http.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
                        }.getOrNull()
                    }
                    var saved = 0
                    val games = repo.games.first()
                    val media = repo.mediaSnapshot()   // add: suspend fun mediaSnapshot() = dao.media().first() OR a @Query list — implement as dao query `SELECT * FROM media` suspend
                    val missingHero = games.filter { g -> media.none { it.gameUri == g.uri && it.kind == "hero" } }
                    for ((i, game) in missingHero.withIndex()) {
                        sgdbProgress.value = ArtScraper.Progress(i, missingHero.size)
                        val art = client.artFor(game.title) ?: continue
                        val items = buildList {
                            art.heroUrl?.let { add(MediaEntity(game.uri, it, "hero", "sgdb")) }
                            art.logoUrl?.let { add(MediaEntity(game.uri, it, "logo", "sgdb")) }
                            art.gridUrl?.let { add(MediaEntity(game.uri, it, "boxart", "sgdb")) }
                        }
                        repo.saveArt(items)
                        saved += items.size
                    }
                    saved
                }
                _message.value = "SteamGridDB: saved $count art items"
            } finally { sgdbProgress.value = null }
        }
    }
```
(Implementer: `mediaSnapshot` — add `@Query("SELECT * FROM media") suspend fun mediaOnce(): List<MediaEntity>` to the DAO + `suspend fun mediaSnapshot() = dao.mediaOnce()` to the repo; expose `sgdbProgress` like scraping progress if trivial, else just the message. Boxart via SGDB respects priority automatically — saveArt won't downgrade folder/esde boxart.)

Folder-art on rescan — in `rescan()`'s try block the scan currently does `matcher.match(WalkEngine.walk(...))`; restructure to keep the file list:
```kotlin
                val result = withContext(Dispatchers.IO) {
                    val source = DocumentsTreeSource(getApplication<Application>().contentResolver, uri)
                    val files = WalkEngine.walk(source, source.rootUri)
                    val scan = matcher.match(files)
                    scan to FolderArtMatcher.match(files, scan.games)
                }
                repo.applyScan(result.first)
                repo.saveArt(result.second.map { (gameUri, artUri) -> MediaEntity(gameUri, artUri, "boxart", "folder") })
                fetchArtwork()
```
- [ ] **Step 2: Settings UI** — append to `EmulatorSettingsScreen` (new params: `sgdbKey: String`, `onSaveSgdbKey: (String) -> Unit`, `onFetchSgdb: () -> Unit`): an "Art sources" trailing section — a `Card` with an `OutlinedTextField` (value = local `remember { mutableStateOf(sgdbKey) }`, label "SteamGridDB API key", `visualTransformation = PasswordVisualTransformation()`), a Save TextButton calling `onSaveSgdbKey`, and a "Fetch heroes & logos" Button calling `onFetchSgdb` (enabled when key nonblank). MainActivity passes the new params (`sgdbKey = viewModel.sgdbApiKey()`, etc.).
- [ ] **Step 3:** `./gradlew test :app:assembleDebug` → 80 tests (scanner 18, scraper 23, data 16, launcher 16, theme 6, app 1). Commit `feat(app): SteamGridDB fetch with user key; folder art picked up on rescan`.

---

### Task 4: E2E + docs

- [ ] **Step 1: E2E** (Pixel_10_Pro emulator; find serial; leave running):
  1. **Folder art:** push a distinguishable PNG (solid GREEN this time, generate like M4a) to `/sdcard/Roms/n64/GoldenEye.png` (sibling convention). Rescan in-app → GoldenEye's card turns green (screencap + READ it; DB row kind=boxart source=folder). Then tap "Fetch artwork" → still green (folder > libretro). 
  2. **SGDB:** read the key from `grep steamgriddb ~/Development/mimir/local.properties` (NEVER echo it into logs/doc — redact in evidence), Settings → Art sources → focus key field → `adb shell input text <key>` → Save → "Fetch heroes & logos" → expect message "SteamGridDB: saved N art items" with N>0; DB has hero/logo rows source=sgdb for at least Mario Kart 64. (Network is live; if SGDB is unreachable or the key is rejected, capture the HTTP status via logcat, report BLOCKED-EXTERNAL with evidence rather than faking it.)
  3. Restart → everything persists; logcat clean.
  Evidence → `docs/superpowers/plans/m5a2-verification.md` (key REDACTED) + `img/m5a2-folderart.png`. Commit.
- [ ] **Step 2:** README status: `**Status: M5a-2 — full art pipeline.** Local folder art auto-pickup (drop a PNG next to your ROM), SteamGridDB heroes/logos/grids with your own free API key, ES-DE import, libretro-thumbnails — sources layered by priority (yours > ES-DE > SGDB > libretro). Next: the new shell (M5a-3).` versionCode 8 / `0.8.0-m5a2`. Final --rerun-tasks 80. Commit.

## Out of scope
Hero/logo rendering in the UI (M5a-3 shell consumes them), SGDB pagination/pickers, ScreenScraper, sound, screens.
