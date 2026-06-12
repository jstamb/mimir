package dev.mimir.theme

data class AmbientPalette(
    val primaryArgb: Int,
    val glowArgb: Int,    // primary at ~25% alpha for washes
    val scrimArgb: Int,   // near-black tinted toward primary
) {
    companion object {
        const val FALLBACK_ARGB = 0xFF26354C.toInt() // Mimir slate-blue

        fun choosePrimary(vibrant: Int?, muted: Int?, dominant: Int?): Int =
            vibrant ?: muted ?: dominant ?: FALLBACK_ARGB

        fun from(primaryArgb: Int): AmbientPalette = AmbientPalette(
            primaryArgb = primaryArgb,
            glowArgb = (primaryArgb and 0x00FFFFFF) or 0x40000000,
            scrimArgb = blend(primaryArgb, 0xFF0B0B10.toInt(), 0.85f),
        )

        private fun blend(a: Int, b: Int, towardB: Float): Int {
            fun ch(shift: Int) =
                (((a shr shift and 0xFF) * (1 - towardB)) + ((b shr shift and 0xFF) * towardB)).toInt() and 0xFF
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
