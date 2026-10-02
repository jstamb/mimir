# Mimir M2c — Custom Player Definitions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The no-whitelist escape hatch — register ANY installed app as an emulator for any platforms, from the UI. After this, an emulator rename (the Azahar-class breakage that plagues Cocoon) is a 30-second user fix, completing the project's core thesis.

**Architecture:** Custom players persist in Room v4 (`custom_players` table, tested 3→4 migration) and are merged with the bundled registry by a pure, tested `mergePlayers` function in `:core:launcher` (order: bundled real emulators → custom → fake-emulator LAST, preserving the installed-order semantics). The app's player list becomes reactive (a flow), so custom players appear instantly in Settings rows, the Play-with sheet, and resolution. UI: an "Add custom emulator" flow in the Emulators screen — pick from launchable installed apps, multi-select platforms, save; custom entries get a delete affordance.

**Conventions:** Repo `~/Development/mimir`, branch from `master` (v0.4.0-m2b, 54 tests). `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` before every `./gradlew`. Commits end with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

### Task 1: `:core:launcher` — mergePlayers (TDD)

**Files:**
- Modify: `core/launcher/src/main/kotlin/dev/mimir/launcher/PlayerResolver.kt`
- Test: modify `core/launcher/src/test/kotlin/dev/mimir/launcher/PlayerResolverTest.kt`

- [ ] **Step 1: Failing tests** (add to PlayerResolverTest):
```kotlin
    @Test
    fun `mergePlayers keeps fake last and slots customs after bundled real emulators`() {
        val custom = PlayerDef("custom-com.example.emu", "My Emu", "com.example.emu", platformIds = listOf("n64"))
        val merged = mergePlayers(bundled = players, custom = listOf(custom))
        assertEquals(listOf("melonds", "drastic", "custom-com.example.emu", "fake-emulator"), merged.map { it.id })
    }

    @Test
    fun `mergePlayers with no fake entry just appends customs`() {
        val custom = PlayerDef("custom-x", "X", "com.example.x")
        val merged = mergePlayers(bundled = listOf(real, other), custom = listOf(custom))
        assertEquals(listOf("melonds", "drastic", "custom-x"), merged.map { it.id })
    }

    @Test
    fun `custom claiming a platform wins installed-order over fake`() {
        val custom = PlayerDef("custom-com.example.emu", "My Emu", "com.example.emu", platformIds = listOf("n64"))
        val r = PlayerResolver(
            mergePlayers(players, listOf(custom)),
            installedPackages = setOf("com.example.emu", "dev.mimir.fakeemulator"),
            prefs = PlayerPrefs(),
        )
        assertEquals("custom-com.example.emu", r.resolve("uri-x", "n64")?.id)
    }
```
Run `./gradlew :core:launcher:test` → FAIL (unresolved `mergePlayers`).

- [ ] **Step 2: Implement** — add to `PlayerResolver.kt` (top-level):
```kotlin
/** Bundled real emulators first, then custom players, then the fake-emulator fixture last. */
fun mergePlayers(bundled: List<PlayerDef>, custom: List<PlayerDef>): List<PlayerDef> {
    val (fake, real) = bundled.partition { it.id == "fake-emulator" }
    return real + custom + fake
}
```
Run → PASS (launcher = 16). Commit:
```bash
git add -A && git commit -m "feat(launcher): mergePlayers slots custom emulators ahead of the fake fixture"
```

---

### Task 2: `:core:data` — custom_players table, DB v4 (TDD)

**Files:**
- Modify: `core/data/src/main/kotlin/dev/mimir/data/Entities.kt`
- Modify: `core/data/src/main/kotlin/dev/mimir/data/LibraryDao.kt`
- Modify: `core/data/src/main/kotlin/dev/mimir/data/MimirDatabase.kt`
- Modify: `core/data/src/main/kotlin/dev/mimir/data/GameRepository.kt`
- Test: modify `core/data/src/test/kotlin/dev/mimir/data/MigrationTest.kt`, `LibraryDaoTest.kt`

