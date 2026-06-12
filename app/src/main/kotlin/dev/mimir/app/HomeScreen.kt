package dev.mimir.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.mimir.data.GameEntity
import dev.mimir.theme.LocalMimirTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    recents: List<MainViewModel.RecentEntry>,
    systems: List<Pair<String, Int>>, // display name to count
    onPlay: (GameEntity) -> Unit,
    onGameLongPress: (GameEntity) -> Unit,
    onOpenSystem: (String) -> Unit,
    onBrowseAll: () -> Unit,
) {
    val theme = LocalMimirTheme.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        SectionLabel("Continue playing")
        if (recents.isEmpty()) {
            Text(
                "Play something and it'll show up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(recents, key = { it.game.uri }) { entry ->
                    Card(
                        Modifier
                            .size(width = 132.dp, height = 178.dp)
                            .combinedClickable(onClick = { onPlay(entry.game) }, onLongClick = { onGameLongPress(entry.game) }),
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            if (entry.artUrl != null) {
                                AsyncImage(
                                    model = entry.artUrl, contentDescription = entry.game.title,
                                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Box(
                                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    entry.game.title, style = MaterialTheme.typography.labelMedium,
                                    color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            SectionLabel("Systems", modifier = Modifier.weight(1f))
            Text(
                "Browse all ›",
                style = MaterialTheme.typography.labelMedium,
                color = theme.primary,
                modifier = Modifier.clickable(onClick = onBrowseAll).padding(horizontal = 16.dp),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(systems, key = { it.first }) { (name, count) ->
                Surface(
                    color = Color.White.copy(alpha = 0.06f),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.clickable { onOpenSystem(name) },
                ) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(name, style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.9f))
                        Text(
                            "$count ${if (count == 1) "game" else "games"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.45f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = Color.White.copy(alpha = 0.55f),
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}
