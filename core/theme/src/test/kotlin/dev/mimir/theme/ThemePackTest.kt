package dev.mimir.theme

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ThemePackTest {
    @Test
    fun `default config is ambient with sane slots`() {
        val c = ThemeConfig()
        assertEquals(PaletteMode.AMBIENT, c.paletteMode)
        assertEquals(TileShape.ROUNDED, c.tiles.shape)
        assertEquals(HapticLevel.SUBTLE, c.haptics)
    }

    @Test
    fun `applying a pack overrides only the slots it defines and records provenance`() {
        val pack = Json.decodeFromString<ThemePack>(
            """{"id":"crt-pack","name":"CRT","tiles":{"shape":"sharp","density":"compact","glow":true}}"""
        )
        val merged = ThemeConfig().applying(pack)
        assertEquals(TileShape.SHARP, merged.tiles.shape)
        assertEquals(PaletteMode.AMBIENT, merged.paletteMode)      // untouched slot
        assertEquals("crt-pack", merged.provenance["tiles"])       // provenance recorded
        assertEquals(null, merged.provenance["paletteMode"])
    }

    @Test
    fun `mixing two packs keeps per-slot provenance`() {
        val tilesPack = Json.decodeFromString<ThemePack>("""{"id":"a","name":"A","tiles":{"shape":"pill","density":"cozy","glow":false}}""")
        val soundPack = Json.decodeFromString<ThemePack>("""{"id":"b","name":"B","soundPackUri":"content://packs/b"}""")
        val merged = ThemeConfig().applying(tilesPack).applying(soundPack)
        assertEquals(TileShape.PILL, merged.tiles.shape)
        assertEquals("content://packs/b", merged.soundPackUri)
        assertEquals("a", merged.provenance["tiles"])
        assertEquals("b", merged.provenance["soundPackUri"])
    }

    @Test
    fun `config round-trips through json`() {
        val c = ThemeConfig(paletteMode = PaletteMode.FIXED, fixedSeedArgb = 0xFF3B8FE2.toInt())
        assertEquals(c, Json.decodeFromString<ThemeConfig>(Json.encodeToString(ThemeConfig.serializer(), c)))
    }
}
