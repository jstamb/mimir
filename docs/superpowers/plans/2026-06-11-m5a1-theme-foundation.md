# Mimir M5a-1 — Theme Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The invisible half of the theme system: a `:core:theme` module (slotted ThemeConfig + DataStore persistence + Compose provider), the ambient palette engine (art → palette, memoized, animated), Room v5 (media `kind`+`source`, games `lastPlayedAt`), and priority-aware art storage — plus one visible payoff: the existing UI tints itself from the most recently played game's art.

**Architecture:** Per spec §3 (docs/superpowers/specs/2026-06-11-m5-theme-system-design.md). `:core:theme` is an Android library (Compose-enabled) whose logic (slot merge, pack application, swatch choice, LRU) is pure and unit-tested; only the DataStore/Palette wrappers touch Android. Art priority (folder > esde > sgdb > libretro) is enforced at write time by a pure `ArtPriority` rule in `:core:data`. No new screens in this wave — M5a-3 builds them on these tokens.

**Conventions:** Repo `~/Local Sites/mimir`, branch from `master` (v0.6.0-m4a, 63 tests). `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`. Commits end with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`. Known KSP quirk: first :core:data test run after a schema bump may fail on the missing new schema JSON — rerun once.

---

### Task 1: Catalog + module registration

**Files:** `gradle/libs.versions.toml`, `settings.gradle.kts`, root `build.gradle.kts` (no change needed — android-library + kotlin-compose already registered), `core/theme/build.gradle.kts` (new, empty stub)

- [ ] **Step 1:** Catalog additions:
```toml
[versions]
palette = "1.0.0"
datastore = "1.2.0"
[libraries]
androidx-palette = { group = "androidx.palette", name = "palette-ktx", version.ref = "palette" }
datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
```
(Version fallback rule: if either fails to resolve, check latest stable on Google Maven and adjust — report.)
- [ ] **Step 2:** `settings.gradle.kts`: `include(":core:theme")` after `:core:scraper`; create empty `core/theme/build.gradle.kts`.
- [ ] **Step 3:** `./gradlew help` → BUILD SUCCESSFUL. Commit `chore: palette/datastore deps, :core:theme module stub`.

---

### Task 2: `:core:data` — Room v5: media kind+source, games lastPlayedAt (TDD)

**Files:** `core/data/src/main/kotlin/dev/mimir/data/{Entities,LibraryDao,MimirDatabase,GameRepository,ArtPriority}.kt`, tests `MigrationTest.kt`, `LibraryDaoTest.kt`, new `ArtPriorityTest.kt`

- [ ] **Step 1: Failing tests.**

`ArtPriorityTest.kt`:
```kotlin
package dev.mimir.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtPriorityTest {
    @Test
    fun `priority order is folder over esde over sgdb over libretro`() {
        assertTrue(ArtPriority.canReplace(existing = "libretro", incoming = "sgdb"))
        assertTrue(ArtPriority.canReplace(existing = "sgdb", incoming = "esde"))
        assertTrue(ArtPriority.canReplace(existing = "esde", incoming = "folder"))
        assertFalse(ArtPriority.canReplace(existing = "folder", incoming = "libretro"))
        assertFalse(ArtPriority.canReplace(existing = "esde", incoming = "sgdb"))
    }

    @Test
    fun `same source can refresh itself and unknown sources lose to known`() {
        assertTrue(ArtPriority.canReplace(existing = "sgdb", incoming = "sgdb"))
        assertFalse(ArtPriority.canReplace(existing = "libretro", incoming = "mystery"))
        assertTrue(ArtPriority.canReplace(existing = "mystery", incoming = "libretro"))
    }
}
```

Add to `MigrationTest.kt`:
```kotlin
    @Test
    fun `migrate 4 to 5 backfills media kind-source and adds lastPlayedAt`() {
        helper.createDatabase("migration-test-5", 4).apply {
            execSQL(
                "INSERT INTO games (uri, title, platformId, relativePath, lastModified) " +
                    "VALUES ('uri-m', 'Mario Kart 64', 'n64', 'n64/mk64.z64', 10)"
            )
            execSQL("INSERT INTO media (gameUri, boxartUrl) VALUES ('uri-m', 'https://x/mk64.png')")
            close()
        }
        val db = helper.runMigrationsAndValidate("migration-test-5", 5, true, MIGRATION_4_5)
        db.query("SELECT kind, source, boxartUrl FROM media WHERE gameUri='uri-m'").use { c ->
            c.moveToFirst()
            assertEquals("boxart", c.getString(0)); assertEquals("libretro", c.getString(1)); assertEquals("https://x/mk64.png", c.getString(2))
        }
        db.query("SELECT COUNT(*) FROM play_state").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
    }
