# Mimir M2a — Persistence + Scanner v2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace M1's per-entry SAF walking and in-memory library with a fast bulk DocumentsContract scanner, Room persistence with incremental diff-sync, an error state for revoked/lost folder access, and a full scan-report screen — the three risks from the M1 final review plus the spec's scanner promises.

**Architecture:** A new `:core:data` Android-library module owns Room (GameEntity/SkippedFileEntity, LibraryDao, MimirDatabase) plus the pure DiffEngine and GameRepository. `:core:scanner` gains a pure `TreeSource`/`WalkEngine` abstraction (unit-testable walking); `:app` implements `TreeSource` with bulk DocumentsContract cursor queries (one IPC per directory instead of per entry) and reworks MainViewModel to be DB-flow-backed with an Error state and a scanning flag. The library now loads instantly from Room on cold start while rescans diff in the background — no duplicates, no losses.

**Tech Stack:** Room 2.8.4 via KSP. **CRITICAL TOOLCHAIN NOTE:** KSP has no Kotlin 2.4.x release yet (google/ksp issue #2965, open as of 2026-06-04; latest KSP = 2.3.9 for Kotlin 2.3.x). Task 1 therefore downgrades the project to Kotlin 2.3.x — verified low-risk, nothing uses 2.4 features; AGP 9.2 requires only KGP ≥ 2.2.10. Robolectric for the DAO round-trip test.

**Conventions:** Repo `~/Development/mimir`, branch from `master`. Every `./gradlew` needs `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`. Commits end with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

### Task 1: Toolchain alignment (Kotlin 2.3.x + KSP + Room + Robolectric in catalog)

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts` (root)
- Modify: `settings.gradle.kts`

- [ ] **Step 1: Update the version catalog**

In `gradle/libs.versions.toml`, change `kotlin = "2.4.0"` to `kotlin = "2.3.10"` and add the new entries:

```toml
[versions]
# kotlin downgraded 2.4.0 -> 2.3.x: KSP (required by Room) has no Kotlin 2.4 release yet
# (github.com/google/ksp/issues/2965). Revert when KSP ships 2.4 support.
kotlin = "2.3.10"
ksp = "2.3.9"
room = "2.8.4"
robolectric = "4.16"
androidxTestCore = "1.7.0"
```

Add to `[libraries]`:

```toml
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "coroutines" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
androidx-test-core = { group = "androidx.test", name = "core-ktx", version.ref = "androidxTestCore" }
```

Add to `[plugins]`:

```toml
android-library = { id = "com.android.library", version.ref = "agp" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

Version verification rules (apply IN ORDER if `./gradlew help` or later resolution fails):
1. Kotlin 2.3.10 not found → try 2.3.4, then the highest 2.3.x listed at https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/
2. KSP 2.3.9 plugin id not found → check https://github.com/google/ksp/releases for the exact artifact/plugin version matching your chosen Kotlin 2.3.x (older hybrid scheme looks like `2.3.4-2.x.x` — use whatever the release page pairs with that Kotlin version)
3. KSP version must pair with the SAME Kotlin minor you chose. Report every adjusted version.

- [ ] **Step 2: Register new plugins in root `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
```

- [ ] **Step 3: Add the module include**

In `settings.gradle.kts`, after `include(":core:launcher")` add:

```kotlin
include(":core:data")
```

Create `core/data/build.gradle.kts` as an EMPTY file (filled in Task 3).

- [ ] **Step 4: Verify the downgraded toolchain builds everything**

Run: `./gradlew test :app:assembleDebug :tools:fake-emulator:assembleDebug --rerun-tasks`
Expected: BUILD SUCCESSFUL, 17/17 tests pass. The Kotlin downgrade must not break anything; if it does, capture the exact error and stop (report BLOCKED).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "chore: Kotlin 2.3.x + KSP + Room in catalog (KSP lacks Kotlin 2.4 support)"
```

---

### Task 2: `:core:scanner` — TreeSource + WalkEngine + richer Game (TDD)

**Files:**
- Create: `core/scanner/src/main/kotlin/dev/mimir/scanner/TreeSource.kt`
- Create: `core/scanner/src/main/kotlin/dev/mimir/scanner/WalkEngine.kt`
- Modify: `core/scanner/src/main/kotlin/dev/mimir/scanner/LibraryMatcher.kt`
- Test: `core/scanner/src/test/kotlin/dev/mimir/scanner/WalkEngineTest.kt`
- Test: modify `core/scanner/src/test/kotlin/dev/mimir/scanner/LibraryMatcherTest.kt`

- [ ] **Step 1: Write the failing tests**

`core/scanner/src/test/kotlin/dev/mimir/scanner/WalkEngineTest.kt`:

```kotlin
package dev.mimir.scanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** In-memory TreeSource: map of dirUri -> children. */
private class FakeTree(private val tree: Map<String, List<TreeSource.Node>>) : TreeSource {
    override fun children(dirUri: String): List<TreeSource.Node> =
        tree[dirUri] ?: throw TreeAccessException("no such dir: $dirUri")
}

private fun dir(name: String, uri: String) = TreeSource.Node(name, uri, isDirectory = true, lastModified = 0)
private fun file(name: String, uri: String, modified: Long = 7) =
    TreeSource.Node(name, uri, isDirectory = false, lastModified = modified)

class WalkEngineTest {
    @Test
    fun `walks nested directories building relative paths`() {
        val source = FakeTree(
            mapOf(
                "root" to listOf(dir("n64", "d1"), file("notes.txt", "f0")),
                "d1" to listOf(file("Mario Kart 64.z64", "f1", modified = 42), dir("hacks", "d2")),
                "d2" to listOf(file("Kaizo.z64", "f2")),
            )
        )
        val files = WalkEngine.walk(source, "root").sortedBy { it.relativePath }
        assertEquals(
            listOf("n64/Mario Kart 64.z64", "n64/hacks/Kaizo.z64", "notes.txt").sorted(),
            files.map { it.relativePath },
        )
        val mario = files.first { it.uri == "f1" }
        assertEquals(42, mario.lastModified)
    }

    @Test
    fun `empty root yields empty list`() {
        assertEquals(emptyList(), WalkEngine.walk(FakeTree(mapOf("root" to emptyList())), "root"))
    }

    @Test
    fun `access failure propagates as TreeAccessException`() {
        assertFailsWith<TreeAccessException> { WalkEngine.walk(FakeTree(emptyMap()), "root") }
    }
}
```

Add to `core/scanner/src/test/kotlin/dev/mimir/scanner/LibraryMatcherTest.kt` (inside the existing class):

```kotlin
    @Test
    fun `matched game carries relativePath and lastModified through`() {
        val scanned = ScannedFile(relativePath = "n64/GoldenEye.z64", uri = "content://x", lastModified = 99)
        val game = matcher.match(listOf(scanned)).games.single()
        assertEquals("n64/GoldenEye.z64", game.relativePath)
        assertEquals(99, game.lastModified)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:scanner:test`
Expected: FAIL — unresolved `TreeSource`, `WalkEngine`, `TreeAccessException`; matcher test fails on missing `lastModified` parameter/property.

- [ ] **Step 3: Implement**

`core/scanner/src/main/kotlin/dev/mimir/scanner/TreeSource.kt`:

```kotlin
package dev.mimir.scanner

/** Abstraction over a walkable document tree; Android implements this with DocumentsContract. */
interface TreeSource {
    data class Node(
        val name: String,
        /** Opaque launchable/queryable identifier (SAF document URI on Android). */
        val uri: String,
        val isDirectory: Boolean,
        val lastModified: Long,
    )

    /** Lists the direct children of a directory. Throws [TreeAccessException] when the tree can't be read. */
    fun children(dirUri: String): List<Node>
}

class TreeAccessException(message: String, cause: Throwable? = null) : Exception(message, cause)
```

`core/scanner/src/main/kotlin/dev/mimir/scanner/WalkEngine.kt`:

```kotlin
package dev.mimir.scanner

object WalkEngine {
    /** Depth-first walk producing every file with a '/'-joined path relative to the root. */
    fun walk(source: TreeSource, rootUri: String): List<ScannedFile> {
        val out = mutableListOf<ScannedFile>()
        fun recurse(dirUri: String, prefix: String) {
            for (node in source.children(dirUri)) {
                val path = if (prefix.isEmpty()) node.name else "$prefix/${node.name}"
                if (node.isDirectory) recurse(node.uri, path)
                else out += ScannedFile(relativePath = path, uri = node.uri, lastModified = node.lastModified)
            }
        }
        recurse(rootUri, "")
        return out
    }
}
```

In `core/scanner/src/main/kotlin/dev/mimir/scanner/LibraryMatcher.kt`, change the two data classes (defaults keep all M1 call sites compiling):

```kotlin
data class ScannedFile(
    /** Path relative to the library root, '/'-separated, e.g. "GameCube/Wind Waker.rvz". */
    val relativePath: String,
    /** Opaque launchable identifier (SAF content URI on Android; anything in tests). */
    val uri: String,
    val lastModified: Long = 0L,
)

data class Game(
    val title: String,
    val uri: String,
    val platformId: String,
    val relativePath: String = "",
    val lastModified: Long = 0L,
)
```

And update BOTH `games +=` sites in `match()` to pass the new fields. Folder-match branch:

```kotlin
                folderPlatform != null && ext in platformIdToExtensions.getValue(folderPlatform.id) ->
                    games += Game(title, file.uri, folderPlatform.id, file.relativePath, file.lastModified)
```

Extension-unique fallback branch:

```kotlin
                        1 -> games += Game(title, file.uri, byExt.single().id, file.relativePath, file.lastModified)
```

(Keep the exact existing condition expressions — only the `Game(...)` constructions change.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:scanner:test`
Expected: PASS — 17 tests (13 existing + 3 WalkEngine + 1 matcher passthrough).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(scanner): pure TreeSource/WalkEngine and lastModified passthrough"
```

---

### Task 3: `:core:data` — Room database module

**Files:**
- Modify: `core/data/build.gradle.kts` (currently empty)
- Create: `core/data/src/main/AndroidManifest.xml`
- Create: `core/data/src/main/kotlin/dev/mimir/data/Entities.kt`
- Create: `core/data/src/main/kotlin/dev/mimir/data/LibraryDao.kt`
- Create: `core/data/src/main/kotlin/dev/mimir/data/MimirDatabase.kt`
- Test: `core/data/src/test/kotlin/dev/mimir/data/LibraryDaoTest.kt`

- [ ] **Step 1: Write `core/data/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}
android {
    namespace = "dev.mimir.data"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    api(project(":core:scanner"))
    api(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
```

And `core/data/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 2: Write the failing Robolectric round-trip test**

`core/data/src/test/kotlin/dev/mimir/data/LibraryDaoTest.kt`:

```kotlin
package dev.mimir.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class LibraryDaoTest {
    private fun db(): MimirDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        MimirDatabase::class.java,
    ).allowMainThreadQueries().build()

    @Test
    fun `games round trip with upsert delete and ordering`() = runBlocking {
        val dao = db().libraryDao()
        dao.upsertGames(
            listOf(
                GameEntity("uri-b", "Banjo", "n64", "n64/Banjo.z64", 1),
                GameEntity("uri-a", "Aero", "n64", "n64/Aero.z64", 1),
            )
        )
        assertEquals(listOf("Aero", "Banjo"), dao.games().first().map { it.title })

        dao.upsertGames(listOf(GameEntity("uri-b", "Banjo-Kazooie", "n64", "n64/Banjo.z64", 2)))
        assertEquals("Banjo-Kazooie", dao.games().first().first { it.uri == "uri-b" }.title)

        dao.deleteGames(listOf("uri-a"))
        assertEquals(listOf("uri-b"), dao.gamesOnce().map { it.uri })
    }

    @Test
    fun `skipped files replace wholesale`() = runBlocking {
        val dao = db().libraryDao()
        dao.insertSkipped(listOf(SkippedFileEntity("a.txt", "reason one")))
        dao.clearSkipped()
        dao.insertSkipped(listOf(SkippedFileEntity("b.txt", "reason two")))
        assertEquals(listOf("b.txt"), dao.skippedFiles().first().map { it.relativePath })
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :core:data:testDebugUnitTest`
Expected: FAIL — unresolved `MimirDatabase`, `GameEntity`, etc.

- [ ] **Step 4: Implement**

`core/data/src/main/kotlin/dev/mimir/data/Entities.kt`:

```kotlin
package dev.mimir.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "games")
data class GameEntity(
    @PrimaryKey val uri: String,
    val title: String,
    val platformId: String,
    val relativePath: String,
    val lastModified: Long,
)

@Entity(tableName = "skipped_files")
data class SkippedFileEntity(
    @PrimaryKey val relativePath: String,
    val reason: String,
)
```

`core/data/src/main/kotlin/dev/mimir/data/LibraryDao.kt`:

```kotlin
package dev.mimir.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM games ORDER BY title")
    fun games(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games")
    suspend fun gamesOnce(): List<GameEntity>

    @Upsert
    suspend fun upsertGames(games: List<GameEntity>)

    @Query("DELETE FROM games WHERE uri IN (:uris)")
    suspend fun deleteGames(uris: List<String>)

    @Query("SELECT * FROM skipped_files ORDER BY relativePath")
    fun skippedFiles(): Flow<List<SkippedFileEntity>>

    @Query("DELETE FROM skipped_files")
    suspend fun clearSkipped()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSkipped(items: List<SkippedFileEntity>)
}
```

`core/data/src/main/kotlin/dev/mimir/data/MimirDatabase.kt`:

```kotlin
package dev.mimir.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [GameEntity::class, SkippedFileEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class MimirDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :core:data:testDebugUnitTest`
Expected: PASS (2 tests). If Robolectric fails on SDK 36 simulation, add `@org.robolectric.annotation.Config(sdk = [34])` on the test class and report the deviation.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat(data): Room database module with games and skipped-files tables"
```

---

### Task 4: `:core:data` — DiffEngine + GameRepository (TDD)

**Files:**
- Create: `core/data/src/main/kotlin/dev/mimir/data/DiffEngine.kt`
- Create: `core/data/src/main/kotlin/dev/mimir/data/GameRepository.kt`
- Test: `core/data/src/test/kotlin/dev/mimir/data/DiffEngineTest.kt`
- Test: modify `core/data/src/test/kotlin/dev/mimir/data/LibraryDaoTest.kt` (add applyScan test)

- [ ] **Step 1: Write the failing tests**

`core/data/src/test/kotlin/dev/mimir/data/DiffEngineTest.kt`:

```kotlin
package dev.mimir.data

import dev.mimir.scanner.Game
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiffEngineTest {
    private val storedMario = GameEntity("uri-m", "Mario Kart 64", "n64", "n64/Mario Kart 64.z64", 10)

    private fun scannedMario(modified: Long = 10) =
        Game("Mario Kart 64", "uri-m", "n64", "n64/Mario Kart 64.z64", modified)

    @Test
    fun `unchanged game produces empty changeset`() {
        val change = DiffEngine.diff(listOf(storedMario), listOf(scannedMario()))
        assertTrue(change.toUpsert.isEmpty())
        assertTrue(change.toDeleteUris.isEmpty())
    }

    @Test
    fun `new game is upserted`() {
        val new = Game("GoldenEye", "uri-g", "n64", "n64/GoldenEye.z64", 5)
        val change = DiffEngine.diff(listOf(storedMario), listOf(scannedMario(), new))
        assertEquals(listOf("uri-g"), change.toUpsert.map { it.uri })
        assertTrue(change.toDeleteUris.isEmpty())
    }

    @Test
    fun `modified lastModified is upserted`() {
        val change = DiffEngine.diff(listOf(storedMario), listOf(scannedMario(modified = 99)))
        assertEquals(listOf(99L), change.toUpsert.map { it.lastModified })
    }

    @Test
    fun `missing game is deleted`() {
        val change = DiffEngine.diff(listOf(storedMario), emptyList())
        assertEquals(listOf("uri-m"), change.toDeleteUris)
        assertTrue(change.toUpsert.isEmpty())
    }
}
```

Add to `LibraryDaoTest.kt` (inside the class — uses `ScanResult`/`SkippedFile` from `:core:scanner`; add imports `dev.mimir.scanner.Game`, `dev.mimir.scanner.ScanResult`, `dev.mimir.scanner.SkippedFile`):

```kotlin
    @Test
    fun `applyScan diffs into the database idempotently`() = runBlocking {
        val dao = db().libraryDao()
        val repo = GameRepository(dao)
        val scan = ScanResult(
            games = listOf(Game("Mario Kart 64", "uri-m", "n64", "n64/Mario Kart 64.z64", 10)),
            skipped = listOf(SkippedFile("notes.txt", "no platform registered for extension .txt and no platform folder in path")),
        )
        repo.applyScan(scan)
        repo.applyScan(scan) // idempotent
        assertEquals(1, dao.gamesOnce().size)
        assertEquals(1, dao.skippedFiles().first().size)

        repo.applyScan(ScanResult(games = emptyList(), skipped = emptyList()))
        assertEquals(0, dao.gamesOnce().size)
        assertEquals(0, dao.skippedFiles().first().size)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:data:testDebugUnitTest`
Expected: FAIL — unresolved `DiffEngine`, `GameRepository`.

- [ ] **Step 3: Implement**

`core/data/src/main/kotlin/dev/mimir/data/DiffEngine.kt`:

```kotlin
package dev.mimir.data

import dev.mimir.scanner.Game

data class ChangeSet(
    val toUpsert: List<GameEntity>,
    val toDeleteUris: List<String>,
)

fun Game.toEntity() = GameEntity(
    uri = uri,
    title = title,
    platformId = platformId,
    relativePath = relativePath,
    lastModified = lastModified,
)

object DiffEngine {
    /** Computes the minimal set of writes to make the stored library match a scan result. */
    fun diff(existing: List<GameEntity>, scanned: List<Game>): ChangeSet {
        val existingByUri = existing.associateBy { it.uri }
        val scannedUris = scanned.mapTo(mutableSetOf()) { it.uri }
        val toUpsert = scanned
            .filter { game -> existingByUri[game.uri] != game.toEntity() }
            .map { it.toEntity() }
        val toDelete = existing.filter { it.uri !in scannedUris }.map { it.uri }
        return ChangeSet(toUpsert, toDelete)
    }
}
```

`core/data/src/main/kotlin/dev/mimir/data/GameRepository.kt`:

```kotlin
package dev.mimir.data

import dev.mimir.scanner.ScanResult
import kotlinx.coroutines.flow.Flow

class GameRepository(private val dao: LibraryDao) {
    val games: Flow<List<GameEntity>> = dao.games()
    val skipped: Flow<List<SkippedFileEntity>> = dao.skippedFiles()

    suspend fun applyScan(result: ScanResult) {
        val change = DiffEngine.diff(dao.gamesOnce(), result.games)
        if (change.toUpsert.isNotEmpty()) dao.upsertGames(change.toUpsert)
        if (change.toDeleteUris.isNotEmpty()) dao.deleteGames(change.toDeleteUris)
        dao.clearSkipped()
        if (result.skipped.isNotEmpty()) {
            dao.insertSkipped(result.skipped.map { SkippedFileEntity(it.relativePath, it.reason) })
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:data:testDebugUnitTest`
Expected: PASS (7 tests: 2 DAO + 4 DiffEngine + 1 applyScan).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(data): diff-sync engine and repository for incremental rescans"
```

---

### Task 5: `:app` — DocumentsContract bulk TreeSource (replaces SafTreeWalker)

**Files:**
- Create: `app/src/main/kotlin/dev/mimir/app/DocumentsTreeSource.kt`
- Delete: `app/src/main/kotlin/dev/mimir/app/SafTreeWalker.kt`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Write `DocumentsTreeSource.kt`**

```kotlin
package dev.mimir.app

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import dev.mimir.scanner.TreeAccessException
import dev.mimir.scanner.TreeSource

/**
 * Bulk SAF tree source: one ContentResolver child-query per directory
 * (vs DocumentFile's one IPC round-trip per entry).
 */
class DocumentsTreeSource(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) : TreeSource {
    val rootUri: String = DocumentsContract.buildDocumentUriUsingTree(
        treeUri, DocumentsContract.getTreeDocumentId(treeUri)
    ).toString()

    override fun children(dirUri: String): List<TreeSource.Node> {
        val docId = DocumentsContract.getDocumentId(Uri.parse(dirUri))
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val cursor = try {
            resolver.query(childrenUri, projection, null, null, null)
        } catch (e: SecurityException) {
            throw TreeAccessException("folder permission revoked", e)
        } ?: throw TreeAccessException("document provider returned no result for $dirUri")

        val out = mutableListOf<TreeSource.Node>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                out += TreeSource.Node(
                    name = name,
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0)).toString(),
                    isDirectory = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    lastModified = c.getLong(3),
                )
            }
        }
        return out
    }
}
```

- [ ] **Step 2: Delete `SafTreeWalker.kt` and drop the now-unused dependency**

```bash
rm app/src/main/kotlin/dev/mimir/app/SafTreeWalker.kt
```

In `app/build.gradle.kts` dependencies, REMOVE the line `implementation(libs.documentfile)` and ADD:

```kotlin
    implementation(project(":core:data"))
```

(Leave the `documentfile` catalog entry in place — harmless, and M2b may use it.)

- [ ] **Step 3: Verify compile fails only where expected, fix comes next task**

Run: `./gradlew :app:compileDebugKotlin`
Expected: FAIL — `MainViewModel.kt` references the deleted `SafTreeWalker`. That's the Task 6 rework. Do NOT commit yet; Tasks 5+6 commit together after green (single commit, message below in Task 6).

---

### Task 6: `:app` — MainViewModel rework (DB-backed, Error state, scanning flag)

**Files:**
- Modify: `app/src/main/kotlin/dev/mimir/app/MainViewModel.kt` (full replacement below)

- [ ] **Step 1: Replace `MainViewModel.kt` entirely with:**

```kotlin
package dev.mimir.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import dev.mimir.data.GameEntity
import dev.mimir.data.GameRepository
import dev.mimir.data.MimirDatabase
import dev.mimir.data.SkippedFileEntity
import dev.mimir.launcher.PlayerDefs
import dev.mimir.launcher.buildIntentSpec
import dev.mimir.launcher.defaultPlayerFor
import dev.mimir.scanner.LibraryMatcher
import dev.mimir.scanner.PlatformDefs
import dev.mimir.scanner.TreeAccessException
import dev.mimir.scanner.WalkEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UiState {
    data object NeedsFolder : UiState
    data class Error(val message: String) : UiState
    data class Library(
        val gamesByPlatform: Map<String, List<GameEntity>>, // key = platform display name
        val skippedCount: Int,
        val scanning: Boolean,
    ) : UiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("mimir", Context.MODE_PRIVATE)
    private val platforms = PlatformDefs.load()
    private val platformNames = platforms.associate { it.id to it.name }
    private val matcher = LibraryMatcher(platforms)
    private val players = PlayerDefs.load()
    private val db = Room.databaseBuilder(app, MimirDatabase::class.java, "mimir.db").build()
    private val repo = GameRepository(db.libraryDao())

    private val treeUri = MutableStateFlow(prefs.getString("treeUri", null)?.let(Uri::parse))
    private val scanning = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    val skipped: StateFlow<List<SkippedFileEntity>> =
        repo.skipped.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val state: StateFlow<UiState> =
        combine(treeUri, repo.games, skipped, scanning, error) { uri, games, skip, scan, err ->
            when {
                err != null -> UiState.Error(err)
                uri == null -> UiState.NeedsFolder
                else -> UiState.Library(
                    gamesByPlatform = games
                        .groupBy { platformNames[it.platformId] ?: it.platformId }
                        .toSortedMap(),
                    skippedCount = skip.size,
                    scanning = scan,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState.NeedsFolder)

    init { treeUri.value?.let { rescan() } }

    fun onFolderPicked(uri: Uri) {
        prefs.edit { putString("treeUri", uri.toString()) }
        treeUri.value = uri
        error.value = null
        rescan()
    }

    fun rescan() {
        val uri = treeUri.value ?: return
        scanning.value = true
        error.value = null
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val source = DocumentsTreeSource(getApplication<Application>().contentResolver, uri)
                    matcher.match(WalkEngine.walk(source, source.rootUri))
                }
                repo.applyScan(result)
            } catch (e: TreeAccessException) {
                error.value = "Can't read the ROM folder (${e.message}). Pick it again or check the storage."
            } catch (e: SecurityException) {
                error.value = "Access to the ROM folder was revoked. Pick it again."
            } finally {
                scanning.value = false
            }
        }
    }

    fun launchGame(game: GameEntity) {
        val player = defaultPlayerFor(players, game.platformId)
        if (player == null) {
            _message.value = "No emulator registered for ${platformNames[game.platformId] ?: game.platformId}"
            return
        }
        val spec = buildIntentSpec(player, romUri = game.uri, title = game.title)
        when (val result = LaunchController.launch(getApplication(), spec)) {
            is LaunchResult.Failure -> _message.value = result.reason
            LaunchResult.Success -> Unit
        }
    }

    fun consumeMessage() { _message.value = null }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: FAIL only in `MainActivity.kt` (it still uses the old `UiState.Library` shape and `dev.mimir.scanner.Game`). If MainViewModel itself errors, fix before proceeding.

- [ ] **Step 3: Commit (Tasks 5+6 together once MainActivity from Task 7 compiles)** — deferred; see Task 7 Step 4.

---

### Task 7: `:app` — UI rework: error screen, scanning indicator, scan report

**Files:**
- Modify: `app/src/main/kotlin/dev/mimir/app/MainActivity.kt` (full replacement below)

- [ ] **Step 1: Replace `MainActivity.kt` entirely with:**

```kotlin
package dev.mimir.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mimir.data.GameEntity
import dev.mimir.data.SkippedFileEntity

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.onFolderPicked(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MainScreen(
                    viewModel = viewModel,
                    onPickFolder = { pickFolder.launch(null) },
                )
            }
        }
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel, onPickFolder: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val message by viewModel.message.collectAsState()
    val skipped by viewModel.skipped.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showReport by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.consumeMessage() }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                UiState.NeedsFolder -> CenteredColumn {
                    Text("Mimir", style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onPickFolder) { Text("Choose ROM folder") }
                }
                is UiState.Error -> CenteredColumn {
                    Text("Something's wrong", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(s.message, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    Row {
                        Button(onClick = viewModel::rescan) { Text("Retry") }
                        Spacer(Modifier.width(12.dp))
                        OutlinedButton(onClick = onPickFolder) { Text("Change folder") }
                    }
                }
                is UiState.Library ->
                    if (showReport) ScanReportScreen(skipped, onBack = { showReport = false })
                    else LibraryGrid(
                        s,
                        onGameClick = viewModel::launchGame,
                        onRescan = viewModel::rescan,
                        onPickFolder = onPickFolder,
                        onShowReport = { showReport = true },
                    )
            }
        }
    }
}

@Composable
private fun BoxScope.CenteredColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.align(Alignment.Center).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
fun LibraryGrid(
    library: UiState.Library,
    onGameClick: (GameEntity) -> Unit,
    onRescan: () -> Unit,
    onPickFolder: () -> Unit,
    onShowReport: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${library.gamesByPlatform.values.sumOf { it.size }} games",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRescan, enabled = !library.scanning) { Text("Rescan") }
                    TextButton(onClick = onPickFolder) { Text("Change folder") }
                }
                if (library.scanning) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
        }
        library.gamesByPlatform.forEach { (platformName, games) ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(platformName, style = MaterialTheme.typography.titleLarge)
            }
            items(games, key = { it.uri }) { game ->
                Card(Modifier.height(100.dp).clickable { onGameClick(game) }) {
                    Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                        Text(game.title, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (library.skippedCount > 0) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                TextButton(onClick = onShowReport) {
                    Text("${library.skippedCount} files skipped — view scan report")
                }
            }
        }
    }
}

@Composable
fun ScanReportScreen(skipped: List<SkippedFileEntity>, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Scan report — ${skipped.size} skipped",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onBack) { Text("Back") }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(skipped, key = { it.relativePath }) { item ->
                Column(Modifier.fillMaxWidth()) {
                    Text(item.relativePath, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        item.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
```

Note the dual `items` imports: `androidx.compose.foundation.lazy.items` (LazyColumn) and the grid's `items` come from `androidx.compose.foundation.lazy.grid.*` — both are present above and do not clash (different receiver scopes).

- [ ] **Step 2: Full build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: All tests**

Run: `./gradlew test`
Expected: PASS — scanner 17, launcher 4, data 7 (28 total).

- [ ] **Step 4: Commit Tasks 5+6+7 together**

```bash
git add -A && git commit -m "feat(app): bulk DocumentsContract scanning, Room-backed library, error state, scan report"
```

---

### Task 8: `:app` — cross-registry validation test

**Files:**
- Modify: `app/build.gradle.kts` (add test deps)
- Test: `app/src/test/kotlin/dev/mimir/app/RegistryConsistencyTest.kt`

- [ ] **Step 1: Add unit-test deps to `app/build.gradle.kts`**

```kotlin
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
```

- [ ] **Step 2: Write the test (it should PASS immediately — it's a guard, verify it runs)**

`app/src/test/kotlin/dev/mimir/app/RegistryConsistencyTest.kt`:

```kotlin
package dev.mimir.app

import dev.mimir.launcher.PlayerDefs
import dev.mimir.scanner.PlatformDefs
import kotlin.test.Test
import kotlin.test.assertTrue

/** Guards the join between the two community-editable registries. */
class RegistryConsistencyTest {
    @Test
    fun `every player platformId exists in the platform registry`() {
        val platformIds = PlatformDefs.load().map { it.id }.toSet()
        val orphans = PlayerDefs.load()
            .flatMap { player -> player.platformIds.map { player.id to it } }
            .filter { (_, platformId) -> platformId !in platformIds }
        assertTrue(orphans.isEmpty(), "players.json references unknown platform ids: $orphans")
    }
}
```

- [ ] **Step 3: Run and verify it executes and passes**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, 1 test executed (check the JUnit XML exists under `app/build/test-results/` — NOT "NO-SOURCE").

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "test(app): guard players/platforms registry join"
```

---

### Task 9: End-to-end re-verification on the Android emulator

**Files:**
- Create: `docs/superpowers/plans/m2a-verification.md`
- Create: `docs/superpowers/plans/img/m2a-*.png` (screenshots)

Environment: AVD `mimir-test` exists (API 34 arm64, headless). Boot it if not running: `~/Library/Android/sdk/emulator/emulator -avd mimir-test -no-snapshot -no-audio -no-boot-anim -gpu swiftshader_indirect -no-window &`, then `adb wait-for-device` and poll `sys.boot_completed`. The M1 ROMs may still exist under /sdcard/Roms (recreate per the M1 doc if not — see docs/superpowers/plans/m1-verification.md). The app may retain its M1 folder grant; uninstall first (`adb uninstall dev.mimir.app`) for a clean slate.

- [ ] **Step 1: Install fresh builds**

```bash
adb uninstall dev.mimir.app || true
./gradlew :app:installDebug :tools:fake-emulator:installDebug
```

- [ ] **Step 2: Cold-start library + persistence check**

Launch the app, pick /sdcard/Roms via the SAF picker (drive with uiautomator dumps + input taps, same technique as M1). Verify the grid shows the M1 set (4 games / 3 platforms). Then force-stop and relaunch: `adb shell am force-stop dev.mimir.app && adb shell am start dev.mimir.app/.MainActivity`. **Verify the library renders immediately from Room** (grid visible in the first UI dump after launch, even before any rescan completes).

- [ ] **Step 3: Incremental rescan check**

```bash
adb shell touch "/sdcard/Roms/n64/Banjo-Kazooie.z64"
```
Tap Rescan. Verify: count goes 4 → 5, Banjo-Kazooie appears under Nintendo 64, NO duplicates of existing games. Then `adb shell rm "/sdcard/Roms/n64/Banjo-Kazooie.z64"`, Rescan again → back to 4, Banjo gone.

- [ ] **Step 4: Scan report check**

Tap "1 files skipped — view scan report". Verify the report screen lists `notes.txt` with the full reason text and Back returns to the grid.

- [ ] **Step 5: Error state check (revoked access)**

```bash
adb shell rm -rf /sdcard/Roms.bak; adb shell mv /sdcard/Roms /sdcard/Roms.bak
```
Tap Rescan. Verify the app does NOT crash: either the Error screen appears (TreeAccessException path) or — if the provider reports the missing dir as empty — the library goes to 0 games gracefully. Record which behavior occurred (both are acceptable; crash is not). Check `adb logcat -d | grep -E "FATAL|AndroidRuntime"` is clean. Restore: `adb shell mv /sdcard/Roms.bak /sdcard/Roms`, Rescan → 4 games return.

- [ ] **Step 6: Launch still works**

Tap "Mario Kart 64" → Fake Emulator screen appears with the content:// URI (same as M1).

- [ ] **Step 7: Record evidence + screenshots, commit**

Write `docs/superpowers/plans/m2a-verification.md` (date, AVD, each check with observed evidence; note the Step 5 behavior observed). Screenshots: `m2a-library.png` (grid with 5 games during Step 3), `m2a-report.png` (scan report screen).

```bash
git add -A && git commit -m "test: M2a persistence, incremental rescan, error state verified on emulator"
```

---

### Task 10: README status update

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Update the status paragraph**

Replace the line starting `**Status: M1 — end-to-end launch loop.**` with:

```markdown
**Status: M2a — persistent library + fast scanning.** Bulk SAF scanning (one
query per directory), Room-backed library with instant cold-start, incremental
rescans (no duplicates, no losses), a full scan-report screen for skipped
files, and graceful error states for lost folder access. Next (M2b): emulator
auto-detection, per-platform/per-game emulator choice, and custom player
definitions.
```

And in the Module map table, add after the `:core:launcher` row:

```markdown
| `:core:data` | Android library — Room persistence, diff-sync repository |
```

- [ ] **Step 2: Final full check**

Run: `./gradlew test :app:assembleDebug --rerun-tasks`
Expected: BUILD SUCCESSFUL, 29 tests pass (scanner 17, launcher 4, data 7, app 1).

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "docs: M2a status in README"
```

---

## Out of scope for M2a (M2b and later — do not add)

Emulator auto-detection (PackageManager), per-platform default player UI, per-game overrides, custom player creation UI, first-run wizard, installed-aware player resolution (fake-emulator still wins by registry order — known, accepted until M2b), scraping/art, second-screen deck, theme tokens, Navigation library (state-driven report screen is deliberate), DI framework, schema export/migrations (DB v1, destructive is fine pre-release).
