# Mimir M1 — End-to-End Launch Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A real Android app that scans one SAF-picked ROM folder, shows the games in a grid, and launches a game in an emulator via intent — ugly but end-to-end ("M1" in the spec at `docs/superpowers/specs/2026-06-10-mimir-launcher-design.md`).

**Architecture:** Three Gradle modules + one test fixture. Pure-JVM modules `:core:scanner` (platform defs + file→platform matching) and `:core:launcher` (player defs + intent-template substitution) hold all logic and are fully unit-tested with plain JUnit. `:app` is a thin Android layer: SAF folder picking, DocumentFile tree walking, Compose grid, intent dispatch. `:tools:fake-emulator` is a tiny APK that catches VIEW intents and displays what it received, so the launch path is verifiable on an emulator with zero real emulators installed. In-memory library only — Room arrives in M2.

**Tech Stack (verified current June 2026):** AGP 9.2.0 (built-in Kotlin — do NOT apply `org.jetbrains.kotlin.android`), Kotlin 2.4.0, Gradle 9.5.x wrapper, Compose BOM 2026.05.00, kotlinx-serialization-json, androidx.documentfile. JDK 17 required. minSdk 29, compileSdk 36.

**Conventions:** Package root `dev.mimir`. All commands run from repo root `~/Development/mimir`. Commit messages end with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

### Task 0: Environment check

**Files:** none

- [ ] **Step 1: Verify JDK 17+, Android SDK, and Gradle are available**

Run:
```bash
java -version 2>&1 | head -1
echo "ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ls "${ANDROID_HOME:-$HOME/Library/Android/sdk}/platforms" 2>/dev/null || echo "NO ANDROID SDK"
gradle --version 2>/dev/null | grep Gradle || echo "no system gradle (ok if wrapper exists)"
```
Expected: java 17+; an SDK directory listing (any platforms). If `NO ANDROID SDK`, stop and install Android Studio (brings SDK + JDK), then re-run. If no system gradle AND no wrapper yet: `brew install gradle` (needed once in Task 1 to generate the wrapper).

- [ ] **Step 2: Ensure SDK platform 36 + build tools are installed**

Run:
```bash
SDKM="${ANDROID_HOME:-$HOME/Library/Android/sdk}/cmdline-tools/latest/bin/sdkmanager"
"$SDKM" "platforms;android-36" "build-tools;36.0.0" --channel=0 || echo "install via Android Studio SDK Manager: platform 36, build-tools 36.0.0"
```
Expected: installs or already-installed messages. Android Studio's SDK Manager UI is an acceptable fallback.

---

### Task 1: Gradle scaffold

