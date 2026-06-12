package dev.mimir.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mimir.data.CustomPlayerEntity
import dev.mimir.data.GameEntity
import dev.mimir.data.GamePrefEntity
import dev.mimir.data.GameRepository
import dev.mimir.data.MediaEntity
import dev.mimir.data.PlatformPrefEntity
import dev.mimir.data.SkippedFileEntity
import dev.mimir.data.toPlayerDef
import dev.mimir.launcher.PlayerDef
import dev.mimir.launcher.PlayerDefs
import dev.mimir.launcher.PlayerPrefs
import dev.mimir.launcher.PlayerResolver
import dev.mimir.launcher.buildIntentSpec
import dev.mimir.launcher.mergePlayers
import dev.mimir.scanner.LibraryMatcher
import dev.mimir.scraper.EsdeImportMatcher
import dev.mimir.scanner.PlatformDefs
import dev.mimir.scanner.TreeAccessException
import dev.mimir.scanner.WalkEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UiState {
    data object NeedsFolder : UiState
    data class Error(val message: String) : UiState
    data class Library(
        val gamesByPlatform: Map<String, List<GameEntity>>, // key = platform display name
        val art: Map<String, String>,                       // game uri -> boxart url
        val skippedCount: Int,
        val scanning: Boolean,
        val scraping: ArtScraper.Progress?,
        val banner: String? = null,
    ) : UiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("mimir", Context.MODE_PRIVATE)
    private val platforms = PlatformDefs.load()
    private val platformNames = platforms.associate { it.id to it.name }
    private val matcher = LibraryMatcher(platforms)
    private val bundledPlayers = PlayerDefs.load()

    private fun installedPackages(): Set<String> =
        getApplication<Application>().packageManager.getInstalledPackages(0)
            .mapTo(mutableSetOf()) { it.packageName }

    private val db = (app as MimirApp).db
    private val repo = GameRepository(db.libraryDao())

    val playersState: StateFlow<List<PlayerDef>> =
        repo.customPlayers.map { custom -> mergePlayers(bundledPlayers, custom.map { it.toPlayerDef() }) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, bundledPlayers)

    private val playerPrefs = combine(repo.platformPrefs, repo.gamePrefs) { platform, game ->
        PlayerPrefs(
            platformDefaults = platform.associate { it.platformId to it.playerId },
            gameOverrides = game.associate { it.gameUri to it.playerId },
        )
    }

    /** Snapshot resolver for one-shot decisions (launch, settings render). */
    private fun resolver(prefs: PlayerPrefs) = PlayerResolver(playersState.value, installedPackages(), prefs)

    val prefsState: StateFlow<PlayerPrefs> =
        playerPrefs.stateIn(viewModelScope, SharingStarted.Eagerly, PlayerPrefs())

    /** platformId -> claimant players, for the settings screen. */
    fun claimantsByPlatform(): Map<String, List<PlayerDef>> =
        platforms.associate { p -> p.id to playersState.value.filter { p.id in it.platformIds } }

    fun platformsForSettings(): List<Pair<String, String>> = platforms.map { it.id to it.name }

    fun isPlayerInstalled(player: PlayerDef): Boolean =
        player.packageName in installedPackages()

    /** One snapshot per screen entry; avoids per-row PackageManager queries during recomposition. */
    fun installedSnapshot(): Set<String> = installedPackages()

    fun setPlatformDefault(platformId: String, playerId: String) {
        viewModelScope.launch { repo.setPlatformDefault(platformId, playerId) }
    }

    fun setGameOverride(game: GameEntity, playerId: String) {
        viewModelScope.launch { repo.setGameOverride(game.uri, playerId) }
    }

    fun clearGameOverride(game: GameEntity) {
        viewModelScope.launch { repo.clearGameOverride(game.uri) }
    }

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

    private val artScraper = ArtScraper(repo, platforms)
    private val scrapeProgress = MutableStateFlow<ArtScraper.Progress?>(null)

    private val treeUri = MutableStateFlow(prefs.getString("treeUri", null)?.let(Uri::parse))
    private val scanning = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    val skipped: StateFlow<List<SkippedFileEntity>> =
        repo.skipped.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private data class LibraryData(val games: List<GameEntity>, val art: Map<String, String>)
    private data class Status(val scanning: Boolean, val error: String?, val scraping: ArtScraper.Progress?)

    private val libraryData = combine(repo.games, repo.media) { games, media ->
        LibraryData(games, media.filter { it.kind == "boxart" }.associate { it.gameUri to it.boxartUrl })
    }
    private val status = combine(scanning, error, scrapeProgress) { scan, err, scrape ->
        Status(scan, err, scrape)
    }

    val state: StateFlow<UiState> =
        combine(treeUri, libraryData, skipped, status) { uri, lib, skip, st ->
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
                fetchArtwork()
            } catch (e: TreeAccessException) {
                error.value = "Can't read the ROM folder (${e.message}). Pick it again or check the storage."
            } catch (e: SecurityException) {
                error.value = "Access to the ROM folder was revoked. Pick it again."
            } finally {
                scanning.value = false
            }
        }
    }

    fun fetchArtwork() {
        if (scrapeProgress.value != null) return // already running
        scrapeProgress.value = ArtScraper.Progress(0, 0)
        viewModelScope.launch {
            try {
                artScraper.scrapeMissing { scrapeProgress.value = it }
            } finally {
                scrapeProgress.value = null
            }
        }
    }

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
                repo.saveArt(result.covers.map { (gameUri, imageUri) -> MediaEntity(gameUri, imageUri, source = "esde") })
                _message.value = buildString {
                    append("Imported ${result.covers.size} covers from ES-DE")
                    if (result.videoCount > 0) append(" — ${result.videoCount} videos found (video support comes with theming)")
                }
            } catch (e: TreeAccessException) {
                _message.value = "Couldn't read that folder (${e.message})"
            }
        }
    }

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
            LaunchResult.Success -> viewModelScope.launch { repo.stampPlayed(game.uri, System.currentTimeMillis()) }
        }
    }

    fun consumeMessage() { _message.value = null }
}
