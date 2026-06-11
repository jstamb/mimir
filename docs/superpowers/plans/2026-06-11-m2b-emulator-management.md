# Mimir M2b — Emulator Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Real emulator management — installed-aware player resolution with a registry of real emulators, a front-and-center per-platform default picker, and per-game overrides via long-press — killing the last demo-ware behavior (fake-emulator winning every launch) and directly answering Cocoon complaint #2.

**Architecture:** Pure resolution logic (`PlayerResolver` + `PlayerPrefs`) lives in `:core:launcher` and is fully unit-tested: per-game override → per-platform default → first INSTALLED claimant in registry order → first claimant (with guided not-installed failure at launch). Prefs persist in Room v3 (two new tables) via the project's **first real migration** — testable because schema export started at v2. `:app` gains an installed-packages provider (QUERY_ALL_PACKAGES — sideloaded launcher, legitimate use), an Emulators settings screen, a long-press "Play with…" sheet, an app-scoped DB singleton, and error-banner-over-library. The bundled registry grows real emulators with VERIFIED package names; fake-emulator moves last so it only wins when nothing real is installed.

**Tech Stack:** Existing stack + `androidx.room:room-testing` (MigrationTestHelper under Robolectric). No new UI libraries — ModalBottomSheet and DropdownMenu are in material3.

**Conventions:** Repo `~/Local Sites/mimir`, branch from `master`. `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` before every `./gradlew`. Commits end with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`. Current totals: 41 tests (scanner 18, scraper 10, data 8, launcher 4, app 1).

---

### Task 1: `:core:launcher` — PlayerResolver + expanded registry (TDD)

**Files:**
- Create: `core/launcher/src/main/kotlin/dev/mimir/launcher/PlayerResolver.kt`
- Modify: `core/launcher/src/main/resources/players.json`
- Test: `core/launcher/src/test/kotlin/dev/mimir/launcher/PlayerResolverTest.kt`
- Test: modify `core/launcher/src/test/kotlin/dev/mimir/launcher/IntentSpecBuilderTest.kt`

- [ ] **Step 1: VERIFY emulator package names via web search before writing the registry.** For each: melonDS, DraStic, Dolphin, DuckStation, Mupen64Plus FZ, Snes9x EX+ — confirm the Android application id (Play Store URL `id=` param or F-Droid/GitHub). Expected values (verify, don't trust): `me.magnum.melonds`, `com.dsemu.drastic`, `org.dolphinemu.dolphinemu`, `com.github.stenzek.duckstation`, `org.mupen64plusae.v3.fzurita`, `com.explusalpha.Snes9xPlus`. If a package can't be confirmed from a primary source, OMIT that emulator and report it (wrong registry data = silent resolution misses; the registry is community-fixable later).

- [ ] **Step 2: Write the failing tests**

`PlayerResolverTest.kt`:
```kotlin
package dev.mimir.launcher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerResolverTest {
    private val real = PlayerDef("melonds", "melonDS", "me.magnum.melonds", platformIds = listOf("nds"))
    private val other = PlayerDef("drastic", "DraStic", "com.dsemu.drastic", platformIds = listOf("nds"))
    private val fake = PlayerDef(
        "fake-emulator", "Fake", "dev.mimir.fakeemulator",
        activityClass = "dev.mimir.fakeemulator.CatchActivity",
        platformIds = listOf("nds", "n64"),
    )
    private val players = listOf(real, other, fake) // fake LAST, like the registry

    private fun resolver(
        installed: Set<String> = emptySet(),
        prefs: PlayerPrefs = PlayerPrefs(),
    ) = PlayerResolver(players, installed, prefs)

    @Test
    fun `per-game override wins over everything`() {
        val r = resolver(
            installed = setOf("me.magnum.melonds"),
            prefs = PlayerPrefs(
                platformDefaults = mapOf("nds" to "melonds"),
                gameOverrides = mapOf("uri-x" to "drastic"),
            ),
        )
        assertEquals("drastic", r.resolve("uri-x", "nds")?.id)
    }

    @Test
    fun `platform default beats installed-order`() {
        val r = resolver(
            installed = setOf("me.magnum.melonds", "com.dsemu.drastic"),
            prefs = PlayerPrefs(platformDefaults = mapOf("nds" to "drastic")),
        )
        assertEquals("drastic", r.resolve("uri-x", "nds")?.id)
    }

    @Test
    fun `first installed claimant wins with no prefs - fake does not shadow real`() {
        val r = resolver(installed = setOf("com.dsemu.drastic", "dev.mimir.fakeemulator"))
        assertEquals("drastic", r.resolve("uri-x", "nds")?.id)
    }

    @Test
    fun `fake wins only when it is the only installed claimant`() {
        val r = resolver(installed = setOf("dev.mimir.fakeemulator"))
        assertEquals("fake-emulator", r.resolve("uri-x", "nds")?.id)
    }

    @Test
    fun `nothing installed falls back to first claimant for guided failure`() {
        assertEquals("melonds", resolver().resolve("uri-x", "nds")?.id)
    }

    @Test
    fun `unknown platform resolves to null`() {
        assertNull(resolver().resolve("uri-x", "gba"))
    }

    @Test
    fun `claimants lists registry order and isInstalled reflects the set`() {
        val r = resolver(installed = setOf("com.dsemu.drastic"))
        assertEquals(listOf("melonds", "drastic", "fake-emulator"), r.claimants("nds").map { it.id })
        assertTrue(r.isInstalled(other))
        assertTrue(!r.isInstalled(real))
    }
}
```

Add to `IntentSpecBuilderTest.kt` (registry shape guards):
```kotlin
    @Test
    fun `fake emulator is the LAST registry entry so real emulators win installed-order`() {
        assertEquals("fake-emulator", PlayerDefs.load().last().id)
    }

    @Test
    fun `registry has a real emulator for every platform the fake claims`() {
        val players = PlayerDefs.load()
        val fakePlatforms = players.first { it.id == "fake-emulator" }.platformIds
        val realClaims = players.filter { it.id != "fake-emulator" }.flatMap { it.platformIds }.toSet()
        val uncovered = fakePlatforms.filterNot { it in realClaims }
        assertTrue(uncovered.isEmpty(), "platforms with no real emulator in registry: $uncovered")
    }