**Files:**
- Create: `settings.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `build.gradle.kts`
- Create: `.gitignore`
- Create: gradle wrapper (`gradlew`, `gradle/wrapper/*`)

- [ ] **Step 1: Write `.gitignore`**

```gitignore
.gradle/
build/
local.properties
.DS_Store
*.iml
.idea/
captures/
.cxx/
```

- [ ] **Step 2: Write `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.caching=true
org.gradle.configuration-cache=true
android.useAndroidX=true
```

- [ ] **Step 3: Write `gradle/libs.versions.toml`**

```toml
[versions]
agp = "9.2.0"
kotlin = "2.4.0"
composeBom = "2026.05.00"
activityCompose = "1.11.0"
lifecycle = "2.10.0"
documentfile = "1.1.0"
serializationJson = "1.9.0"
coroutines = "1.10.2"
junit = "4.13.2"

[libraries]
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
documentfile = { group = "androidx.documentfile", name = "documentfile", version.ref = "documentfile" }
serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "serializationJson" }
coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
junit = { group = "junit", name = "junit", version.ref = "junit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

If `9.2.0`/`2.4.0`/`2026.05.00` fail to resolve in Step 7, check latest stable on https://developer.android.com/build/releases and https://developer.android.com/develop/ui/compose/bom — adjust versions, don't fight resolution.

- [ ] **Step 4: Write `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "mimir"
include(":app")
include(":core:scanner")
include(":core:launcher")
include(":tools:fake-emulator")
```

- [ ] **Step 5: Write root `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
```

- [ ] **Step 6: Generate the Gradle wrapper**

Run: `cd "~/Development/mimir" && gradle wrapper --gradle-version 9.5.1`
Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/` created.

- [ ] **Step 7: Create minimal module stubs so settings resolves, then verify**

Create `app/build.gradle.kts`, `core/scanner/build.gradle.kts`, `core/launcher/build.gradle.kts`, `tools/fake-emulator/build.gradle.kts` as EMPTY files (content comes in their tasks), then:

Run: `./gradlew help`
Expected: `BUILD SUCCESSFUL`. (Empty build files are valid Gradle scripts.)

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "chore: Gradle scaffold (AGP 9.2, Kotlin 2.4, version catalog, 4 modules)"
```

---

### Task 2: `:core:scanner` — platform definitions (TDD)

**Files:**
- Create: `core/scanner/build.gradle.kts`
- Create: `core/scanner/src/main/resources/platforms.json`
- Create: `core/scanner/src/main/kotlin/dev/mimir/scanner/PlatformDef.kt`
- Test: `core/scanner/src/test/kotlin/dev/mimir/scanner/PlatformDefsTest.kt`

- [ ] **Step 1: Write `core/scanner/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
dependencies {
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}
```

- [ ] **Step 2: Write the failing test**

`core/scanner/src/test/kotlin/dev/mimir/scanner/PlatformDefsTest.kt`:
```kotlin
package dev.mimir.scanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformDefsTest {
    @Test
    fun `bundled platforms load and contain n64 with aliases and extensions`() {
        val platforms = PlatformDefs.load()
        assertTrue(platforms.size >= 5, "expected at least 5 bundled platforms")
        val n64 = platforms.first { it.id == "n64" }
        assertEquals("Nintendo 64", n64.name)
        assertTrue("n64" in n64.folderAliases)
        assertTrue("nintendo64" in n64.folderAliases)
        assertTrue("z64" in n64.extensions)
    }

    @Test
    fun `ids are unique`() {
        val platforms = PlatformDefs.load()
        assertEquals(platforms.size, platforms.map { it.id }.toSet().size)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :core:scanner:test`
Expected: FAIL — `Unresolved reference: PlatformDefs` (compile error counts as the failing state for greenfield code).

- [ ] **Step 4: Write `platforms.json` and `PlatformDef.kt`**

`core/scanner/src/main/resources/platforms.json` (M1 seed set — the community-PR-able registry from the spec; folderAliases/extensions all lowercase):
```json
[
  {"id": "nes", "name": "Nintendo Entertainment System",
   "folderAliases": ["nes", "famicom", "fc"], "extensions": ["nes", "fds", "unf"]},
  {"id": "snes", "name": "Super Nintendo",
   "folderAliases": ["snes", "sfc", "superfamicom", "supernintendo"], "extensions": ["sfc", "smc"]},
  {"id": "n64", "name": "Nintendo 64",
   "folderAliases": ["n64", "nintendo64", "nintendo 64"], "extensions": ["n64", "z64", "v64"]},
  {"id": "gba", "name": "Game Boy Advance",
   "folderAliases": ["gba", "gameboyadvance", "gameboy advance"], "extensions": ["gba"]},
  {"id": "nds", "name": "Nintendo DS",
   "folderAliases": ["nds", "ds", "nintendods", "nintendo ds"], "extensions": ["nds"]},
  {"id": "gc", "name": "GameCube",
   "folderAliases": ["gc", "ngc", "gamecube", "gcn"], "extensions": ["gcm", "rvz", "ciso"]},
  {"id": "psx", "name": "PlayStation",
   "folderAliases": ["psx", "ps1", "playstation"], "extensions": ["chd", "pbp", "cue"]}
]
```

`core/scanner/src/main/kotlin/dev/mimir/scanner/PlatformDef.kt`:
```kotlin
package dev.mimir.scanner

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PlatformDef(
    val id: String,
    val name: String,
    val folderAliases: List<String>,
    val extensions: List<String>,
)

object PlatformDefs {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<PlatformDef> {
        val text = requireNotNull(
            PlatformDefs::class.java.getResourceAsStream("/platforms.json")
        ) { "platforms.json missing from resources" }.bufferedReader().readText()
        return json.decodeFromString(text)
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :core:scanner:test`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat(scanner): bundled platform definitions with JSON registry"
```

---

### Task 3: `:core:scanner` — file→platform matcher (TDD)

**Files:**
- Create: `core/scanner/src/main/kotlin/dev/mimir/scanner/LibraryMatcher.kt`
- Test: `core/scanner/src/test/kotlin/dev/mimir/scanner/LibraryMatcherTest.kt`

Matcher rules (from spec: alias dictionary, any casing, diagnostics):
1. Walk path segments of the file's parent folders; if any segment (lowercased, trimmed) is a `folderAlias` of a platform → candidate platform. Deepest matching segment wins.
2. If a candidate platform was found and the file extension is in its `extensions` → matched.
3. No folder match → if the extension belongs to exactly ONE platform → matched (extension-unique fallback).
4. Otherwise → skipped, with a human-readable reason (seeds the M2 scan-report screen).

- [ ] **Step 1: Write the failing test**

`core/scanner/src/test/kotlin/dev/mimir/scanner/LibraryMatcherTest.kt`:
```kotlin
package dev.mimir.scanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryMatcherTest {
    private val matcher = LibraryMatcher(PlatformDefs.load())

    private fun file(path: String) = ScannedFile(
        relativePath = path,
        uri = "content://test/${path.replace('/', '_')}",
    )

    @Test
    fun `matches by folder alias case-insensitively`() {
        val result = matcher.match(listOf(file("GameCube/Wind Waker.rvz")))
        assertEquals("gc", result.games.single().platformId)
        assertEquals("Wind Waker", result.games.single().title)
    }

    @Test
    fun `matches lowercase short alias`() {
        val result = matcher.match(listOf(file("gc/Wind Waker.rvz")))
        assertEquals("gc", result.games.single().platformId)
    }

    @Test
    fun `deepest folder alias wins`() {
        val result = matcher.match(listOf(file("roms/Nintendo 64/Mario Kart 64.z64")))
        assertEquals("n64", result.games.single().platformId)
    }

    @Test
    fun `extension-unique fallback matches without folder hint`() {
        val result = matcher.match(listOf(file("stuff/Pokemon Emerald.gba")))
        assertEquals("gba", result.games.single().platformId)
    }

    @Test
    fun `ambiguous extension without folder hint is skipped with reason`() {
        // chd belongs to psx only in M1 seed; use a fake unknown extension for ambiguity-free skip
        val result = matcher.match(listOf(file("misc/readme.txt")))
        assertTrue(result.games.isEmpty())
        val skipped = result.skipped.single()
        assertEquals("misc/readme.txt", skipped.relativePath)
        assertTrue("txt" in skipped.reason)
    }

    @Test
    fun `alias folder with wrong extension is skipped and reason names the platform`() {
        val result = matcher.match(listOf(file("n64/Mario.gba")))
        assertTrue(result.games.isEmpty())
        assertTrue("Nintendo 64" in result.skipped.single().reason)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:scanner:test`
Expected: FAIL — `Unresolved reference: LibraryMatcher` / `ScannedFile`.

- [ ] **Step 3: Write `LibraryMatcher.kt`**

```kotlin
package dev.mimir.scanner

data class ScannedFile(
    /** Path relative to the library root, '/'-separated, e.g. "GameCube/Wind Waker.rvz". */
    val relativePath: String,
    /** Opaque launchable identifier (SAF content URI on Android; anything in tests). */
    val uri: String,
)