- [ ] **Step 1: Failing tests.** Add to `MigrationTest.kt`:
```kotlin
    @Test
    fun `migrate 3 to 4 preserves prefs and creates custom_players`() {
        helper.createDatabase("migration-test-4", 3).apply {
            execSQL("INSERT INTO platform_prefs (platformId, playerId) VALUES ('nds', 'melonds')")
            close()
        }
        val db = helper.runMigrationsAndValidate("migration-test-4", 4, true, MIGRATION_3_4)
        db.query("SELECT playerId FROM platform_prefs WHERE platformId = 'nds'").use { c ->
            c.moveToFirst(); assertEquals("melonds", c.getString(0))
        }
        db.query("SELECT COUNT(*) FROM custom_players").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
    }
```
Add to `LibraryDaoTest.kt`:
```kotlin
    @Test
    fun `custom players round trip and delete`() = runBlocking {
        val dao = db().libraryDao()
        val entity = CustomPlayerEntity(
            id = "custom-com.example.emu",
            name = "My Emu",
            packageName = "com.example.emu",
            activityClass = null,
            action = "android.intent.action.VIEW",
            platformIds = "n64,gc",
        )
        dao.upsertCustomPlayer(entity)
        assertEquals(listOf(entity), dao.customPlayers().first())
        assertEquals(listOf("n64", "gc"), dao.customPlayers().first().single().toPlayerDef().platformIds)
        dao.deleteCustomPlayer("custom-com.example.emu")
        assertEquals(emptyList<CustomPlayerEntity>(), dao.customPlayers().first())
    }
```
Run → FAIL.

- [ ] **Step 2: Implement.** `Entities.kt` add (plus `dev.mimir.launcher` is NOT a dependency of :core:data... it IS not — check: core:data depends on :core:scanner only. `toPlayerDef` needs PlayerDef from :core:launcher → add `api(project(":core:launcher"))` to core/data/build.gradle.kts dependencies):
```kotlin
@Entity(tableName = "custom_players")
data class CustomPlayerEntity(
    @PrimaryKey val id: String,            // "custom-" + packageName
    val name: String,
    val packageName: String,
    val activityClass: String?,
    val action: String,
    val platformIds: String,               // comma-joined platform ids
)

fun CustomPlayerEntity.toPlayerDef() = dev.mimir.launcher.PlayerDef(
    id = id,
    name = name,
    packageName = packageName,
    activityClass = activityClass,
    action = action,
    platformIds = platformIds.split(',').filter { it.isNotBlank() },
)
```
`LibraryDao.kt` add:
```kotlin
    @Query("SELECT * FROM custom_players")
    fun customPlayers(): Flow<List<CustomPlayerEntity>>

    @Upsert
    suspend fun upsertCustomPlayer(player: CustomPlayerEntity)

    @Query("DELETE FROM custom_players WHERE id = :id")
    suspend fun deleteCustomPlayer(id: String)
```
`MimirDatabase.kt`: entities gains `CustomPlayerEntity::class`, `version = 4`, add:
```kotlin
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `custom_players` " +
                "(`id` TEXT NOT NULL, `name` TEXT NOT NULL, `packageName` TEXT NOT NULL, " +
                "`activityClass` TEXT, `action` TEXT NOT NULL, `platformIds` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
    }
}
```
`GameRepository.kt` add:
```kotlin
    val customPlayers: Flow<List<CustomPlayerEntity>> = dao.customPlayers()
    suspend fun saveCustomPlayer(player: CustomPlayerEntity) = dao.upsertCustomPlayer(player)
    suspend fun deleteCustomPlayer(id: String) = dao.deleteCustomPlayer(id)
```
Commit the new `schemas/.../4.json`. Run → PASS (data = 12). Commit:
```bash
git add -A && git commit -m "feat(data): custom players table, DB v4 with tested 3->4 migration"
```

---

### Task 3: `:app` — reactive players + add/delete custom UI

**Files:**
- Modify: `app/src/main/kotlin/dev/mimir/app/MimirApp.kt` (add MIGRATION_3_4 to addMigrations)
- Modify: `app/src/main/kotlin/dev/mimir/app/MainViewModel.kt`
- Modify: `app/src/main/kotlin/dev/mimir/app/EmulatorSettingsScreen.kt`
- Modify: `app/src/main/kotlin/dev/mimir/app/MainActivity.kt`

