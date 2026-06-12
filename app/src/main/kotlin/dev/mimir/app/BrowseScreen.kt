package dev.mimir.app

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import dev.mimir.data.GameEntity
import dev.mimir.theme.LocalMimirTheme
import kotlinx.coroutines.launch

private const val RAIL_THRESHOLD = 24

@Composable
fun BrowseScreen(
    library: UiState.Library,
    selectedUri: String?,
    onGameTap: (GameEntity) -> Unit,
    onGameLongPress: (GameEntity) -> Unit,
    initialSystem: String? = null,
    onSystemChange: () -> Unit = {},
) {
    val systems = library.gamesByPlatform.keys.toList()
    var activeSystem by rememberSaveable(systems) { mutableStateOf(initialSystem ?: systems.firstOrNull() ?: "") }
    var listMode by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val theme = LocalMimirTheme.current

    val allGames = library.gamesByPlatform[activeSystem].orEmpty()

    Column(Modifier.fillMaxSize()) {
        // System tabs (horizontal scroll when many)
        if (systems.size > 1) {
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(systems, key = { it }) { system ->
                    val on = system == activeSystem
                    Surface(
                        color = Color.White.copy(alpha = if (on) 0.18f else 0.07f),
                        contentColor = if (on) Color.White else Color.White.copy(alpha = 0.6f),
                        shape = MaterialTheme.shapes.extraLarge,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = if (on) 0.35f else 0.10f)),
                        modifier = Modifier
                            // active tab carries the ambient tint under its translucent white wash
                            .then(if (on) Modifier.background(theme.glow, MaterialTheme.shapes.extraLarge) else Modifier)
                            .clickable {
                                if (system != activeSystem) { activeSystem = system; onSystemChange() }
                            },
                    ) {
                        Text(
                            "$system  ·  ${library.gamesByPlatform[system].orEmpty().size}",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
        // Search + view toggle row
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search ${allGames.size} games") },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White.copy(alpha = 0.08f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                    focusedBorderColor = Color.White.copy(alpha = 0.35f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                ),
                modifier = Modifier.weight(1f).heightIn(max = 56.dp),
            )
            TextButton(onClick = { listMode = !listMode }) { Text(if (listMode) "⊞ Grid" else "≡ List") }
        }
        // Tab switches crossfade the grid/list region; each system composes its own
        // list (and scroll state) so both sides of the fade show the right content.
        Crossfade(targetState = activeSystem, animationSpec = tween(200), label = "systemContent") { sys ->
        val sysGames = library.gamesByPlatform[sys].orEmpty()
        val visibleTitles = BrowseLogic.filter(sysGames.map { it.title }, query).toSet()
        val games = sysGames.filter { it.title in visibleTitles }
        val gridState = rememberLazyGridState()
        val scope = rememberCoroutineScope()
        val sections = remember(games) { BrowseLogic.alphaSections(games.map { it.title }) }
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                if (listMode) {
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                        items(games, key = { it.uri }) { game ->
                            ListItem(
                                headlineContent = { Text(game.title) },
                                supportingContent = if (library.art[game.uri] == null) {
                                    { Text("no art", style = MaterialTheme.typography.labelSmall) }
                                } else null,
                                leadingContent = {
                                    coil3.compose.AsyncImage(
                                        model = library.art[game.uri], contentDescription = null,
                                        modifier = Modifier.size(width = 34.dp, height = 46.dp),
                                    )
                                },
                                // ListItem paints its own opaque container, so a Modifier.background
                                // behind it is invisible — the highlight must be the containerColor.
                                colors = ListItemDefaults.colors(
                                    containerColor = if (game.uri == selectedUri) theme.glow else Color.White.copy(alpha = 0.04f),
                                ),
                                modifier = Modifier
                                    .animateItem()
                                    .padding(vertical = 2.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .clickable { onGameTap(game) },
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 120.dp),
                        state = gridState,
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(games, key = { it.uri }) { game ->
                            GameCard(
                                game = game,
                                artUrl = library.art[game.uri],
                                selected = game.uri == selectedUri,
                                onClick = { onGameTap(game) },
                                onLongClick = { onGameLongPress(game) },
                                modifier = Modifier.animateItem(), // placement animation on filter/sort
                            )
                        }
                    }
                }
            }
            if (!listMode && games.size > RAIL_THRESHOLD) {
                AlphaRail(
                    sections = sections,
                    onJump = { index -> scope.launch { gridState.scrollToItem(index) } },
                )
            }
        }
        }
    }
}

@Composable
private fun AlphaRail(sections: Map<String, Int>, onJump: (Int) -> Unit) {
    val theme = LocalMimirTheme.current
    var railHeightPx by remember { mutableFloatStateOf(1f) }
    Column(
        Modifier
            .fillMaxHeight()
            .width(22.dp)
            .padding(vertical = 8.dp)
            .onGloballyPositioned { railHeightPx = it.size.height.toFloat() }
            .pointerInput(sections) {
                detectVerticalDragGestures { change, _ ->
                    val fraction = (change.position.y / railHeightPx).coerceIn(0f, 0.999f)
                    val letter = BrowseLogic.RAIL[(fraction * BrowseLogic.RAIL.size).toInt()]
                    sections[letter]?.let(onJump)
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        for (letter in BrowseLogic.RAIL) {
            Text(
                letter,
                style = MaterialTheme.typography.labelSmall,
                color = if (letter in sections) theme.primary else Color.White.copy(alpha = 0.25f),
                modifier = Modifier.clickable(enabled = letter in sections) { sections[letter]?.let(onJump) },
            )
        }
    }
}