data class Game(
    val title: String,
    val uri: String,
    val platformId: String,
)

data class SkippedFile(val relativePath: String, val reason: String)

data class ScanResult(val games: List<Game>, val skipped: List<SkippedFile>)

class LibraryMatcher(private val platforms: List<PlatformDef>) {
    private val aliasToPlatform: Map<String, PlatformDef> =
        platforms.flatMap { p -> p.folderAliases.map { it.lowercase() to p } }.toMap()
    private val extensionToPlatforms: Map<String, List<PlatformDef>> =
        platforms.flatMap { p -> p.extensions.map { it.lowercase() to p } }
            .groupBy({ it.first }, { it.second })

    fun match(files: List<ScannedFile>): ScanResult {
        val games = mutableListOf<Game>()
        val skipped = mutableListOf<SkippedFile>()
        for (file in files) {
            val segments = file.relativePath.split('/')
            val name = segments.last()
            val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            val title = name.substringBeforeLast('.')
            val folderPlatform = segments.dropLast(1)
                .lastOrNull { aliasToPlatform.containsKey(it.lowercase().trim()) }
                ?.let { aliasToPlatform.getValue(it.lowercase().trim()) }

            when {
                folderPlatform != null && ext in folderPlatform.extensions ->
                    games += Game(title, file.uri, folderPlatform.id)
                folderPlatform != null ->
                    skipped += SkippedFile(
                        file.relativePath,
                        "extension .$ext is not registered for ${folderPlatform.name} " +
                            "(expects: ${folderPlatform.extensions.joinToString { ".$it" }})",
                    )
                else -> {
                    val byExt = extensionToPlatforms[ext].orEmpty()
                    when (byExt.size) {
                        1 -> games += Game(title, file.uri, byExt.single().id)
                        0 -> skipped += SkippedFile(
                            file.relativePath,
                            "no platform registered for extension .$ext and no platform folder in path",
                        )
                        else -> skipped += SkippedFile(
                            file.relativePath,
                            "extension .$ext is ambiguous (${byExt.joinToString { it.name }}); " +
                                "place the file in a platform-named folder",
                        )
                    }
                }
            }
        }
        return ScanResult(games, skipped)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:scanner:test`
Expected: PASS (8 tests total across both test files).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(scanner): alias-dictionary matcher with skip diagnostics"
```

---

### Task 4: `:core:launcher` — players + intent templates (TDD)

**Files:**
- Create: `core/launcher/build.gradle.kts`
- Create: `core/launcher/src/main/resources/players.json`
- Create: `core/launcher/src/main/kotlin/dev/mimir/launcher/PlayerDef.kt`
- Create: `core/launcher/src/main/kotlin/dev/mimir/launcher/IntentSpec.kt`
- Test: `core/launcher/src/test/kotlin/dev/mimir/launcher/IntentSpecBuilderTest.kt`

This is the Daijisho-Player/ES-DE-template hybrid from the spec, as data: a Player is `(component, action, data template, extras, flags, platformIds)`. `%ROM%` and `%TITLE%` are the M1 variables.

- [ ] **Step 1: Write `core/launcher/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
dependencies {
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}
```

- [ ] **Step 2: Write the failing test**

`core/launcher/src/test/kotlin/dev/mimir/launcher/IntentSpecBuilderTest.kt`:
```kotlin
package dev.mimir.launcher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntentSpecBuilderTest {
    private val melonds = PlayerDef(
        id = "melonds",
        name = "melonDS",
        packageName = "me.magnum.melonds",
        activityClass = null,
        action = "android.intent.action.VIEW",
        platformIds = listOf("nds"),
    )

    @Test
    fun `substitutes ROM into data uri`() {
        val spec = buildIntentSpec(melonds, romUri = "content://tree/doc%2Fmario.nds", title = "Mario")
        assertEquals("android.intent.action.VIEW", spec.action)
        assertEquals("me.magnum.melonds", spec.packageName)
        assertEquals("content://tree/doc%2Fmario.nds", spec.dataUri)
        assertTrue("GRANT_READ_URI_PERMISSION" in spec.flags)
    }

    @Test
    fun `substitutes variables inside extras`() {
        val player = melonds.copy(extras = mapOf("ROM" to "%ROM%", "GAME_TITLE" to "%TITLE%"))
        val spec = buildIntentSpec(player, romUri = "content://x/y.nds", title = "Zelda")
        assertEquals("content://x/y.nds", spec.extras["ROM"])
        assertEquals("Zelda", spec.extras["GAME_TITLE"])
    }

    @Test
    fun `bundled players load and cover the fake emulator`() {
        val players = PlayerDefs.load()
        val fake = players.first { it.id == "fake-emulator" }
        assertEquals("dev.mimir.fakeemulator", fake.packageName)
        assertTrue(fake.platformIds.containsAll(listOf("nes", "n64", "nds")))
    }

    @Test
    fun `defaultPlayerFor returns first player claiming the platform`() {
        val players = PlayerDefs.load()
        val player = defaultPlayerFor(players, platformId = "nds")
        assertTrue(player != null && "nds" in player.platformIds)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :core:launcher:test`
Expected: FAIL — unresolved references.

- [ ] **Step 4: Write the implementation**

`core/launcher/src/main/resources/players.json` (fake emulator first so M1 demos work with zero real emulators; melonDS included as the first real-world player):
```json
[
  {"id": "fake-emulator", "name": "Mimir Fake Emulator",
   "packageName": "dev.mimir.fakeemulator",
   "activityClass": "dev.mimir.fakeemulator.CatchActivity",
   "action": "android.intent.action.VIEW",
   "platformIds": ["nes", "snes", "n64", "gba", "nds", "gc", "psx"]},
  {"id": "melonds", "name": "melonDS",
   "packageName": "me.magnum.melonds",
   "action": "android.intent.action.VIEW",
   "platformIds": ["nds"]}
]
```

`core/launcher/src/main/kotlin/dev/mimir/launcher/PlayerDef.kt`:
```kotlin
package dev.mimir.launcher

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PlayerDef(
    val id: String,
    val name: String,
    val packageName: String,
    val activityClass: String? = null,
    val action: String = "android.intent.action.VIEW",
    val dataTemplate: String = "%ROM%",
    val extras: Map<String, String> = emptyMap(),
    val flags: List<String> = listOf("GRANT_READ_URI_PERMISSION"),
    val platformIds: List<String> = emptyList(),
)

object PlayerDefs {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<PlayerDef> {
        val text = requireNotNull(
            PlayerDefs::class.java.getResourceAsStream("/players.json")
        ) { "players.json missing from resources" }.bufferedReader().readText()
        return json.decodeFromString(text)
    }
}

fun defaultPlayerFor(players: List<PlayerDef>, platformId: String): PlayerDef? =
    players.firstOrNull { platformId in it.platformIds }
```

`core/launcher/src/main/kotlin/dev/mimir/launcher/IntentSpec.kt`:
```kotlin
package dev.mimir.launcher

data class IntentSpec(
    val action: String,
    val packageName: String,
    val activityClass: String?,
    val dataUri: String,
    val extras: Map<String, String>,
    val flags: List<String>,
)

fun buildIntentSpec(player: PlayerDef, romUri: String, title: String): IntentSpec {
    fun substitute(template: String): String =
        template.replace("%ROM%", romUri).replace("%TITLE%", title)
    return IntentSpec(
        action = player.action,
        packageName = player.packageName,
        activityClass = player.activityClass,
        dataUri = substitute(player.dataTemplate),
        extras = player.extras.mapValues { (_, v) -> substitute(v) },
        flags = player.flags,
    )
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :core:launcher:test`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat(launcher): player registry and intent-template builder"
```

---

### Task 5: `:tools:fake-emulator` — intent-catching test fixture APK

**Files:**
- Create: `tools/fake-emulator/build.gradle.kts`
- Create: `tools/fake-emulator/src/main/AndroidManifest.xml`
- Create: `tools/fake-emulator/src/main/kotlin/dev/mimir/fakeemulator/CatchActivity.kt`

- [ ] **Step 1: Write `tools/fake-emulator/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
}
android {
    namespace = "dev.mimir.fakeemulator"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.mimir.fakeemulator"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
}
```

- [ ] **Step 2: Write the manifest**

`tools/fake-emulator/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:label="Fake Emulator">
        <activity android:name=".CatchActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:scheme="content" android:mimeType="*/*" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 3: Write `CatchActivity.kt`** (plain views — no Compose needed in a fixture)

```kotlin
package dev.mimir.fakeemulator

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

class CatchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extras = intent.extras?.keySet()
            ?.joinToString("\n") { "  $it = ${intent.extras?.get(it)}" } ?: "  (none)"
        val report = """
            FAKE EMULATOR — intent received
            action: ${intent.action}
            data:   ${intent.data}
            flags:  0x${Integer.toHexString(intent.flags)}
            extras:
            $extras
        """.trimIndent()
        setContentView(ScrollView(this).apply {
            addView(TextView(context).apply { text = report; textSize = 16f; setPadding(32, 64, 32, 32) })
        })
    }
}
```

- [ ] **Step 4: Verify it builds**

Run: `./gradlew :tools:fake-emulator:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(tools): fake-emulator intent-catching test fixture"
```

---

### Task 6: `:app` — module setup + SAF tree walker

**Files:**
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/kotlin/dev/mimir/app/SafTreeWalker.kt`
- Create: `app/src/main/res/values/themes.xml`

- [ ] **Step 1: Write `app/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "dev.mimir.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.mimir.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-m1"
    }
    buildFeatures { compose = true }
}
dependencies {
    implementation(project(":core:scanner"))
    implementation(project(":core:launcher"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.documentfile)
    implementation(libs.coroutines.android)
}
```

- [ ] **Step 2: Write the manifest and theme**

`app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <queries>
        <intent>
            <action android:name="android.intent.action.VIEW" />
            <data android:scheme="content" android:mimeType="*/*" />
        </intent>
    </queries>
    <application
        android:label="Mimir"
        android:theme="@style/Theme.Mimir">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`app/src/main/res/values/themes.xml`:
```xml
<resources>
    <style name="Theme.Mimir" parent="android:Theme.Material.NoActionBar" />
</resources>
```

- [ ] **Step 3: Write `SafTreeWalker.kt`** — the thin DocumentFile→`ScannedFile` adapter (all matching logic stays in the tested `:core:scanner`)

```kotlin
package dev.mimir.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.mimir.scanner.ScannedFile

object SafTreeWalker {
    /** Walks a persisted SAF tree and returns every file as a ScannedFile with a path relative to the root. */
    fun walk(context: Context, treeUri: Uri): List<ScannedFile> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val out = mutableListOf<ScannedFile>()
        fun recurse(dir: DocumentFile, prefix: String) {
            for (child in dir.listFiles()) {
                val name = child.name ?: continue
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                if (child.isDirectory) recurse(child, path)
                else out += ScannedFile(relativePath = path, uri = child.uri.toString())
            }
        }
        recurse(root, "")
        return out
    }
}
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL` (MainActivity referenced in manifest doesn't exist yet — manifest references aren't checked at compile time; if `assembleDebug` is run instead, expect lint/manifest merge to pass too since activity classes are validated at runtime, not build).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(app): app module scaffold with SAF tree walker"
```

---

### Task 7: `:app` — launch dispatch + ViewModel

**Files:**
- Create: `app/src/main/kotlin/dev/mimir/app/LaunchController.kt`
- Create: `app/src/main/kotlin/dev/mimir/app/MainViewModel.kt`

- [ ] **Step 1: Write `LaunchController.kt`** (IntentSpec→Intent; failures become values, never crashes — spec §3 guided-fix groundwork)

```kotlin
package dev.mimir.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.mimir.launcher.IntentSpec

sealed interface LaunchResult {
    data object Success : LaunchResult
    data class Failure(val reason: String) : LaunchResult
}

object LaunchController {
    fun launch(context: Context, spec: IntentSpec): LaunchResult {
        val intent = Intent(spec.action).apply {
            setDataAndType(Uri.parse(spec.dataUri), "*/*")
            if (spec.activityClass != null) setClassName(spec.packageName, spec.activityClass)
            else `package` = spec.packageName
            for ((k, v) in spec.extras) putExtra(k, v)
            if ("GRANT_READ_URI_PERMISSION" in spec.flags) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            LaunchResult.Success
        } catch (e: ActivityNotFoundException) {
            LaunchResult.Failure("${spec.packageName} is not installed (or exposes no matching activity)")
        } catch (e: SecurityException) {
            LaunchResult.Failure("Permission denied launching ${spec.packageName}: ${e.message}")
        }
    }
}
```

- [ ] **Step 2: Write `MainViewModel.kt`** (in-memory library; Room is M2)

```kotlin
package dev.mimir.app

import android.app.Application
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mimir.launcher.PlayerDefs
import dev.mimir.launcher.buildIntentSpec
import dev.mimir.launcher.defaultPlayerFor
import dev.mimir.scanner.Game
import dev.mimir.scanner.LibraryMatcher
import dev.mimir.scanner.PlatformDefs
import dev.mimir.scanner.SkippedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UiState {
    data object NeedsFolder : UiState
    data object Scanning : UiState
    data class Library(
        val gamesByPlatform: Map<String, List<Game>>, // key = platform display name
        val skipped: List<SkippedFile>,
    ) : UiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("mimir", 0)
    private val platforms = PlatformDefs.load()
    private val platformNames = platforms.associate { it.id to it.name }
    private val matcher = LibraryMatcher(platforms)
    private val players = PlayerDefs.load()

    private val _state = MutableStateFlow<UiState>(UiState.NeedsFolder)
    val state: StateFlow<UiState> = _state
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    init { savedTreeUri()?.let { rescan(it) } }

    fun savedTreeUri(): Uri? = prefs.getString("treeUri", null)?.let(Uri::parse)

    fun onFolderPicked(treeUri: Uri) {
        prefs.edit { putString("treeUri", treeUri.toString()) }
        rescan(treeUri)
    }

    fun rescan(treeUri: Uri) {
        _state.value = UiState.Scanning
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                matcher.match(SafTreeWalker.walk(getApplication(), treeUri))
            }
            _state.value = UiState.Library(
                gamesByPlatform = result.games
                    .groupBy { platformNames[it.platformId] ?: it.platformId }
                    .toSortedMap(),
                skipped = result.skipped,
            )
        }
    }

    fun launchGame(game: Game) {
        val player = defaultPlayerFor(players, game.platformId)
        if (player == null) {
            _message.value = "No emulator registered for ${platformNames[game.platformId]}"
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

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`. (`androidx.core` ktx `edit` comes transitively via activity-compose; if unresolved, replace with `prefs.edit().putString("treeUri", treeUri.toString()).apply()`.)

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat(app): launch dispatch with failure-as-value and library view model"
```

---

### Task 8: `:app` — Compose UI (folder pick → grid → tap to launch)

**Files:**
- Create: `app/src/main/kotlin/dev/mimir/app/MainActivity.kt`

- [ ] **Step 1: Write `MainActivity.kt`**

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
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mimir.scanner.Game

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
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.consumeMessage() }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                UiState.NeedsFolder -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Mimir", style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onPickFolder) { Text("Choose ROM folder") }
                }
                UiState.Scanning -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is UiState.Library -> LibraryGrid(
                    s,
                    onGameClick = viewModel::launchGame,
                    onRescan = { viewModel.savedTreeUri()?.let(viewModel::rescan) },
                    onPickFolder = onPickFolder,
                )
            }
        }
    }
}

