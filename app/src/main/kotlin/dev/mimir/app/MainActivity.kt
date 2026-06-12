package dev.mimir.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.mimir.data.GameEntity
import dev.mimir.data.SkippedFileEntity
import dev.mimir.launcher.PlayerDef
import dev.mimir.theme.LocalMimirTheme
import dev.mimir.theme.MimirTheme

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val config by viewModel.themeConfig.collectAsState()
            val ambient by viewModel.ambient.collectAsState()
            MimirTheme(config = config, ambient = ambient) {
                MaterialTheme(colorScheme = darkColorScheme(primary = LocalMimirTheme.current.primary)) {
                    MainScreen(
                        viewModel = viewModel,
                        onPickFolder = { pickFolder.launch(null) },
                        onImportEsde = { pickEsdeMedia.launch(null) },
                    )
                }
            }
        }
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel, onPickFolder: () -> Unit, onImportEsde: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val message by viewModel.message.collectAsState()
    val skipped by viewModel.skipped.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showReport by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var sheetGame by remember { mutableStateOf<GameEntity?>(null) }
    val prefs by viewModel.prefsState.collectAsState()

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.consumeMessage() }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(LocalMimirTheme.current.glow, Color.Transparent)))
                .padding(padding)
        ) {
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
                is UiState.Library -> when {
                    showReport -> ScanReportScreen(skipped, onBack = { showReport = false })
                    showSettings -> {
                        val installed = remember { viewModel.installedSnapshot() }
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
                            sgdbKey = viewModel.sgdbApiKey(),
                            onSaveSgdbKey = viewModel::setSgdbApiKey,
                            onFetchSgdb = viewModel::fetchSgdbArt,
                            onBack = { showSettings = false },
                        )
                    }
                    else -> LibraryGrid(
                        s,
                        onGameClick = { viewModel.launchGame(it) },
                        onGameLongClick = { sheetGame = it },
                        onRescan = viewModel::rescan,
                        onPickFolder = onPickFolder,
                        onShowReport = { showReport = true },
                        onShowSettings = { showSettings = true },
                        onFetchArtwork = viewModel::fetchArtwork,
                        onImportEsde = onImportEsde,
                    )
                }
            }
            sheetGame?.let { game ->
                val installed = remember(game) { viewModel.installedSnapshot() }
                PlayWithSheet(
                    game = game,
                    claimants = viewModel.claimantsByPlatform()[game.platformId].orEmpty(),
                    overrideId = prefs.gameOverrides[game.uri],
                    isInstalled = { it.packageName in installed },
                    onPick = { player ->
                        viewModel.setGameOverride(game, player.id)
                        sheetGame = null
                        viewModel.launchGame(game, forced = player)
                    },
                    onClearOverride = { viewModel.clearGameOverride(game); sheetGame = null },
                    onDismiss = { sheetGame = null },
                )
            }
        }
    }
}

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
    onGameLongClick: (GameEntity) -> Unit,
    onRescan: () -> Unit,
    onPickFolder: () -> Unit,
    onShowReport: () -> Unit,
    onShowSettings: () -> Unit,
    onFetchArtwork: () -> Unit,
    onImportEsde: () -> Unit,
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
                    TextButton(onClick = onFetchArtwork, enabled = library.scraping == null) {
                        Text(if (library.scraping == null) "Fetch artwork" else "Artwork ${library.scraping.done}/${library.scraping.total}")
                    }
                    TextButton(onClick = onImportEsde) { Text("Import ES-DE") }
                    TextButton(onClick = onShowSettings) { Text("Emulators") }
                    TextButton(onClick = onRescan, enabled = !library.scanning) { Text("Rescan") }
                    TextButton(onClick = onPickFolder) { Text("Change folder") }
                }
                if (library.scanning) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
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
            }
        }
        library.gamesByPlatform.forEach { (platformName, games) ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = 8.dp)) {
                    Text(platformName, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${games.size} ${if (games.size == 1) "game" else "games"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(games, key = { it.uri }) { game ->
                GameCard(
                    game,
                    artUrl = library.art[game.uri],
                    onClick = { onGameClick(game) },
                    onLongClick = { onGameLongClick(game) },
                )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameCard(game: GameEntity, artUrl: String?, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(Modifier.height(180.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(Modifier.fillMaxSize()) {
            if (artUrl != null) {
                AsyncImage(
                    model = artUrl,
                    contentDescription = game.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(
                        game.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                    Text(game.title, style = MaterialTheme.typography.bodyMedium)
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