```

Add to `LibraryDaoTest.kt`:
```kotlin
    @Test
    fun `art priority enforced on save and recents ordered by lastPlayedAt`() = runBlocking {
        val dao = db().libraryDao()
        val repo = GameRepository(dao)
        dao.upsertGames(listOf(
            GameEntity("uri-a", "A", "n64", "n64/a.z64", 1),
            GameEntity("uri-b", "B", "n64", "n64/b.z64", 1),
        ))
        repo.saveArt(listOf(MediaEntity("uri-a", "u-libretro", "boxart", "libretro")))
        repo.saveArt(listOf(MediaEntity("uri-a", "u-folder", "boxart", "folder")))
        repo.saveArt(listOf(MediaEntity("uri-a", "u-sgdb", "boxart", "sgdb"))) // must NOT downgrade folder
        assertEquals("u-folder", dao.media().first().single { it.gameUri == "uri-a" && it.kind == "boxart" }.boxartUrl)

        dao.stampPlayed("uri-b", 2000); dao.stampPlayed("uri-a", 1000)
        assertEquals(listOf("uri-b", "uri-a"), dao.recentGames(8).map { it.uri })
    }
```

Run `:core:data:testDebugUnitTest` → FAIL.

- [ ] **Step 2: Implement.**

`Entities.kt` — `MediaEntity` becomes (composite key: one row per game per kind):
```kotlin
@Entity(
    tableName = "media",
    primaryKeys = ["gameUri", "kind"],
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["uri"],
            childColumns = ["gameUri"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class MediaEntity(
    val gameUri: String,
    val boxartUrl: String,   // image uri for this kind (name kept for compatibility; semantically "url")
    val kind: String = "boxart",     // boxart | hero | logo
    val source: String = "libretro", // folder | esde | sgdb | libretro
)
```
**Play state gets its OWN table — do NOT add a column to GameEntity.** (Rationale: DiffEngine compares full entity equality on rescan; a `lastPlayedAt` field on GameEntity would make every played game look "changed" and get re-upserted with null, wiping playtime on every rescan. A separate table sidesteps DiffEngine entirely.) Add to `Entities.kt`:
```kotlin
@Entity(
    tableName = "play_state",
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["uri"],
            childColumns = ["gameUri"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class PlayStateEntity(
    @PrimaryKey val gameUri: String,
    val lastPlayedAt: Long,
)
```

`ArtPriority.kt`:
```kotlin
package dev.mimir.data

object ArtPriority {
    private val rank = mapOf("folder" to 4, "esde" to 3, "sgdb" to 2, "libretro" to 1)
    /** True when [incoming] may overwrite [existing] (>= so a source can refresh itself). */
    fun canReplace(existing: String, incoming: String): Boolean =
        (rank[incoming] ?: 0) >= (rank[existing] ?: 0)
}
```

`LibraryDao.kt` — change/add:
```kotlin
    @Query("SELECT * FROM media WHERE gameUri = :gameUri AND kind = :kind")
    suspend fun mediaFor(gameUri: String, kind: String): MediaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlayState(state: PlayStateEntity)

    @Query(
        "SELECT g.* FROM games g INNER JOIN play_state p ON g.uri = p.gameUri " +
            "ORDER BY p.lastPlayedAt DESC LIMIT :limit"
    )
    suspend fun recentGames(limit: Int): List<GameEntity>

    @Query("SELECT * FROM play_state")
    fun playStates(): Flow<List<PlayStateEntity>>
```
AND fix the now-stale `gamesWithoutArt` query — games can have hero/logo rows without boxart, so the join must be kind-scoped:
```kotlin
    @Query("SELECT g.* FROM games g LEFT JOIN media m ON g.uri = m.gameUri AND m.kind = 'boxart' WHERE m.gameUri IS NULL")
    suspend fun gamesWithoutArt(): List<GameEntity>
```
(`insertMedia` keeps REPLACE — priority is enforced in the repository, which is the only writer. The dao test's `dao.stampPlayed("uri-b", 2000)` calls become `dao.upsertPlayState(PlayStateEntity("uri-b", 2000))` — adjust the test snippet accordingly when writing it.)

`GameRepository.kt` — `saveArt` becomes priority-aware:
```kotlin
    suspend fun saveArt(items: List<MediaEntity>) {
        val accepted = items.filter { item ->
            val existing = dao.mediaFor(item.gameUri, item.kind)
            existing == null || ArtPriority.canReplace(existing.source, item.source)
        }
        if (accepted.isNotEmpty()) dao.insertMedia(accepted)
    }

    val playStates: Flow<List<PlayStateEntity>> = dao.playStates()
    suspend fun stampPlayed(uri: String, at: Long) = dao.upsertPlayState(PlayStateEntity(uri, at))
    suspend fun recentGames(limit: Int = 8): List<GameEntity> = dao.recentGames(limit)
```

`MimirDatabase.kt` — entities list gains `PlayStateEntity::class` (MediaEntity stays listed — its shape changed), `version = 5`, and:
```kotlin
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `play_state` " +
                "(`gameUri` TEXT NOT NULL, `lastPlayedAt` INTEGER NOT NULL, PRIMARY KEY(`gameUri`), " +
                "FOREIGN KEY(`gameUri`) REFERENCES `games`(`uri`) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `media_new` " +
                "(`gameUri` TEXT NOT NULL, `boxartUrl` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL DEFAULT 'boxart', `source` TEXT NOT NULL DEFAULT 'libretro', " +
                "PRIMARY KEY(`gameUri`, `kind`), " +
                "FOREIGN KEY(`gameUri`) REFERENCES `games`(`uri`) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL("INSERT INTO media_new (gameUri, boxartUrl) SELECT gameUri, boxartUrl FROM media")
        db.execSQL("DROP TABLE media")
        db.execSQL("ALTER TABLE media_new RENAME TO media")
    }
}
```
(Validation against the exported 5.json is the arbiter — adjust SQL details to match, never weaken tests. Commit schemas/5.json.)

**Call-site sweep (compile will point at them):** `ArtScraper` constructs `MediaEntity(game.uri, url)` → add `source = "libretro"`; `importEsdeMedia` → `source = "esde"`; `MainViewModel.launchGame` on `LaunchResult.Success` adds `viewModelScope.launch { repo.stampPlayed(game.uri, System.currentTimeMillis()) }`; UI `library.art` map built from media flow must now filter `kind == "boxart"` (MainViewModel `libraryData` combine: `media.filter { it.kind == "boxart" }.associate { ... }`). `MimirApp` adds `MIGRATION_4_5`.

- [ ] **Step 3:** Tests green: data = 16 (12 + 1 migration + 1 dao + 2 priority). Full `./gradlew test :app:assembleDebug` green (app compiles with sweep changes). Commit `feat(data): media kinds and sources with priority, lastPlayedAt, DB v5`.

---

### Task 3: `:core:theme` — ThemeConfig, slots, pack application (TDD)

**Files:** `core/theme/build.gradle.kts`, `core/theme/src/main/AndroidManifest.xml`, `core/theme/src/main/kotlin/dev/mimir/theme/{ThemeConfig,ThemePack}.kt`, test `ThemePackTest.kt`

- [ ] **Step 1:** `core/theme/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "dev.mimir.theme"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    buildFeatures { compose = true }
}
dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.serialization.json)
    implementation(libs.androidx.palette)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    // AGP built-in Kotlin: pin the JUnit variant (see core/data comment)
    testImplementation(kotlin("test-junit"))
}
```
Manifest: `<manifest />` stub.

- [ ] **Step 2: Failing tests.** `ThemePackTest.kt`:
```kotlin
package dev.mimir.theme

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ThemePackTest {
    @Test
    fun `default config is ambient with sane slots`() {
        val c = ThemeConfig()
        assertEquals(PaletteMode.AMBIENT, c.paletteMode)
        assertEquals(TileShape.ROUNDED, c.tiles.shape)
        assertEquals(HapticLevel.SUBTLE, c.haptics)
    }

    @Test
    fun `applying a pack overrides only the slots it defines and records provenance`() {
        val pack = Json.decodeFromString<ThemePack>(
            """{"id":"crt-pack","name":"CRT","tiles":{"shape":"sharp","density":"compact","glow":true}}"""
        )
        val merged = ThemeConfig().applying(pack)
        assertEquals(TileShape.SHARP, merged.tiles.shape)
        assertEquals(PaletteMode.AMBIENT, merged.paletteMode)      // untouched slot
        assertEquals("crt-pack", merged.provenance["tiles"])       // provenance recorded
        assertEquals(null, merged.provenance["paletteMode"])
    }

    @Test
    fun `mixing two packs keeps per-slot provenance`() {
        val tilesPack = Json.decodeFromString<ThemePack>("""{"id":"a","name":"A","tiles":{"shape":"pill","density":"cozy","glow":false}}""")
        val soundPack = Json.decodeFromString<ThemePack>("""{"id":"b","name":"B","soundPackUri":"content://packs/b"}""")
        val merged = ThemeConfig().applying(tilesPack).applying(soundPack)
        assertEquals(TileShape.PILL, merged.tiles.shape)
        assertEquals("content://packs/b", merged.soundPackUri)
        assertEquals("a", merged.provenance["tiles"])
        assertEquals("b", merged.provenance["soundPackUri"])
    }

    @Test
    fun `config round-trips through json`() {
        val c = ThemeConfig(paletteMode = PaletteMode.FIXED, fixedSeedArgb = 0xFF3B8FE2.toInt())
        assertEquals(c, Json.decodeFromString<ThemeConfig>(Json.encodeToString(ThemeConfig.serializer(), c)))
    }
}
```

- [ ] **Step 3: Implement.** `ThemeConfig.kt`:
```kotlin
package dev.mimir.theme

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable enum class PaletteMode { @SerialName("ambient") AMBIENT, @SerialName("fixed") FIXED }
@Serializable enum class TileShape { @SerialName("rounded") ROUNDED, @SerialName("sharp") SHARP, @SerialName("pill") PILL }
@Serializable enum class TileDensity { @SerialName("cozy") COZY, @SerialName("compact") COMPACT }
@Serializable enum class HapticLevel { @SerialName("off") OFF, @SerialName("subtle") SUBTLE, @SerialName("strong") STRONG }

