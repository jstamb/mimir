package dev.mimir.theme

import kotlinx.serialization.Serializable

/** A pack defines any subset of slots; applying writes only what it defines. */
@Serializable
data class ThemePack(
    val id: String,
    val name: String,
    val paletteMode: PaletteMode? = null,
    val fixedSeedArgb: Int? = null,
    val wallpaperTopUri: String? = null,
    val wallpaperBottomUri: String? = null,
    val soundPackUri: String? = null,
    val haptics: HapticLevel? = null,
    val tiles: TileStyle? = null,
)

fun ThemeConfig.applying(pack: ThemePack): ThemeConfig {
    var prov = provenance
    fun mark(slot: String) { prov = prov + (slot to pack.id) }
    var c = this
    pack.paletteMode?.let { c = c.copy(paletteMode = it); mark("paletteMode") }
    pack.fixedSeedArgb?.let { c = c.copy(fixedSeedArgb = it); mark("fixedSeedArgb") }
    pack.wallpaperTopUri?.let { c = c.copy(wallpaperTopUri = it); mark("wallpaperTopUri") }
    pack.wallpaperBottomUri?.let { c = c.copy(wallpaperBottomUri = it); mark("wallpaperBottomUri") }
    pack.soundPackUri?.let { c = c.copy(soundPackUri = it); mark("soundPackUri") }
    pack.haptics?.let { c = c.copy(haptics = it); mark("haptics") }
    pack.tiles?.let { c = c.copy(tiles = it); mark("tiles") }
    return c.copy(provenance = prov)
}
