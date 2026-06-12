package dev.mimir.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.mimir.theme.LocalMimirTheme
import java.text.DateFormat
import java.util.Date

/** Full-bleed showcase for the focused game: hero art, logo, glass metadata pills. */
@Composable
fun HeroPane(
    hero: MainViewModel.HeroArt,
    platformName: (String) -> String,
    emulatorName: (dev.mimir.data.GameEntity) -> String,
    modifier: Modifier = Modifier,
) {
    val theme = LocalMimirTheme.current
    Box(modifier.fillMaxWidth().background(theme.scrim)) {
        val art = hero.heroUrl ?: hero.boxartUrl
        if (art != null) {
            AsyncImage(
                model = art,
                contentDescription = hero.game?.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(0.92f),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Transparent, 0.55f to Color.Transparent, 1f to theme.scrim)
            )
        )
        HeroOverlay(
            hero = hero,
            platformName = platformName,
            emulatorName = emulatorName,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Logo (or styled title) + glass metadata pills for the focused game, anchored to the
 * overlay's bottom-start. Shared by [HeroPane] (M5b top display) and the single-screen
 * layered layout in MainActivity, where it floats between the hero art and glass panel.
 */
@Composable
fun HeroOverlay(
    hero: MainViewModel.HeroArt,
    platformName: (String) -> String,
    emulatorName: (dev.mimir.data.GameEntity) -> String,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        val game = hero.game
        if (game == null) {
            Text(
                "Mimir",
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                if (hero.logoUrl != null) {
                    AsyncImage(
                        model = hero.logoUrl,
                        contentDescription = game.title,
                        modifier = Modifier.heightIn(max = 64.dp).widthIn(max = 280.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(
                        game.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassPill(platformName(game.platformId))
                    GlassPill("▶ ${emulatorName(game)}")
                    hero.lastPlayedAt?.let {
                        GlassPill("Last played ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it))}")
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassPill(text: String) {
    Surface(
        color = Color.White.copy(alpha = 0.12f),
        contentColor = Color.White.copy(alpha = 0.92f),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
    }
}