@Serializable
data class TileStyle(
    val shape: TileShape = TileShape.ROUNDED,
    val density: TileDensity = TileDensity.COZY,
    val glow: Boolean = false,
)

@Serializable
data class ThemeConfig(
    val paletteMode: PaletteMode = PaletteMode.AMBIENT,
    val fixedSeedArgb: Int = 0xFF26354C.toInt(),         // Mimir slate-blue
    val wallpaperTopUri: String? = null,
    val wallpaperBottomUri: String? = null,
    val soundPackUri: String? = null,                     // null = bundled default
    val soundVolume: Float = 0.6f,
    val haptics: HapticLevel = HapticLevel.SUBTLE,
    val tiles: TileStyle = TileStyle(),
    /** slot name -> pack id that last set it (theme mixing provenance) */
    val provenance: Map<String, String> = emptyMap(),
)
```
`ThemePack.kt`:
```kotlin
package dev.mimir.theme

import kotlinx.serialization.Serializable

/** A pack defines any subset of slots; applying writes only what it defines. */
@Serializable
data class ThemePack(
    val id: String,
    val name: String,
    val paletteMode: PaletteMode? = null,
    val fixedSeedArgb: Int? = null,
    val wallpaperTopUri: String? = null,
    val wallpaperBottomUri: String? = null,
    val soundPackUri: String? = null,
    val haptics: HapticLevel? = null,
    val tiles: TileStyle? = null,
)