```

- [ ] **Step 3: Run tests, verify FAIL** (`./gradlew :core:launcher:test`) — unresolved `PlayerResolver`/`PlayerPrefs`; registry tests fail on order/coverage.

- [ ] **Step 4: Implement**

`PlayerResolver.kt`:
```kotlin
package dev.mimir.launcher

data class PlayerPrefs(
    val platformDefaults: Map<String, String> = emptyMap(), // platformId -> playerId
    val gameOverrides: Map<String, String> = emptyMap(),    // game uri -> playerId
)

/**
 * Resolution order: per-game override -> per-platform default -> first INSTALLED
 * claimant in registry order -> first claimant (launch will show a guided
 * not-installed failure). Explicit prefs win even when uninstalled, on purpose.
 */
class PlayerResolver(
    private val players: List<PlayerDef>,
    private val installedPackages: Set<String>,
    private val prefs: PlayerPrefs,
) {
    private val byId = players.associateBy { it.id }

    fun claimants(platformId: String): List<PlayerDef> =
        players.filter { platformId in it.platformIds }

    fun isInstalled(player: PlayerDef): Boolean = player.packageName in installedPackages

    fun resolve(gameUri: String, platformId: String): PlayerDef? =
        prefs.gameOverrides[gameUri]?.let(byId::get)
            ?: prefs.platformDefaults[platformId]?.let(byId::get)
            ?: claimants(platformId).firstOrNull { isInstalled(it) }
            ?: claimants(platformId).firstOrNull()
}
```

`players.json` — rewrite with VERIFIED packages from Step 1, real emulators first, fake LAST, every platform covered by at least one real emulator (Step 1 verified set permitting — adjust coverage to what you verified; the registry-coverage test defines done):
```json
[
  {"id": "melonds", "name": "melonDS", "packageName": "me.magnum.melonds",
   "action": "android.intent.action.VIEW", "platformIds": ["nds"]},
  {"id": "drastic", "name": "DraStic", "packageName": "com.dsemu.drastic",
   "action": "android.intent.action.VIEW", "platformIds": ["nds"]},
  {"id": "mupen64-fz", "name": "Mupen64Plus FZ", "packageName": "org.mupen64plusae.v3.fzurita",
   "action": "android.intent.action.VIEW", "platformIds": ["n64"]},
  {"id": "dolphin", "name": "Dolphin", "packageName": "org.dolphinemu.dolphinemu",
   "action": "android.intent.action.VIEW", "platformIds": ["gc"]},
  {"id": "duckstation", "name": "DuckStation", "packageName": "com.github.stenzek.duckstation",
   "action": "android.intent.action.VIEW", "platformIds": ["psx"]},
  {"id": "snes9x-ex", "name": "Snes9x EX+", "packageName": "com.explusalpha.Snes9xPlus",
   "action": "android.intent.action.VIEW", "platformIds": ["snes"]},
  {"id": "nes-emu", "name": "NES.emu", "packageName": "com.explusalpha.NesEmu",
   "action": "android.intent.action.VIEW", "platformIds": ["nes"]},
  {"id": "gba-emu", "name": "GBA.emu", "packageName": "com.explusalpha.GbaEmu",
   "action": "android.intent.action.VIEW", "platformIds": ["gba"]},
  {"id": "fake-emulator", "name": "Mimir Fake Emulator",
   "packageName": "dev.mimir.fakeemulator",
   "activityClass": "dev.mimir.fakeemulator.CatchActivity",
   "action": "android.intent.action.VIEW",
   "platformIds": ["nes", "snes", "n64", "gba", "nds", "gc", "psx"]}
]
```
(NES.emu/GBA.emu packages also need Step-1 verification; omit + report any you can't confirm, keeping the coverage test satisfied by what remains — if a platform loses real coverage, remove it from the coverage expectation is NOT allowed; instead find a verified emulator for it.)

- [ ] **Step 5: Run tests, verify PASS** — launcher = 13 (4 existing + 7 resolver + 2 registry). Full `./gradlew test` → 50.

- [ ] **Step 6: Commit**
```bash
git add -A && git commit -m "feat(launcher): installed-aware player resolution; real-emulator registry, fake last"
```

---

### Task 2: `:core:data` — prefs tables, DB v3 with TESTED migration (TDD)

**Files:**
- Modify: `core/data/build.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Modify: `core/data/src/main/kotlin/dev/mimir/data/Entities.kt`
- Modify: `core/data/src/main/kotlin/dev/mimir/data/LibraryDao.kt`
- Modify: `core/data/src/main/kotlin/dev/mimir/data/MimirDatabase.kt`
- Modify: `core/data/src/main/kotlin/dev/mimir/data/GameRepository.kt`
- Test: `core/data/src/test/kotlin/dev/mimir/data/MigrationTest.kt`
- Test: modify `core/data/src/test/kotlin/dev/mimir/data/LibraryDaoTest.kt`

