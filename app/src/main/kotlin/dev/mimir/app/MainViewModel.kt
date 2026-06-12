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
import dev.mimir.theme.AmbientPalette
import dev.mimir.theme.PaletteMode
import dev.mimir.theme.ThemeConfig
import dev.mimir.scraper.EsdeImportMatcher
import dev.mimir.scraper.FolderArtMatcher
import dev.mimir.scraper.SgdbClient
import dev.mimir.scanner.PlatformDefs
import dev.mimir.scanner.TreeAccessException
import dev.mimir.scanner.WalkEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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

    // Order matters: these reference repo above (same init-order NPE class as M2c).
    private val themeStore = (app as MimirApp).themeStore
    private val paletteExtractor = (app as MimirApp).paletteExtractor

    val themeConfig: StateFlow<ThemeConfig> =
        themeStore.config.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeConfig())

    private val sound = (app as MimirApp).soundEngine

    init {
        viewModelScope.launch { themeStore.config.collect { sound.config = it } }
    }

    fun play(cue: SoundEngine.Cue) = sound.play(cue)
    fun hapticsEnabled() = sound.hapticsEnabled()

    private val selectedGameUri = MutableStateFlow<String?>(null)
    val selectedUriState: StateFlow<String?> = selectedGameUri

    fun selectGame(game: GameEntity) { selectedGameUri.value = game.uri }

    /** Tap behavior: first tap selects, second tap on the same game launches. */
    fun onGameTapped(game: GameEntity) {
        if (selectedGameUri.value == game.uri) {
            launchGame(game)
        } else {
            play(SoundEngine.Cue.SELECT)
            selectGame(game)
        }
    }

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

    /** Ambient palette follows the focused (selected, else last-played) game's hero/boxart. */
    val ambient: StateFlow<AmbientPalette> =
        heroArt.map { hero ->
            if (themeConfig.value.paletteMode == PaletteMode.FIXED)
                AmbientPalette.from(themeConfig.value.fixedSeedArgb)
            else paletteExtractor.extract(hero.heroUrl ?: hero.boxartUrl)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, AmbientPalette.from(AmbientPalette.FALLBACK_ARGB))

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

    /** Resolved emulator name for the hero pane pills. */
    fun resolvedEmulatorName(game: GameEntity): String =
        resolver(prefsState.value).resolve(game.uri, game.platformId)?.name ?: "none"

    fun platformName(id: String): String = platformNames[id] ?: id

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
    private val sgdbProgress = MutableStateFlow<ArtScraper.Progress?>(null)

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
                    val files = WalkEngine.walk(source, source.rootUri)
                    val scan = matcher.match(files)
                    scan to FolderArtMatcher.match(files, scan.games)
                }
                repo.applyScan(result.first)
                repo.saveArt(result.second.map { (gameUri, artUri) -> MediaEntity(gameUri, artUri, "boxart", "folder") })
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

    fun sgdbApiKey(): String = prefs.getString("sgdbApiKey", "") ?: ""
    fun setSgdbApiKey(key: String) { prefs.edit { putString("sgdbApiKey", key.trim()) } }

    fun fetchSgdbArt() {
        val key = sgdbApiKey()
        if (key.isBlank()) {
            _message.value = "Add your SteamGridDB API key first (it's free — steamgriddb.com/profile/preferences/api)"
            return
        }
        if (sgdbProgress.value != null) return // already running
        sgdbProgress.value = ArtScraper.Progress(0, 0)
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    val http = okhttp3.OkHttpClient()
                    val client = SgdbClient(key) { url, headers ->
                        runCatching {
                            val req = okhttp3.Request.Builder().url(url)
                                .apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build()
                            http.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
                        }.getOrNull()
                    }
                    var saved = 0
                    val games = repo.games.first()
                    val media = repo.mediaSnapshot()
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
            } finally {
                sgdbProgress.value = null
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
            LaunchResult.Success -> {
                play(SoundEngine.Cue.LAUNCH)
                viewModelScope.launch { repo.stampPlayed(game.uri, System.currentTimeMillis()) }
            }
        }
    }

    fun consumeMessage() { _message.value = null }
}
