package dev.mimir.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
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
                        onFetchArtwork = viewModel::fetchArtwork,
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
    onFetchArtwork: () -> Unit,
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
                GameCard(game, artUrl = library.art[game.uri], onClick = { onGameClick(game) })
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
fun GameCard(game: GameEntity, artUrl: String?, onClick: () -> Unit) {
    Card(Modifier.height(180.dp).clickable(onClick = onClick)) {
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