- [ ] **Step 1: MimirApp** — `.addMigrations(MIGRATION_2_3, MIGRATION_3_4)` (import MIGRATION_3_4).

- [ ] **Step 2: MainViewModel — players become reactive.** Replace `private val players = PlayerDefs.load()` with:
```kotlin
    private val bundledPlayers = PlayerDefs.load()
    val playersState: StateFlow<List<PlayerDef>> =
        repo.customPlayers.map { custom -> mergePlayers(bundledPlayers, custom.map { it.toPlayerDef() }) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, bundledPlayers)
```
(imports: `dev.mimir.data.CustomPlayerEntity`, `dev.mimir.data.toPlayerDef`, `dev.mimir.launcher.mergePlayers`, `kotlinx.coroutines.flow.map`.)
Update every use of the old `players` val to `playersState.value` (resolver(), claimantsByPlatform()). Add:
```kotlin
    /** Launchable installed apps for the custom-emulator picker: label to packageName, sorted. */
    fun launchableApps(): List<Pair<String, String>> {
        val pm = getApplication<Application>().packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .filterNot { it.second == getApplication<Application>().packageName }
            .sortedBy { it.first.lowercase() }
    }

    fun addCustomPlayer(name: String, packageName: String, platformIds: List<String>) {
        viewModelScope.launch {
            repo.saveCustomPlayer(
                CustomPlayerEntity(
                    id = "custom-$packageName",
                    name = name,
                    packageName = packageName,
                    activityClass = null,
                    action = "android.intent.action.VIEW",
                    platformIds = platformIds.joinToString(","),
                )
            )
        }
    }

    fun deleteCustomPlayer(playerId: String) {
        viewModelScope.launch { repo.deleteCustomPlayer(playerId) }
    }
```

- [ ] **Step 3: EmulatorSettingsScreen — add-custom flow + custom list.** Add parameters to `EmulatorSettingsScreen`: `customPlayers: List<PlayerDef>`, `launchableApps: () -> List<Pair<String, String>>`, `onAddCustom: (name: String, packageName: String, platformIds: List<String>) -> Unit`, `onDeleteCustom: (String) -> Unit`. Below the platform list (inside the LazyColumn as trailing items), add:
```kotlin
            item {
                Column(Modifier.padding(top = 16.dp)) {
                    Text("Custom emulators", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Register any installed app as an emulator — no whitelist.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(customPlayers, key = { it.id }) { player ->
                Card {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(player.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${player.packageName} — ${player.platformIds.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onDeleteCustom(player.id) }) { Text("Remove") }
                    }
                }
            }
            item {
                var showAdd by remember { mutableStateOf(false) }
                Button(onClick = { showAdd = true }, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Add custom emulator")
                }
                if (showAdd) {
                    AddCustomPlayerDialog(
                        platforms = platforms,
                        launchableApps = launchableApps,
                        onAdd = { name, pkg, ids -> onAddCustom(name, pkg, ids); showAdd = false },
                        onDismiss = { showAdd = false },
                    )
                }
            }
```
Add the dialog composable (same file; imports for `AlertDialog`, `Checkbox`, `LazyColumn` height constraint via `Modifier.heightIn`):
```kotlin
@Composable
private fun AddCustomPlayerDialog(
    platforms: List<Pair<String, String>>,
    launchableApps: () -> List<Pair<String, String>>,
    onAdd: (name: String, packageName: String, platformIds: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val apps = remember { launchableApps() }
    var selectedApp by remember { mutableStateOf<Pair<String, String>?>(null) }
    val selectedPlatforms = remember { mutableStateListOf<String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (selectedApp == null) "Pick the app" else "Pick its systems") },
        text = {
            if (selectedApp == null) {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(apps, key = { it.second }) { app ->
                        ListItem(
                            headlineContent = { Text(app.first) },
                            supportingContent = { Text(app.second, style = MaterialTheme.typography.bodySmall) },
                            modifier = Modifier.clickable { selectedApp = app },
                        )
                    }
                }
            } else {
                Column {
                    Text(selectedApp!!.first, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(platforms, key = { it.first }) { (id, name) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    if (id in selectedPlatforms) selectedPlatforms.remove(id)
                                    else selectedPlatforms.add(id)
                                },
                            ) {
                                Checkbox(
                                    checked = id in selectedPlatforms,
                                    onCheckedChange = {
                                        if (id in selectedPlatforms) selectedPlatforms.remove(id)
                                        else selectedPlatforms.add(id)
                                    },
                                )
                                Text(name)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedApp != null && selectedPlatforms.isNotEmpty(),
                onClick = { onAdd(selectedApp!!.first, selectedApp!!.second, selectedPlatforms.toList()) },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
```
(imports to add in this file: `androidx.compose.foundation.clickable`, `androidx.compose.runtime.mutableStateListOf`.)