- [ ] **Step 1: Catalog + deps**

`[libraries]` in `gradle/libs.versions.toml`:
```toml
room-testing = { group = "androidx.room", name = "room-testing", version.ref = "room" }
androidx-test-runner = { group = "androidx.test", name = "runner", version = "1.7.0" }
```
`core/data/build.gradle.kts` dependencies:
```kotlin
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.test.runner)
```

CRITICAL: MigrationTestHelper loads exported schema JSONs from TEST ASSETS — without this, `createDatabase(..., 2)` fails with "Cannot find schema file". Add inside the `android { }` block of `core/data/build.gradle.kts`:
```kotlin
    sourceSets {
        getByName("test") {
            assets.srcDir("$projectDir/schemas")
        }
    }
```
(If AGP 9's DSL rejects that exact form, the equivalent `android.sourceSets["test"].assets.srcDirs(...)` spelling is fine — report which compiled.)

- [ ] **Step 2: Write the failing tests**

`MigrationTest.kt`:
```kotlin
package dev.mimir.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MimirDatabase::class.java,
    )

    @Test
    fun `migrate 2 to 3 preserves games and media`() {
        helper.createDatabase("migration-test", 2).apply {
            execSQL(
                "INSERT INTO games (uri, title, platformId, relativePath, lastModified) " +
                    "VALUES ('uri-m', 'Mario Kart 64', 'n64', 'n64/Mario Kart 64.z64', 10)"
            )
            execSQL("INSERT INTO media (gameUri, boxartUrl) VALUES ('uri-m', 'https://example.test/mk64.png')")
            close()
        }
        val db = helper.runMigrationsAndValidate("migration-test", 3, true, MIGRATION_2_3)
        db.query("SELECT COUNT(*) FROM games").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.query("SELECT COUNT(*) FROM media").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.query("SELECT COUNT(*) FROM platform_prefs").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        db.query("SELECT COUNT(*) FROM game_prefs").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
    }
}
```

Add to `LibraryDaoTest.kt`:
```kotlin
    @Test
    fun `prefs round trip and game override cascade-deletes with its game`() = runBlocking {
        val dao = db().libraryDao()
        dao.setPlatformPref(PlatformPrefEntity("nds", "melonds"))
        dao.setPlatformPref(PlatformPrefEntity("nds", "drastic")) // replace
        assertEquals(listOf(PlatformPrefEntity("nds", "drastic")), dao.platformPrefs().first())

        dao.upsertGames(listOf(GameEntity("uri-g", "Game", "nds", "nds/Game.nds", 1)))
        dao.setGamePref(GamePrefEntity("uri-g", "melonds"))
        assertEquals(listOf(GamePrefEntity("uri-g", "melonds")), dao.gamePrefs().first())

        dao.clearGamePref("uri-g")
        assertEquals(emptyList<GamePrefEntity>(), dao.gamePrefs().first())

        dao.setGamePref(GamePrefEntity("uri-g", "melonds"))
        dao.deleteGames(listOf("uri-g"))
        assertEquals(emptyList<GamePrefEntity>(), dao.gamePrefs().first())
    }
```

Run: `./gradlew :core:data:testDebugUnitTest` → FAIL (unresolved entities/MIGRATION_2_3/DAO methods).

- [ ] **Step 3: Implement**

`Entities.kt` — add:
```kotlin
@Entity(tableName = "platform_prefs")
data class PlatformPrefEntity(
    @PrimaryKey val platformId: String,
    val playerId: String,
)

@Entity(
    tableName = "game_prefs",
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["uri"],
            childColumns = ["gameUri"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class GamePrefEntity(
    @PrimaryKey val gameUri: String,
    val playerId: String,
)
```

`LibraryDao.kt` — add:
```kotlin
    @Query("SELECT * FROM platform_prefs")
    fun platformPrefs(): Flow<List<PlatformPrefEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setPlatformPref(pref: PlatformPrefEntity)

    @Query("SELECT * FROM game_prefs")
    fun gamePrefs(): Flow<List<GamePrefEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setGamePref(pref: GamePrefEntity)

    @Query("DELETE FROM game_prefs WHERE gameUri = :gameUri")
    suspend fun clearGamePref(gameUri: String)
```

`MimirDatabase.kt` — entities list gains `PlatformPrefEntity::class, GamePrefEntity::class`, `version = 3`, and add the migration (top-level in the same file, with imports `androidx.room.migration.Migration` and `androidx.sqlite.db.SupportSQLiteDatabase`):
```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `platform_prefs` " +
                "(`platformId` TEXT NOT NULL, `playerId` TEXT NOT NULL, PRIMARY KEY(`platformId`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `game_prefs` " +
                "(`gameUri` TEXT NOT NULL, `playerId` TEXT NOT NULL, PRIMARY KEY(`gameUri`), " +
                "FOREIGN KEY(`gameUri`) REFERENCES `games`(`uri`) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
    }
}
```
(`runMigrationsAndValidate` diffs the migrated schema against the exported v3 JSON — if validation fails on FK/index details, fix the SQL to match what Room expects from `schemas/.../3.json`, never weaken the test. The new `schemas/dev.mimir.data.MimirDatabase/3.json` gets committed.)

`GameRepository.kt` — add:
```kotlin
    val platformPrefs: Flow<List<PlatformPrefEntity>> = dao.platformPrefs()
    val gamePrefs: Flow<List<GamePrefEntity>> = dao.gamePrefs()

    suspend fun setPlatformDefault(platformId: String, playerId: String) =
        dao.setPlatformPref(PlatformPrefEntity(platformId, playerId))

    suspend fun setGameOverride(gameUri: String, playerId: String) =
        dao.setGamePref(GamePrefEntity(gameUri, playerId))

    suspend fun clearGameOverride(gameUri: String) = dao.clearGamePref(gameUri)
```

- [ ] **Step 4: Run tests, verify PASS** — data = 10 (8 existing + 1 migration + 1 prefs). Commit:
```bash
git add -A && git commit -m "feat(data): player prefs tables, DB v3 with tested 2->3 migration"
```

---

### Task 3: `:app` — singleton DB, installed packages, ViewModel resolution + banner

**Files:**
- Create: `app/src/main/kotlin/dev/mimir/app/MimirApp.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/kotlin/dev/mimir/app/MainViewModel.kt`

- [ ] **Step 1: `MimirApp.kt` + manifest**

```kotlin
package dev.mimir.app

import android.app.Application
import androidx.room.Room
import dev.mimir.data.MIGRATION_2_3
import dev.mimir.data.MimirDatabase

class MimirApp : Application() {
    val db: MimirDatabase by lazy {
        Room.databaseBuilder(this, MimirDatabase::class.java, "mimir.db")
            .addMigrations(MIGRATION_2_3)
            .fallbackToDestructiveMigration(dropAllTables = true) // pre-release safety net for v1 installs only
            .build()
    }
}
```

Manifest: add inside `<application ...>` the attribute `android:name=".MimirApp"`, and add after the INTERNET permission:
```xml
    <!-- Launcher needs to see which emulators are installed; app is sideload-distributed. -->
    <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />
```

- [ ] **Step 2: Rework `MainViewModel.kt`**

Replace the `db` property with the singleton and add installed-package + prefs plumbing. The full set of changes:

Imports to add:
```kotlin
import dev.mimir.data.GamePrefEntity
import dev.mimir.data.PlatformPrefEntity
import dev.mimir.launcher.PlayerDef
import dev.mimir.launcher.PlayerPrefs
import dev.mimir.launcher.PlayerResolver
import kotlinx.coroutines.flow.map
```
(Remove the now-unused `androidx.room.Room` import and `defaultPlayerFor` import.)

Replace `private val db = Room.databaseBuilder(...)...build()` with:
```kotlin
    private val db = (app as MimirApp).db
```

Add after `private val players = PlayerDefs.load()`:
```kotlin
    private fun installedPackages(): Set<String> =
        getApplication<Application>().packageManager.getInstalledPackages(0)
            .mapTo(mutableSetOf()) { it.packageName }

    private val playerPrefs = combine(repo.platformPrefs, repo.gamePrefs) { platform, game ->
        PlayerPrefs(
            platformDefaults = platform.associate { it.platformId to it.playerId },
            gameOverrides = game.associate { it.gameUri to it.playerId },
        )
    }

    /** Snapshot resolver for one-shot decisions (launch, settings render). */
    private fun resolver(prefs: PlayerPrefs) = PlayerResolver(players, installedPackages(), prefs)

    val prefsState: StateFlow<PlayerPrefs> =
        playerPrefs.stateIn(viewModelScope, SharingStarted.Eagerly, PlayerPrefs())

    /** platformId -> claimant players, for the settings screen. */
    fun claimantsByPlatform(): Map<String, List<PlayerDef>> =
        platforms.associate { p -> p.id to players.filter { p.id in it.platformIds } }

    fun platformsForSettings(): List<Pair<String, String>> = platforms.map { it.id to it.name }

    fun isPlayerInstalled(player: PlayerDef): Boolean =
        player.packageName in installedPackages()

    fun setPlatformDefault(platformId: String, playerId: String) {
        viewModelScope.launch { repo.setPlatformDefault(platformId, playerId) }
    }

    fun setGameOverride(game: GameEntity, playerId: String) {
        viewModelScope.launch { repo.setGameOverride(game.uri, playerId) }
    }

    fun clearGameOverride(game: GameEntity) {
        viewModelScope.launch { repo.clearGameOverride(game.uri) }
    }
```

Replace `launchGame` with resolver-based resolution + a clearer not-installed message:
```kotlin
    fun launchGame(game: GameEntity, forced: PlayerDef? = null) {
        val player = forced ?: resolver(prefsState.value).resolve(game.uri, game.platformId)
        if (player == null) {
            _message.value = "No emulator registered for ${platformNames[game.platformId] ?: game.platformId}"
            return
        }
        if (!isPlayerInstalled(player)) {
            _message.value = "${player.name} is not installed — install it or pick another emulator in Settings"
            return
        }
        val spec = buildIntentSpec(player, romUri = game.uri, title = game.title)
        when (val result = LaunchController.launch(getApplication(), spec)) {
            is LaunchResult.Failure -> _message.value = result.reason
            LaunchResult.Success -> Unit
        }
    }
```

Error-banner-over-library: change `UiState.Library` to add `val banner: String? = null`, and in the outer `state` combine, replace the `when` with:
```kotlin
            when {
                uri == null -> UiState.NeedsFolder
                st.error != null && lib.games.isEmpty() -> UiState.Error(st.error)
                else -> UiState.Library(
                    gamesByPlatform = lib.games
                        .groupBy { platformNames[it.platformId] ?: it.platformId }
                        .toSortedMap(),
                    art = lib.art,
                    skippedCount = skip.size,
                    scanning = st.scanning,
                    scraping = st.scraping,
                    banner = st.error,
                )
            }
```
(Full-screen Error remains for the empty-library case; with games present, the error rides as a banner.)

- [ ] **Step 3: Compile check** — `./gradlew :app:compileDebugKotlin`: MainViewModel must compile; MainActivity may fail on the new `banner` field only if it constructs Library (it doesn't) — expect SUCCESS. No commit yet (Tasks 3+4 commit together).

---

### Task 4: `:app` — settings screen, long-press sheet, banner UI

**Files:**
- Create: `app/src/main/kotlin/dev/mimir/app/EmulatorSettingsScreen.kt`
- Modify: `app/src/main/kotlin/dev/mimir/app/MainActivity.kt`

- [ ] **Step 1: `EmulatorSettingsScreen.kt`**

```kotlin
package dev.mimir.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mimir.launcher.PlayerDef
import dev.mimir.launcher.PlayerPrefs

@Composable
fun EmulatorSettingsScreen(
    platforms: List<Pair<String, String>>, // id to display name
    claimants: Map<String, List<PlayerDef>>,
    prefs: PlayerPrefs,
    isInstalled: (PlayerDef) -> Boolean,
    onSetDefault: (platformId: String, playerId: String) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Emulators", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Back") }
        }
        Text(
            "Default emulator per system. Per-game overrides: long-press any game.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(platforms, key = { it.first }) { (platformId, platformName) ->
                PlatformDefaultRow(
                    platformName = platformName,
                    claimants = claimants[platformId].orEmpty(),
                    selectedId = prefs.platformDefaults[platformId],
                    isInstalled = isInstalled,
                    onSelect = { onSetDefault(platformId, it) },
                )
            }
        }
    }
}

@Composable
private fun PlatformDefaultRow(
    platformName: String,
    claimants: List<PlayerDef>,
    selectedId: String?,
    isInstalled: (PlayerDef) -> Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val effective = claimants.firstOrNull { it.id == selectedId }
        ?: claimants.firstOrNull { isInstalled(it) }
        ?: claimants.firstOrNull()
    Card {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(platformName, style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        effective == null -> "No emulators registered"
                        selectedId != null -> "Default: ${effective.name}"
                        else -> "Auto: ${effective.name}"
                    } + if (effective != null && !isInstalled(effective)) " (not installed)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (effective != null && !isInstalled(effective))
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                TextButton(onClick = { expanded = true }, enabled = claimants.isNotEmpty()) { Text("Change") }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    claimants.forEach { player ->
                        DropdownMenuItem(
                            text = {
                                Text(player.name + if (!isInstalled(player)) "  (not installed)" else "")
                            },
                            onClick = { expanded = false; onSelect(player.id) },
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: MainActivity wiring**

Imports to add:
```kotlin
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import dev.mimir.launcher.PlayerDef
```

In `MainScreen`, add state + collect prefs (after `var showReport ...`):
```kotlin
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var sheetGame by remember { mutableStateOf<GameEntity?>(null) }
    val prefs by viewModel.prefsState.collectAsState()
```

In the `is UiState.Library ->` branch, route between report/settings/grid:
```kotlin
                is UiState.Library -> when {
                    showReport -> ScanReportScreen(skipped, onBack = { showReport = false })
                    showSettings -> EmulatorSettingsScreen(
                        platforms = viewModel.platformsForSettings(),
                        claimants = viewModel.claimantsByPlatform(),
                        prefs = prefs,
                        isInstalled = viewModel::isPlayerInstalled,
                        onSetDefault = viewModel::setPlatformDefault,
                        onBack = { showSettings = false },
                    )
                    else -> LibraryGrid(
                        s,
                        onGameClick = { viewModel.launchGame(it) },
                        onGameLongClick = { sheetGame = it },
                        onRescan = viewModel::rescan,
                        onPickFolder = onPickFolder,
                        onShowReport = { showReport = true },
                        onShowSettings = { showSettings = true },
                        onFetchArtwork = viewModel::fetchArtwork,
                    )
                }
```

After the `when` block (still inside the Scaffold padding Box), add the bottom sheet:
```kotlin
            sheetGame?.let { game ->
                PlayWithSheet(
                    game = game,
                    claimants = viewModel.claimantsByPlatform()[game.platformId].orEmpty(),
                    overrideId = prefs.gameOverrides[game.uri],
                    isInstalled = viewModel::isPlayerInstalled,
                    onPick = { player ->
                        viewModel.setGameOverride(game, player.id)
                        sheetGame = null
                        viewModel.launchGame(game, forced = player)
                    },
                    onClearOverride = { viewModel.clearGameOverride(game); sheetGame = null },
                    onDismiss = { sheetGame = null },
                )
            }
```

Add the sheet composable (same file):
```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayWithSheet(
    game: GameEntity,
    claimants: List<PlayerDef>,
    overrideId: String?,
    isInstalled: (PlayerDef) -> Boolean,
    onPick: (PlayerDef) -> Unit,
    onClearOverride: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Play \"${game.title}\" with…", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            claimants.forEach { player ->
                ListItem(
                    headlineContent = {
                        Text(
                            player.name +
                                (if (player.id == overrideId) "  ✓ current" else "") +
                                (if (!isInstalled(player)) "  (not installed)" else "")
                        )
                    },
                    modifier = Modifier.clickable { onPick(player) },
                )
            }
            if (overrideId != null) {
                TextButton(onClick = onClearOverride) { Text("Clear override — use system default") }
            }
        }
    }
}
```
(`ListItem` and `clickable` need imports: `androidx.compose.material3.ListItem` is already under material3's wildcard if used; `androidx.compose.foundation.clickable` is already imported in this file.)

`LibraryGrid` signature gains `onGameLongClick: (GameEntity) -> Unit` and `onShowSettings: () -> Unit`. In the header Row add (before Rescan):
```kotlin
                    TextButton(onClick = onShowSettings) { Text("Emulators") }
