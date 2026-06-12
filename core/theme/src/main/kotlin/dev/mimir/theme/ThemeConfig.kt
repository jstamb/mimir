package dev.mimir.theme

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable enum class PaletteMode { @SerialName("ambient") AMBIENT, @SerialName("fixed") FIXED }
@Serializable enum class TileShape { @SerialName("rounded") ROUNDED, @SerialName("sharp") SHARP, @SerialName("pill") PILL }
@Serializable enum class TileDensity { @SerialName("cozy") COZY, @SerialName("compact") COMPACT }
@Serializable enum class HapticLevel { @SerialName("off") OFF, @SerialName("subtle") SUBTLE, @SerialName("strong") STRONG }

@Serializable
data class TileStyle(
    val shape: TileShape = TileShape.ROUNDED,
    val density: TileDensity = TileDensity.COZY,
    val glow: Boolean = false,
)

@Serializable
data class ThemeConfig(
    val paletteMode: PaletteMode = PaletteMode.AMBIENT,
    val fixedSeedArgb: Int = 0xFF26354C.toInt(),         // Mimir slate-blue
    val wallpaperTopUri: String? = null,
    val wallpaperBottomUri: String? = null,
    val soundPackUri: String? = null,                     // null = bundled default
    val soundVolume: Float = 0.6f,
    val haptics: HapticLevel = HapticLevel.SUBTLE,
    val tiles: TileStyle = TileStyle(),
    /** slot name -> pack id that last set it (theme mixing provenance) */
    val provenance: Map<String, String> = emptyMap(),
)
