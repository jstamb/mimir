# Mimir M4a — ES-DE Media Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Point Mimir at an existing `ES-DE/downloaded_media` folder and inherit its scraped boxart instantly — the switcher's migration path from the spec (scraper tier 4), keyless and offline.

**Architecture:** Reuses the whole existing pipeline: the user picks the media tree via SAF (persisted READ permission), the existing `DocumentsTreeSource` + `WalkEngine` walk it, and a new pure `EsdeImportMatcher` in `:core:scraper` matches `<system>/covers/<Game Name>.<ext>` files to library games — system dir matched via the platforms' existing `folderAliases` (ES-DE's short names `n64`, `gc`, `psx`… are already aliases), game matched by exact title then normalized title. Matches are stored as `MediaEntity` rows whose `boxartUrl` is the image's SAF `content://` URI — Coil loads content URIs natively, so zero new infrastructure. Video files are counted and reported (playback lands with the theming milestone), not imported.

**ES-DE layout (reference):** `downloaded_media/<system shortname>/<mediatype>/<game filename minus extension>.<png|jpg>`; mediatypes include `covers`, `screenshots`, `marquees`, `videos` (`.mp4`). Game media filenames equal the ROM filename minus extension — which is exactly Mimir's `Game.title`.

**Conventions:** Repo `~/Development/mimir`, branch from `master` (v0.5.0-m2c, 59 tests). `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` for every `./gradlew`. Commits end with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

### Task 1: `:core:scraper` — EsdeImportMatcher (TDD)

**Files:**
- Create: `core/scraper/src/main/kotlin/dev/mimir/scraper/EsdeImportMatcher.kt`
- Test: `core/scraper/src/test/kotlin/dev/mimir/scraper/EsdeImportMatcherTest.kt`

Note: `:core:scraper` currently has NO dependency on `:core:scanner` — the matcher needs `ScannedFile`/`Game`/`PlatformDef`. Add to `core/scraper/build.gradle.kts` dependencies: `api(project(":core:scanner"))`.

- [ ] **Step 1: Failing test** — `EsdeImportMatcherTest.kt`:
```kotlin
package dev.mimir.scraper

import dev.mimir.scanner.Game
import dev.mimir.scanner.PlatformDef
import dev.mimir.scanner.ScannedFile
import kotlin.test.Test
import kotlin.test.assertEquals

class EsdeImportMatcherTest {
    private val platforms = listOf(
        PlatformDef("n64", "Nintendo 64", listOf("n64", "nintendo64"), listOf("z64"), "Nintendo - Nintendo 64"),
        PlatformDef("gc", "GameCube", listOf("gc", "gamecube"), listOf("rvz"), "Nintendo - GameCube"),
    )
    private val games = listOf(
        Game("Mario Kart 64", "uri-mk", "n64", "n64/Mario Kart 64.z64", 1),
        Game("GoldenEye", "uri-ge", "n64", "n64/GoldenEye.z64", 1),
        Game("Wind Waker", "uri-ww", "gc", "gc/Wind Waker.rvz", 1),
    )

    private fun media(path: String, uri: String) = ScannedFile(relativePath = path, uri = uri)

    @Test
    fun `matches covers by exact title within the aliased system dir`() {
        val result = EsdeImportMatcher.match(
            mediaFiles = listOf(
                media("n64/covers/Mario Kart 64.png", "content://esde/mk64"),
                media("gamecube/covers/Wind Waker.jpg", "content://esde/ww"),
            ),
            games = games,
            platforms = platforms,
        )
        assertEquals(
            mapOf("uri-mk" to "content://esde/mk64", "uri-ww" to "content://esde/ww"),
            result.covers,
        )
    }

    @Test
    fun `normalized fallback matches punctuation differences`() {
        val result = EsdeImportMatcher.match(
            mediaFiles = listOf(media("n64/covers/goldeneye.png", "content://esde/ge")),
            games = games,
            platforms = platforms,
        )
        assertEquals(mapOf("uri-ge" to "content://esde/ge"), result.covers)
    }

    @Test
    fun `counts videos without importing and ignores other media types`() {
        val result = EsdeImportMatcher.match(
            mediaFiles = listOf(
                media("n64/videos/Mario Kart 64.mp4", "content://esde/v1"),
                media("n64/screenshots/Mario Kart 64.png", "content://esde/s1"),
                media("n64/covers/Mario Kart 64.png", "content://esde/c1"),
            ),
            games = games,
            platforms = platforms,
        )
        assertEquals(1, result.covers.size)
        assertEquals(1, result.videoCount)
    }

    @Test
    fun `unknown system dirs and unmatched titles are skipped silently`() {
        val result = EsdeImportMatcher.match(
            mediaFiles = listOf(
                media("atari2600/covers/Pitfall.png", "content://esde/p"),
                media("n64/covers/Banjo-Tooie.png", "content://esde/b"),
            ),
            games = games,
            platforms = platforms,
        )
        assertEquals(emptyMap(), result.covers)
        assertEquals(0, result.videoCount)
    }
}
```
Run `./gradlew :core:scraper:test` → FAIL (unresolved EsdeImportMatcher; possibly unresolved scanner types until the dep is added).