```
Change the `GameCard(...)` call to pass long-press through, and update `GameCard`:
```kotlin
            items(games, key = { it.uri }) { game ->
                GameCard(
                    game,
                    artUrl = library.art[game.uri],
                    onClick = { onGameClick(game) },
                    onLongClick = { onGameLongClick(game) },
                )
            }
```
```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameCard(game: GameEntity, artUrl: String?, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(Modifier.height(180.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
```
(rest of GameCard body unchanged.)

Banner UI — in `LibraryGrid`, directly under the scanning indicator inside the header `Column`:
```kotlin
                if (library.banner != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                library.banner,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onRescan) { Text("Retry") }
                        }
                    }
                }
```

- [ ] **Step 3: Build + tests** — `./gradlew :app:assembleDebug test` → Expected: **52 tests**, 0 failures (scanner 18, scraper 10, data 10, launcher 13, app 1).

- [ ] **Step 4: Commit (Tasks 3+4)**
```bash
git add -A && git commit -m "feat(app): emulator settings, per-game play-with sheet, installed-aware launching, error banner"
```

---

### Task 5: ArtMatcher Demo/Kiosk deprioritization (TDD)

**Files:**
- Modify: `core/scraper/src/main/kotlin/dev/mimir/scraper/ArtMatcher.kt`
- Test: modify `core/scraper/src/test/kotlin/dev/mimir/scraper/ArtMatcherTest.kt`

- [ ] **Step 1: Failing test** (add to ArtMatcherTest; also add the two filenames to the fixture `listing` list):

Add to the `listing` list:
```kotlin
        "Mario Kart DS (USA) (Demo) (Kiosk).png",
        "Metroid Prime (USA) (Kiosk).png",