fun ThemeConfig.applying(pack: ThemePack): ThemeConfig {
    var prov = provenance
    fun mark(slot: String) { prov = prov + (slot to pack.id) }
    var c = this
    pack.paletteMode?.let { c = c.copy(paletteMode = it); mark("paletteMode") }
    pack.fixedSeedArgb?.let { c = c.copy(fixedSeedArgb = it); mark("fixedSeedArgb") }
    pack.wallpaperTopUri?.let { c = c.copy(wallpaperTopUri = it); mark("wallpaperTopUri") }
    pack.wallpaperBottomUri?.let { c = c.copy(wallpaperBottomUri = it); mark("wallpaperBottomUri") }
    pack.soundPackUri?.let { c = c.copy(soundPackUri = it); mark("soundPackUri") }
    pack.haptics?.let { c = c.copy(haptics = it); mark("haptics") }
    pack.tiles?.let { c = c.copy(tiles = it); mark("tiles") }
    return c.copy(provenance = prov)
}
```
- [ ] **Step 4:** `./gradlew :core:theme:testDebugUnitTest` → 4 tests green. Commit `feat(theme): slotted ThemeConfig with per-slot pack mixing and provenance`.

---

### Task 4: `:core:theme` — DataStore persistence + ambient palette engine (TDD where pure)

**Files:** `core/theme/src/main/kotlin/dev/mimir/theme/{ThemeStore,AmbientPalette,PaletteExtractor,MimirTheme}.kt`, test `AmbientPaletteTest.kt`

- [ ] **Step 1: Failing test** — `AmbientPaletteTest.kt` (the pure swatch chooser):
```kotlin
package dev.mimir.theme

