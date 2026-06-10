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
