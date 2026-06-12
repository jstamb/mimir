package dev.mimir.theme

import kotlin.test.Test
import kotlin.test.assertEquals

class AmbientPaletteTest {
    @Test
    fun `chooses vibrant then muted then dominant then fallback`() {
        assertEquals(1, AmbientPalette.choosePrimary(vibrant = 1, muted = 2, dominant = 3))
        assertEquals(2, AmbientPalette.choosePrimary(vibrant = null, muted = 2, dominant = 3))
        assertEquals(3, AmbientPalette.choosePrimary(vibrant = null, muted = null, dominant = 3))
        assertEquals(AmbientPalette.FALLBACK_ARGB, AmbientPalette.choosePrimary(null, null, null))
    }

    @Test
    fun `derives glow and scrim from primary deterministically`() {
        val p = AmbientPalette.from(primaryArgb = 0xFF3B8FE2.toInt())
        assertEquals(0xFF3B8FE2.toInt(), p.primaryArgb)
        assertEquals(AmbientPalette.from(0xFF3B8FE2.toInt()), p) // pure/deterministic
    }
}