```
New tests:
```kotlin
    @Test
    fun `retail release beats Demo-Kiosk variants in tie-break`() {
        assertEquals("Mario Kart DS (USA) (En,Fr,De,Es,It).png", matcher.bestMatch("Mario Kart DS"))
    }

    @Test
    fun `demo variant still matches when it is the only candidate`() {
        assertEquals("Metroid Prime (USA) (Kiosk).png", matcher.bestMatch("Metroid Prime"))
    }
```

Run `./gradlew :core:scraper:test` → first new test FAILS (Demo wins shortest-name tie-break today).

- [ ] **Step 2: Fix** — in `ArtMatcher.bestMatch`, change the sort to penalize demo-ish tags first:
```kotlin
        val demoTags = listOf("(demo", "(kiosk", "(beta", "(proto", "(sample")
        return tier.sortedWith(
            compareBy<Candidate> { c -> demoTags.any { it in c.fileName.lowercase() } }
                .thenByDescending { "(usa)" in it.fileName.lowercase() }
                .thenBy { it.fileName.length }
        ).first().fileName
```

Run `./gradlew :core:scraper:test` → PASS (12 scraper tests). Commit:
```bash
git add -A && git commit -m "fix(scraper): deprioritize Demo/Kiosk/Beta boxart variants in tie-break"
```

---

### Task 6: E2E on the emulator

**Files:**
- Create: `docs/superpowers/plans/m2b-verification.md`
- Create: `docs/superpowers/plans/img/m2b-settings.png`, `img/m2b-sheet.png`

Device: emulator-5556 (Pixel_10_Pro) — boot headless if not running (see m2a-verification.md for flags). It has the v0.3.0-m3 app WITH library + art data — perfect for the migration check. uiautomator dumps via grep (host pyexpat broken).

- [ ] **Step 1: MIGRATION CHECK (do this FIRST, before any reinstall):** install the new build OVER the old one (`ANDROID_SERIAL=emulator-5556 ./gradlew :app:installDebug` — upgrade keeps data), launch, and verify the library + boxart appear WITHOUT a wipe (4 games, art present immediately, no rescan needed for them to exist). This proves MIGRATION_2_3 ran non-destructively on real data. If the library is empty → migration fell back to destructive → STOP, report BLOCKED with logcat.
- [ ] **Step 2: Settings flow:** tap "Emulators" → screenshot (`m2b-settings.png`). Verify all 7 platforms listed; NDS row shows an Auto/Default line; tap Change on Nintendo DS → dropdown shows melonDS + DraStic "(not installed)" + Mimir Fake Emulator; select **melonDS**. Then verify persistence: force-stop, relaunch, reopen Emulators → NDS still shows "Default: melonDS (not installed)".
- [ ] **Step 3: Guided not-installed failure:** tap "Mario Kart DS" → snackbar "melonDS is not installed — install it or pick another emulator in Settings". No crash, no fake-emulator launch (the explicit pref wins on purpose).
- [ ] **Step 4: Per-game override:** long-press "Mario Kart DS" → sheet appears (screenshot `m2b-sheet.png`) → pick "Mimir Fake Emulator" → Fake Emulator opens with the content URI (override + launch in one tap). Back; long-press again → "✓ current" marks fake; "Clear override" row present.
- [ ] **Step 5: Resolution sanity for unset platforms:** tap "Mario Kart 64" (no prefs set for n64) → fake-emulator catches it (only installed claimant). Logcat clean of FATAL throughout.
- [ ] **Step 6: Record evidence, commit:**
```bash
git add -A && git commit -m "test: M2b emulator management and non-destructive migration verified on emulator"
```

---

### Task 7: README + version

**Files:**
- Modify: `README.md`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1:** README status paragraph → replace the `**Status: M3 — boxart.**` paragraph with:
```markdown
**Status: M2b — emulator management.** Per-system default emulators (front and
center in Settings → Emulators), per-game overrides via long-press, and
installed-aware resolution over a registry of real emulators — any emulator the
registry doesn't know can still be added (custom player definitions land next).
Boxart, persistent library, fast scanning, and scan diagnostics already in.
```
`app/build.gradle.kts`: `versionCode = 4`, `versionName = "0.4.0-m2b"`.

- [ ] **Step 2:** Final check `./gradlew test :app:assembleDebug --rerun-tasks` → Expected: **54 tests**, 0 failures (scanner 18, scraper 12, data 10, launcher 13, app 1). Commit:
```bash
git add -A && git commit -m "docs: M2b status, version 0.4.0-m2b"
```

---

## Out of scope for M2b (do not add)

Custom player creation UI (M2c — the data model + resolver already support it), RetroArch core arguments, emulator install deep-links (Play/F-Droid), settings for anything besides emulators, Navigation library, DI framework, scraping tiers 2+, dual-screen anything, button remapping.