- [ ] **Step 2: Implement** — `EsdeImportMatcher.kt`:
```kotlin
package dev.mimir.scraper

import dev.mimir.scanner.Game
import dev.mimir.scanner.PlatformDef
import dev.mimir.scanner.ScannedFile

/**
 * Matches an ES-DE downloaded_media tree against the library.
 * Path shape: <system dir>/<mediatype>/<game filename minus extension>.<ext>.
 * System dirs resolve through the platforms' folderAliases (ES-DE short names
 * are already aliases). Covers match by exact title, then normalized title.
 */
object EsdeImportMatcher {
    data class Result(
        /** game uri -> cover image SAF uri */
        val covers: Map<String, String>,
        val videoCount: Int,
    )

    fun match(mediaFiles: List<ScannedFile>, games: List<Game>, platforms: List<PlatformDef>): Result {
        val aliasToPlatformId = platforms
            .flatMap { p -> p.folderAliases.map { it.lowercase().trim() to p.id } }
            .toMap()
        val gamesByPlatform = games.groupBy { it.platformId }

        val covers = mutableMapOf<String, String>()
        var videoCount = 0
        for (file in mediaFiles) {
            val segments = file.relativePath.split('/')
            if (segments.size < 3) continue
            val platformId = aliasToPlatformId[segments[0].lowercase().trim()] ?: continue
            val mediaType = segments[1].lowercase()
            val title = segments.last().substringBeforeLast('.')
            when (mediaType) {
                "videos" -> if (gamesByPlatform[platformId]?.any { titlesMatch(it.title, title) } == true) videoCount++
                "covers" -> {
                    val game = gamesByPlatform[platformId]?.firstOrNull { it.title == title }
                        ?: gamesByPlatform[platformId]?.firstOrNull { titlesMatch(it.title, title) }
                    if (game != null && game.uri !in covers) covers[game.uri] = file.uri
                }
            }
        }
        return Result(covers, videoCount)
    }

    private fun titlesMatch(a: String, b: String): Boolean = normalize(a) == normalize(b)

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("""[^a-z0-9]+"""), " ").trim()
}
```
Run → PASS (scraper = 16). Commit:
```bash
git add -A && git commit -m "feat(scraper): ES-DE downloaded_media matcher for cover import"
```

---

### Task 2: `:app` — import flow

**Files:**
- Modify: `app/src/main/kotlin/dev/mimir/app/MainViewModel.kt`
- Modify: `app/src/main/kotlin/dev/mimir/app/MainActivity.kt`

(Design note: the entry point lives in the library header, NOT the Emulators settings screen — settings stays emulator-only.)

- [ ] **Step 1: ViewModel** — add (imports: `dev.mimir.data.MediaEntity`, `dev.mimir.scraper.EsdeImportMatcher`):
```kotlin
    fun importEsdeMedia(treeUri: Uri) {
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val source = DocumentsTreeSource(getApplication<Application>().contentResolver, treeUri)
                    val mediaFiles = WalkEngine.walk(source, source.rootUri)
                    val games = repo.games.first()
                    EsdeImportMatcher.match(
                        mediaFiles = mediaFiles,
                        games = games.map { dev.mimir.scanner.Game(it.title, it.uri, it.platformId, it.relativePath, it.lastModified) },
                        platforms = platforms,
                    )
                }
                repo.saveArt(result.covers.map { (gameUri, imageUri) -> MediaEntity(gameUri, imageUri) })
                _message.value = buildString {
                    append("Imported ${result.covers.size} covers from ES-DE")
                    if (result.videoCount > 0) append(" — ${result.videoCount} videos found (video support comes with theming)")
                }
            } catch (e: TreeAccessException) {
                _message.value = "Couldn't read that folder (${e.message})"
            }
        }
    }
```
(import `kotlinx.coroutines.flow.first`.)