@Composable
fun LibraryGrid(
    library: UiState.Library,
    onGameClick: (Game) -> Unit,
    onRescan: () -> Unit,
    onPickFolder: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${library.gamesByPlatform.values.sumOf { it.size }} games",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRescan) { Text("Rescan") }
                TextButton(onClick = onPickFolder) { Text("Change folder") }
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
        if (library.skipped.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "${library.skipped.size} files skipped — first: " +
                        "${library.skipped.first().relativePath} (${library.skipped.first().reason})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
```

- [ ] **Step 2: Build the full app**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 3: Run all module tests**

Run: `./gradlew test`
Expected: PASS — all `:core:scanner` and `:core:launcher` tests green.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat(app): library grid UI with SAF folder picking and tap-to-launch"
```

---

### Task 9: End-to-end verification on the Android emulator

**Files:**
- Create: `docs/superpowers/plans/m1-verification.md` (record of evidence)

- [ ] **Step 1: Start an emulator and install both APKs**

```bash
# any running AVD works; create one in Android Studio if none exist (API 34+ recommended)
adb wait-for-device
./gradlew :app:installDebug :tools:fake-emulator:installDebug
```
Expected: both `Installed on 1 device`.

- [ ] **Step 2: Seed fake ROM files on the device**

```bash
adb shell mkdir -p /sdcard/Roms/n64 /sdcard/Roms/GameCube /sdcard/Roms/nds
adb shell touch "/sdcard/Roms/n64/Mario Kart 64.z64"
adb shell touch "/sdcard/Roms/n64/GoldenEye.z64"
adb shell touch "/sdcard/Roms/GameCube/Wind Waker.rvz"
adb shell touch "/sdcard/Roms/nds/Mario Kart DS.nds"
adb shell touch "/sdcard/Roms/notes.txt"
```

- [ ] **Step 3: Walk the launch loop manually**

1. `adb shell am start dev.mimir.app/.MainActivity`
2. Tap **Choose ROM folder** → navigate to `Roms` → **Use this folder** → allow.
3. Expected grid: **GameCube (1)**, **Nintendo 64 (2)**, **Nintendo DS (1)** under their platform headers, and a "1 files skipped" line naming `notes.txt`.
4. Tap **Mario Kart 64** → Fake Emulator opens showing `action: android.intent.action.VIEW` and a `content://` data URI ending in the encoded file path.
5. Press back → tap **Rescan** → same counts (idempotent).

- [ ] **Step 4: Record evidence**

Write `docs/superpowers/plans/m1-verification.md` with: date, emulator AVD/API level, the platform counts seen, and the action/data line shown by Fake Emulator (copy from screen or `adb shell dumpsys activity activities | grep fakeemulator`). If any step deviated, record what and why before fixing.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "test: M1 end-to-end launch loop verified on emulator"
```

---

### Task 10: README

**Files:**
- Create: `README.md`
- Create: `LICENSE`

- [ ] **Step 1: Write `README.md`**

```markdown
# Mimir

An open-source (GPLv3), dual-screen-first emulation frontend for Android gaming
handhelds — built for the AYN Thor.

**Status: M1 — end-to-end launch loop.** Scan a ROM folder, browse the grid,
launch games in your emulators. Dual-screen shell, scraping, and theming land in
M2–M5 (see `docs/superpowers/specs/`).

## Why another launcher?

- **Open emulator model** — any emulator, any fork, any custom intent. No
  curated whitelist dead-ends.
- **Forgiving library scanning** — folder-name aliases (`GameCube` = `gc` =
  `ngc`, any casing) and a diagnostic report for every skipped file.
- **Dual-screen-first** — designed around the Thor's two displays, not adapted
  to them.
- **GPLv3** — this project can outlive its maintainer.

## Build

JDK 17 + Android SDK 36 required.

    ./gradlew :app:assembleDebug        # the launcher
    ./gradlew test                      # unit tests (pure-JVM core modules)
    ./gradlew :tools:fake-emulator:assembleDebug   # intent-catching test fixture

## Module map

| Module | What it is |
|---|---|
| `:app` | Android shell — Compose UI, SAF scanning, intent dispatch |
| `:core:scanner` | Pure JVM — platform registry + file→platform matcher |
| `:core:launcher` | Pure JVM — player (emulator) registry + intent templates |
| `:tools:fake-emulator` | Test fixture APK that displays any VIEW intent it receives |

Platform and player registries are JSON (`core/*/src/main/resources/`) — PRs to
add systems and emulators are the easiest way to contribute.
```

- [ ] **Step 2: Add the GPLv3 `LICENSE`**

Run: `curl -fsSL https://www.gnu.org/licenses/gpl-3.0.txt -o LICENSE && head -3 LICENSE`
Expected: `GNU GENERAL PUBLIC LICENSE / Version 3, 29 June 2007`.

- [ ] **Step 3: Final full check**

Run: `./gradlew test :app:assembleDebug :tools:fake-emulator:assembleDebug`
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "docs: README and GPLv3 license"
```

---

## Out of scope for M1 (lands later — do not add)

Room persistence, emulator auto-detection wizard, per-game player overrides, custom player creation UI, scraping/art, second-screen Presentation deck, theme tokens, incremental rescan, RetroArch core arguments, button remapping. M1's only job: pick folder → grid → launch → tested core logic.