- [ ] **Step 4: MainActivity** — the settings branch passes the new params:
```kotlin
                        val players by viewModel.playersState.collectAsState()
                        EmulatorSettingsScreen(
                            platforms = viewModel.platformsForSettings(),
                            claimants = viewModel.claimantsByPlatform(),
                            prefs = prefs,
                            customPlayers = players.filter { it.id.startsWith("custom-") },
                            launchableApps = viewModel::launchableApps,
                            onAddCustom = viewModel::addCustomPlayer,
                            onDeleteCustom = viewModel::deleteCustomPlayer,
                            isInstalled = { it.packageName in installed },
                            onSetDefault = viewModel::setPlatformDefault,
                            onBack = { showSettings = false },
                        )
```
(NOTE: `claimantsByPlatform()` reads `playersState.value` so it must be recomputed when players change — since `players` is collected in this branch, recomposition re-calls it; acceptable.)

- [ ] **Step 5: Build + tests** — `./gradlew :app:assembleDebug test` → Expected **59 tests**, 0 failures (scanner 18, scraper 12, data 12, launcher 16, app 1). Commit:
```bash
git add -A && git commit -m "feat(app): custom emulator creation UI with reactive player registry"
```

---

### Task 4: E2E + docs

**Files:**
- Create: `docs/superpowers/plans/m2c-verification.md`, `docs/superpowers/plans/img/m2c-custom.png`
- Modify: `README.md`, `app/build.gradle.kts`

- [ ] **Step 1: E2E on emulator-5556** (boot headless if needed; install over existing — verifies 3→4 migration preserves the melonDS NDS default from the M2b run):
  1. Install + launch → library intact (4 games + art), Settings → Emulators still shows "Default: melonDS (not installed)" for NDS → migration preserved prefs.
  2. Settings → Add custom emulator → pick **Chrome** (present on google_apis images; any launchable app works — record which you used) → check **GameCube** → Add. Verify the custom card appears ("Chrome — gc") and the GameCube row's dropdown now contains Chrome.
  3. Set GameCube default = Chrome. Tap **Wind Waker** → Chrome opens (attempts to view the content URI — any Chrome rendering of/error about the file is SUCCESS; the handoff is what's under test). Screenshot the settings screen with the custom card → `img/m2c-custom.png`.
  4. Remove the custom player → GameCube dropdown no longer lists Chrome; tapping Wind Waker falls back to fake-emulator... NOTE: the platform default still points at the deleted "custom-..." id — resolution can't find it (byId miss) → falls through to platform default tier returning null? READ PlayerResolver: `prefs.platformDefaults[platformId]?.let(byId::get)` → null when deleted → falls to installed-claimant tier → fake. Verify that's what happens (no crash, fake opens). Record this dangling-pref behavior as a known-acceptable finding.
  5. Force-stop + relaunch → custom-player list state persists correctly (still removed), logcat clean.
- [ ] **Step 2:** README status paragraph → replace M2b paragraph:
```markdown
**Status: M2c — open emulator model complete.** Register ANY installed app as
an emulator for any system (Settings → Emulators → Add custom emulator) — the
no-whitelist escape hatch. Per-system defaults, per-game overrides, installed-
aware resolution, boxart, persistent fast-scanning library all in. Next: UI
polish + theming.
```
`app/build.gradle.kts`: `versionCode = 5`, `versionName = "0.5.0-m2c"`. Final `--rerun-tasks` check. Commits per evidence + docs (2 commits, conventional messages with trailer).