- [ ] **Step 2: MainActivity** — second SAF launcher + header button. In MainActivity (the class body, next to `pickFolder`):
```kotlin
    private val pickEsdeMedia = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.importEsdeMedia(uri)
        }
    }
```
Pass `onImportEsde = { pickEsdeMedia.launch(null) }` down: `MainScreen` gains the param and forwards to `LibraryGrid`, which gains `onImportEsde: () -> Unit` and a header `TextButton(onClick = onImportEsde) { Text("Import ES-DE") }` placed after "Fetch artwork". (Header buttons are getting crowded — acceptable until the theming milestone redesigns the header; do NOT redesign now.)

- [ ] **Step 3: Build + tests** — `./gradlew :app:assembleDebug test` → 63 tests, 0 failures (scanner 18, scraper 16, data 12, launcher 16, app 1). Commit:
```bash
git add -A && git commit -m "feat(app): ES-DE downloaded_media import — covers via SAF content URIs"
```

---

### Task 3: E2E + docs

**Files:**
- Create: `docs/superpowers/plans/m4a-verification.md`, `docs/superpowers/plans/img/m4a-imported.png`
- Modify: `README.md`, `app/build.gradle.kts`

- [ ] **Step 1: E2E** on the running Pixel_10_Pro emulator (check `adb devices` + `adb emu avd name` for the serial — it was emulator-5554 in the last run). Build a fake ES-DE tree with a REAL distinguishable image:
```bash
ADB=~/Library/Android/sdk/platform-tools/adb; S=<serial>
$ADB -s $S shell mkdir -p /sdcard/ES-DE/downloaded_media/n64/covers /sdcard/ES-DE/downloaded_media/n64/videos
# make a real PNG locally (solid magenta 200x280 so it's visually unmistakable vs the libretro art):
python3 - <<'EOF' || magick -size 200x280 xc:magenta /tmp/esde-cover.png
import zlib, struct
w,h=200,280; row=b'\x00'+b'\xff\x00\xff'*w
raw=zlib.compress(row*h)
def chunk(t,d): return struct.pack('>I',len(d))+t+d+struct.pack('>I',zlib.crc32(t+d)&0xffffffff)
png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,8,2,0,0,0))+chunk(b'IDAT',raw)+chunk(b'IEND',b'')
open('/tmp/esde-cover.png','wb').write(png)
EOF
$ADB -s $S push /tmp/esde-cover.png "/sdcard/ES-DE/downloaded_media/n64/covers/Mario Kart 64.png"
$ADB -s $S shell touch "/sdcard/ES-DE/downloaded_media/n64/videos/Mario Kart 64.mp4"
```
Then: install current build if not already (`ANDROID_SERIAL=$S ./gradlew :app:installDebug`), launch, tap **Import ES-DE** → SAF picker → navigate to ES-DE/downloaded_media → Use this folder → Allow. Verify:
1. Snackbar: "Imported 1 covers from ES-DE — 1 videos found (video support comes with theming)".
2. Mario Kart 64's card turns MAGENTA (screencap, READ the png — the imported cover must visibly replace the libretro boxart). Other cards unchanged.
3. DB: media row for the MK64 uri now holds a `content://` ES-DE uri (pull DB or `adb shell` query via run-as is unavailable — pull the db file as in prior verifications).
4. Force-stop + relaunch → magenta cover persists (persisted permission + DB).
5. Logcat clean of FATAL.
Record evidence in m4a-verification.md, screenshot to img/m4a-imported.png, commit.

- [ ] **Step 2: README + version.** Status paragraph → replace M2c paragraph:
```markdown
**Status: M4a — ES-DE media import.** One tap imports covers from an existing
ES-DE `downloaded_media` folder (videos are detected and counted; playback
lands with theming). Open emulator model, boxart scraping, persistent library
all in. Next: UI polish + theme system.
```
`versionCode = 6`, `versionName = "0.6.0-m4a"`. Final `--rerun-tasks` check → 63 tests. Commit.

---

## Out of scope (do not add)

Video playback, screenshots/marquees import, ScreenScraper (needs Jordan's dev credentials), SteamGridDB (needs user API key), import-progress UI beyond the snackbar, header redesign (theming milestone), per-game art override UI.