import kotlin.test.Test
import kotlin.test.assertEquals

class AmbientPaletteTest {
    @Test
    fun `chooses vibrant then muted then dominant then fallback`() {
        assertEquals(1, AmbientPalette.choosePrimary(vibrant = 1, muted = 2, dominant = 3))
        assertEquals(2, AmbientPalette.choosePrimary(vibrant = null, muted = 2, dominant = 3))
        assertEquals(3, AmbientPalette.choosePrimary(vibrant = null, muted = null, dominant = 3))
        assertEquals(AmbientPalette.FALLBACK_ARGB, AmbientPalette.choosePrimary(null, null, null))
    }

    @Test
    fun `derives glow and scrim from primary deterministically`() {
        val p = AmbientPalette.from(primaryArgb = 0xFF3B8FE2.toInt())
        assertEquals(0xFF3B8FE2.toInt(), p.primaryArgb)
        assertEquals(AmbientPalette.from(0xFF3B8FE2.toInt()), p) // pure/deterministic
    }
}
```

- [ ] **Step 2: Implement.**

`AmbientPalette.kt`:
```kotlin
package dev.mimir.theme

data class AmbientPalette(
    val primaryArgb: Int,
    val glowArgb: Int,    // primary at ~25% alpha for washes
    val scrimArgb: Int,   // near-black tinted toward primary
) {
    companion object {
        const val FALLBACK_ARGB = 0xFF26354C.toInt() // Mimir slate-blue

        fun choosePrimary(vibrant: Int?, muted: Int?, dominant: Int?): Int =
            vibrant ?: muted ?: dominant ?: FALLBACK_ARGB

        fun from(primaryArgb: Int): AmbientPalette = AmbientPalette(
            primaryArgb = primaryArgb,
            glowArgb = (primaryArgb and 0x00FFFFFF) or 0x40000000,
            scrimArgb = blend(primaryArgb, 0xFF0B0B10.toInt(), 0.85f),
        )

        private fun blend(a: Int, b: Int, towardB: Float): Int {
            fun ch(shift: Int) =
                (((a shr shift and 0xFF) * (1 - towardB)) + ((b shr shift and 0xFF) * towardB)).toInt() and 0xFF
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
```

`PaletteExtractor.kt` (Android wrapper — thin, not unit-tested):
```kotlin
package dev.mimir.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

/** Extracts an AmbientPalette from an art uri (content:// or https://), memoized. */
class PaletteExtractor(private val context: Context) {
    private val cache = LruCache<String, AmbientPalette>(64)

    suspend fun extract(artUri: String?): AmbientPalette {
        if (artUri == null) return AmbientPalette.from(AmbientPalette.FALLBACK_ARGB)
        cache.get(artUri)?.let { return it }
        val palette = withContext(Dispatchers.IO) {
            runCatching {
                val bitmap = loadScaledBitmap(artUri) ?: return@runCatching null
                val p = Palette.from(bitmap).maximumColorCount(16).generate()
                bitmap.recycle()
                AmbientPalette.from(
                    AmbientPalette.choosePrimary(
                        vibrant = p.vibrantSwatch?.rgb,
                        muted = p.mutedSwatch?.rgb,
                        dominant = p.dominantSwatch?.rgb,
                    )
                )
            }.getOrNull()
        } ?: AmbientPalette.from(AmbientPalette.FALLBACK_ARGB)
        cache.put(artUri, palette)
        return palette
    }

    private fun loadScaledBitmap(uri: String): Bitmap? {
        val opts = BitmapFactory.Options().apply { inSampleSize = 4 } // palette doesn't need full res
        return if (uri.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it, null, opts) }
        } else {
            URL(uri).openStream().use { BitmapFactory.decodeStream(it, null, opts) }
        }
    }
}
```
(Note: https extraction re-downloads a scaled copy rather than reaching into Coil's disk cache — acceptable for M5a-1, one network image per palette miss, LRU'd. M5a-3 may switch to Coil-bitmap reuse; do not build that now.)

`ThemeStore.kt`:
```kotlin
package dev.mimir.theme

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.themeDataStore by preferencesDataStore(name = "mimir_theme")

class ThemeStore(private val context: Context) {
    private val key = stringPreferencesKey("theme_config_json")
    private val json = Json { ignoreUnknownKeys = true }

    val config: Flow<ThemeConfig> = context.themeDataStore.data.map { prefs ->
        prefs[key]?.let { runCatching { json.decodeFromString<ThemeConfig>(it) }.getOrNull() } ?: ThemeConfig()
    }

    suspend fun update(transform: (ThemeConfig) -> ThemeConfig) {
        context.themeDataStore.edit { prefs ->
            val current = prefs[key]?.let { runCatching { json.decodeFromString<ThemeConfig>(it) }.getOrNull() } ?: ThemeConfig()
            prefs[key] = json.encodeToString(ThemeConfig.serializer(), transform(current))
        }
    }
}
```

`MimirTheme.kt` (Compose surface — the provider M5a-3 builds screens on):
```kotlin
package dev.mimir.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class MimirThemeValues(
    val config: ThemeConfig,
    val ambient: AmbientPalette,
    val primary: Color,
    val glow: Color,
    val scrim: Color,
)

val LocalMimirTheme = staticCompositionLocalOf {
    MimirThemeValues(
        config = ThemeConfig(),
        ambient = AmbientPalette.from(AmbientPalette.FALLBACK_ARGB),
        primary = Color(AmbientPalette.FALLBACK_ARGB),
        glow = Color(AmbientPalette.from(AmbientPalette.FALLBACK_ARGB).glowArgb),
        scrim = Color(AmbientPalette.from(AmbientPalette.FALLBACK_ARGB).scrimArgb),
    )
}

@Composable
fun MimirTheme(config: ThemeConfig, ambient: AmbientPalette, content: @Composable () -> Unit) {
    val animPrimary by animateColorAsState(Color(ambient.primaryArgb), tween(400), label = "ambientPrimary")
    val animGlow by animateColorAsState(Color(ambient.glowArgb), tween(400), label = "ambientGlow")
    val animScrim by animateColorAsState(Color(ambient.scrimArgb), tween(400), label = "ambientScrim")
    CompositionLocalProvider(
        LocalMimirTheme provides MimirThemeValues(config, ambient, animPrimary, animGlow, animScrim),
        content = content,
    )
}
```

- [ ] **Step 3:** `:core:theme:testDebugUnitTest` → 6 tests green. Commit `feat(theme): DataStore persistence, ambient palette engine, Compose provider`.

---

### Task 5: `:app` — wire foundation + visible ambient tint

**Files:** `app/build.gradle.kts` (+ `implementation(project(":core:theme"))`), `MainViewModel.kt`, `MainActivity.kt`, `MimirApp.kt`

- [ ] **Step 1:** MimirApp: add `MIGRATION_4_5` to addMigrations; add `val themeStore by lazy { dev.mimir.theme.ThemeStore(this) }` and `val paletteExtractor by lazy { dev.mimir.theme.PaletteExtractor(this) }`.
- [ ] **Step 2:** MainViewModel additions:
```kotlin
    private val themeStore = (app as MimirApp).themeStore
    private val paletteExtractor = (app as MimirApp).paletteExtractor

    val themeConfig: StateFlow<ThemeConfig> =
        themeStore.config.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeConfig())

    /** Ambient palette follows the most recently played game's boxart (M5a-3 will follow browse focus). */
    val ambient: StateFlow<AmbientPalette> =
        combine(repo.playStates, repo.media) { plays, media ->
            val recentUri = plays.maxByOrNull { it.lastPlayedAt }?.gameUri
            media.firstOrNull { it.gameUri == recentUri && it.kind == "boxart" }?.boxartUrl
        }.map { artUri ->
            if (themeConfig.value.paletteMode == PaletteMode.FIXED)
                AmbientPalette.from(themeConfig.value.fixedSeedArgb)
            else paletteExtractor.extract(artUri)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, AmbientPalette.from(AmbientPalette.FALLBACK_ARGB))
