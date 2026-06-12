package dev.mimir.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.mimir.data.GameEntity
import dev.mimir.data.SkippedFileEntity
import dev.mimir.launcher.PlayerDef
import dev.mimir.theme.LocalMimirTheme
import dev.mimir.theme.MimirTheme

enum class Route { HOME, BROWSE, SETTINGS, REPORT }

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

    private val createBackup = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) contentResolver.openOutputStream(uri)?.let(viewModel::exportBackup)
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
                        onBackup = { createBackup.launch("mimir-backup.zip") },
                    )
                }
            }
        }
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel, onPickFolder: () -> Unit, onImportEsde: () -> Unit, onBackup: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val message by viewModel.message.collectAsState()
    val skipped by viewModel.skipped.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var route by rememberSaveable { mutableStateOf(Route.HOME) }
    var browseSystem by rememberSaveable { mutableStateOf<String?>(null) }
    var sheetGame by remember { mutableStateOf<GameEntity?>(null) }
    val prefs by viewModel.prefsState.collectAsState()
    val haptic = LocalHapticFeedback.current

    BackHandler(enabled = route != Route.HOME) {
        viewModel.play(SoundEngine.Cue.BACK)
        route = if (route == Route.BROWSE) Route.HOME else Route.BROWSE
    }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.consumeMessage() }
    }

    // Zero content insets: the hero art must run edge-to-edge behind the status bar.
    // L2's top scrim provides status-bar protection; insets are re-applied where needed.
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
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
                is UiState.Library -> {
                    val theme = LocalMimirTheme.current
                    val heroState by viewModel.heroArt.collectAsState()
                    val selectedUri by viewModel.selectedUriState.collectAsState()
                    val hazeState = remember { HazeState() }
                    val panelShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                    Box(Modifier.fillMaxSize()) {
                        // L1 — hero art fills the entire screen behind everything
                        Crossfade(
                            targetState = heroState.heroUrl ?: heroState.boxartUrl,
                            animationSpec = tween(600),
                            label = "heroArt",
                        ) { art ->
                            if (art != null) {
                                AsyncImage(
                                    model = art,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize().hazeSource(hazeState),
                                )
                            } else {
                                Box(Modifier.fillMaxSize().background(theme.scrim).hazeSource(hazeState))
                            }
                        }
                        // L2 — scrims: status-bar protection on top, fade toward theme scrim below
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(
                                    0f to Color.Black.copy(alpha = 0.35f),
                                    0.18f to Color.Transparent,
                                    0.40f to Color.Transparent,
                                    0.95f to theme.scrim,
                                )
                            )
                        )
                        // L3 — logo + pills float just above the glass panel's top edge
                        HeroOverlay(
                            hero = heroState,
                            platformName = viewModel::platformName,
                            emulatorName = viewModel::resolvedEmulatorName,
                            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().fillMaxHeight(0.38f),
                        )
                        // L5 — top-right controls (no overlap with the lower panel)
                        Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp)) {
                                TextButton(onClick = { route = Route.SETTINGS }) { Text("⚙") }
                                Box {
                                    var menuOpen by remember { mutableStateOf(false) }
                                    TextButton(onClick = { menuOpen = true }) { Text("⋮") }
                                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                        DropdownMenuItem(
                                            text = { Text("Rescan") },
                                            enabled = !s.scanning,
                                            onClick = { menuOpen = false; viewModel.rescan() },
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    if (s.scraping == null) "Fetch artwork"
                                                    else "Artwork ${s.scraping.done}/${s.scraping.total}"
                                                )
                                            },
                                            enabled = s.scraping == null,
                                            onClick = { menuOpen = false; viewModel.fetchArtwork() },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Import ES-DE") },
                                            onClick = { menuOpen = false; onImportEsde() },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Scan report (${s.skippedCount} skipped)") },
                                            onClick = { menuOpen = false; route = Route.REPORT },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Back up art & themes") },
                                            onClick = { menuOpen = false; onBackup() },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Change ROM folder") },
                                            onClick = { menuOpen = false; onPickFolder() },
                                        )
                                    }
                                }
                            }
                        // L4 — glass content panel over the lower ~62%
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .fillMaxHeight(0.62f)
                                .clip(panelShape)
                                .hazeEffect(
                                    hazeState,
                                    style = HazeStyle(
                                        backgroundColor = theme.scrim,
                                        tints = listOf(
                                            HazeTint(theme.scrim.copy(alpha = 0.55f)),
                                            HazeTint(theme.glow),
                                        ),
                                        blurRadius = 24.dp,
                                        noiseFactor = 0.02f,
                                    ),
                                ),
                        ) {
                            AnimatedContent(
                                targetState = route,
                                transitionSpec = {
                                    (slideInVertically { it / 8 } + fadeIn(tween(250))) togetherWith fadeOut(tween(150))
                                },
                                label = "route",
                            ) { r ->
                            when (r) {
                                Route.HOME -> {
                                    val recents by viewModel.recents.collectAsState()
                                    HomeScreen(
                                        recents = recents,
                                        systems = viewModel.systemRow(s),
                                        onPlay = { game ->
                                            if (viewModel.hapticsEnabled()) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.launchGame(game)
                                        },
                                        onGameLongPress = { sheetGame = it },
                                        onOpenSystem = {
                                            viewModel.play(SoundEngine.Cue.NAV)
                                            browseSystem = it; route = Route.BROWSE
                                        },
                                        onBrowseAll = {
                                            viewModel.play(SoundEngine.Cue.NAV)
                                            browseSystem = null; route = Route.BROWSE
                                        },
                                    )
                                }
                                Route.REPORT -> ScanReportScreen(skipped, onBack = { route = Route.BROWSE })
                                Route.SETTINGS -> {
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
                                        onBack = { route = Route.BROWSE },
                                    )
                                }
                                Route.BROWSE -> Column(Modifier.fillMaxSize()) {
                                    if (s.scanning) {
                                        LinearProgressIndicator(Modifier.fillMaxWidth())
                                    }
                                    if (s.banner != null) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.errorContainer,
                                            shape = MaterialTheme.shapes.small,
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                        ) {
                                            Row(
                                                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    s.banner,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                                    modifier = Modifier.weight(1f),
                                                )
                                                TextButton(onClick = viewModel::rescan) { Text("Retry") }
                                            }
                                        }
                                    }
                                    BrowseScreen(
                                        library = s,
                                        selectedUri = selectedUri,
                                        onGameTap = { game ->
                                            if (viewModel.hapticsEnabled()) {
                                                haptic.performHapticFeedback(
                                                    if (game.uri == selectedUri) HapticFeedbackType.LongPress // re-tap launches
                                                    else HapticFeedbackType.TextHandleMove                    // first tap selects
                                                )
                                            }
                                            viewModel.onGameTapped(game)
                                        },
                                        onGameLongPress = { sheetGame = it },
                                        initialSystem = browseSystem,
                                        onSystemChange = { viewModel.play(SoundEngine.Cue.NAV) },
                                    )
                                }
                            }
                            }
                        }
                    }
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

/** Shared press feedback: cards/tiles scale down slightly while pressed. */
@Composable
fun Modifier.pressScale(interactionSource: MutableInteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(), label = "pressScale")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameCard(
    game: GameEntity,
    artUrl: String?,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = CardDefaults.shape
    val theme = LocalMimirTheme.current
    val interactionSource = remember { MutableInteractionSource() }
    val borderColor by animateColorAsState(
        if (selected) theme.primary else Color.White.copy(alpha = 0.14f),
        tween(250),
        label = "cardBorder",
    )
    val glowElevation by animateDpAsState(if (selected) 16.dp else 2.dp, tween(250), label = "cardGlow")
    Card(
        modifier
            .height(180.dp)
            .pressScale(interactionSource)
            .shadow(glowElevation, shape, ambientColor = theme.primary, spotColor = theme.primary)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = shape,
        border = BorderStroke(1.dp, borderColor),
    ) {
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
