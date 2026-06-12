package dev.mimir.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class MimirThemeValues(
    val config: ThemeConfig,
    val ambient: AmbientPalette,
    val primary: Color,
    val glow: Color,
    val scrim: Color,
)

val LocalMimirTheme = staticCompositionLocalOf {
    MimirThemeValues(
        config = ThemeConfig(),
        ambient = AmbientPalette.from(AmbientPalette.FALLBACK_ARGB),
        primary = Color(AmbientPalette.FALLBACK_ARGB),
        glow = Color(AmbientPalette.from(AmbientPalette.FALLBACK_ARGB).glowArgb),
        scrim = Color(AmbientPalette.from(AmbientPalette.FALLBACK_ARGB).scrimArgb),
    )
}

@Composable
fun MimirTheme(config: ThemeConfig, ambient: AmbientPalette, content: @Composable () -> Unit) {
    val animPrimary by animateColorAsState(Color(ambient.primaryArgb), tween(400), label = "ambientPrimary")
    val animGlow by animateColorAsState(Color(ambient.glowArgb), tween(400), label = "ambientGlow")
    val animScrim by animateColorAsState(Color(ambient.scrimArgb), tween(400), label = "ambientScrim")
    CompositionLocalProvider(
        LocalMimirTheme provides MimirThemeValues(config, ambient, animPrimary, animGlow, animScrim),
        content = content,
    )
}