```
(imports for ThemeConfig/PaletteMode/AmbientPalette/ThemeStore types; also the Task 2 sweep items if not already landed: stampPlayed on launch success, art map filtered by kind.)
- [ ] **Step 3:** MainActivity `setContent` wraps everything:
```kotlin
            val config by viewModel.themeConfig.collectAsState()
            val ambient by viewModel.ambient.collectAsState()
            MimirTheme(config = config, ambient = ambient) {
                MaterialTheme(colorScheme = darkColorScheme(primary = LocalMimirTheme.current.primary)) {
                    ...existing MainScreen...
                }
            }
```
And tint the library background: in `MainScreen`'s root `Box`, add `Modifier.background(Brush.verticalGradient(listOf(LocalMimirTheme.current.glow, Color.Transparent)))` behind content (imports: `dev.mimir.theme.*`, Brush already imported).
- [ ] **Step 4:** `./gradlew test :app:assembleDebug` → 73 tests (63 + 4 data-delta... recount: scanner 18, scraper 16, data 16, launcher 16, theme 6, app 1 = 73), 0 failures. Commit `feat(app): theme foundation wired — ambient tint follows last-played game`.

---

### Task 6: E2E + docs

- [ ] **Step 1: E2E on the Pixel_10_Pro emulator** (serial via `adb devices`/avd name; boot headless if needed; leave running): install OVER v0.6.0 → migration v4→v5 preserves games/art/prefs (pull DB: media rows have kind=boxart/source=libretro, custom/platform prefs intact); launch Mario Kart 64 (fake emulator opens) → back → pull DB: lastPlayedAt stamped; force-stop/relaunch → UI shows a subtle blue-ish ambient wash at top of the grid (screenshot, READ the png, compare against a pre-launch baseline screenshot — the tint must visibly differ from pure black); ES-DE re-import (`Import ES-DE`) still works and its magenta cover survives a subsequent `Fetch artwork` tap (priority: esde > libretro — the libretro fetch must NOT overwrite it; verify DB source column). Logcat clean. Evidence → `docs/superpowers/plans/m5a1-verification.md` + `img/m5a1-ambient.png`. Commit.
- [ ] **Step 2:** README status → `**Status: M5a-1 — theme foundation.** Slotted theme config with per-slot pack mixing, ambient palette engine (UI tints from your last-played game's art), art sources with priority (folder > ES-DE > SteamGridDB > libretro), playtime stamping. The new shell screens land in M5a-3.` Version `versionCode = 7`, `versionName = "0.7.0-m5a1"`. Final `--rerun-tasks` → 73 tests. Commit.

---

## Out of scope (M5a-2/-3 — do not add)
SGDB client, folder-art pickup, new screens (Home/Browse/HeroPane), sound/haptic engine, backup export, wallpapers UI, theme settings UI, focus-driven palette (last-played proxy only for now).
