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
import dev.mimir.data.MediaEntity
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
        val art: Map<String, String>,                       // game uri -> boxart url
        val skippedCount: Int,
        val scanning: Boolean,
        val scraping: ArtScraper.Progress?,
    ) : UiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("mimir", Context.MODE_PRIVATE)
    private val platforms = PlatformDefs.load()
    private val platformNames = platforms.associate { it.id to it.name }
    private val matcher = LibraryMatcher(platforms)
    private val players = PlayerDefs.load()
    private val db = Room.databaseBuilder(app, MimirDatabase::class.java, "mimir.db")
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
    private val repo = GameRepository(db.libraryDao())
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
        LibraryData(games, media.associate { it.gameUri to it.boxartUrl })
    }
    private val status = combine(scanning, error, scrapeProgress) { scan, err, scrape ->
        Status(scan, err, scrape)
    }

    val state: StateFlow<UiState> =
        combine(treeUri, libraryData, skipped, status) { uri, lib, skip, st ->
            when {
                st.error != null -> UiState.Error(st.error)
                uri == null -> UiState.NeedsFolder
                else -> UiState.Library(
                    gamesByPlatform = lib.games
                        .groupBy { platformNames[it.platformId] ?: it.platformId }
                        .toSortedMap(),
                    art = lib.art,
                    skippedCount = skip.size,
                    scanning = st.scanning,
                    scraping = st.scraping,
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
        viewModelScope.launch {
            try {
                artScraper.scrapeMissing { scrapeProgress.value = it }
            } finally {
                scrapeProgress.value = null
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
