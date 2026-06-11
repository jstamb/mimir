package dev.mimir.scraper

import dev.mimir.scanner.Game
import dev.mimir.scanner.PlatformDef
import dev.mimir.scanner.ScannedFile
import kotlin.test.Test
import kotlin.test.assertEquals

class EsdeImportMatcherTest {
    private val platforms = listOf(
        PlatformDef("n64", "Nintendo 64", listOf("n64", "nintendo64"), listOf("z64"), "Nintendo - Nintendo 64"),
        PlatformDef("gc", "GameCube", listOf("gc", "gamecube"), listOf("rvz"), "Nintendo - GameCube"),
    )
    private val games = listOf(
        Game("Mario Kart 64", "uri-mk", "n64", "n64/Mario Kart 64.z64", 1),
        Game("GoldenEye", "uri-ge", "n64", "n64/GoldenEye.z64", 1),
        Game("Wind Waker", "uri-ww", "gc", "gc/Wind Waker.rvz", 1),
    )

    private fun media(path: String, uri: String) = ScannedFile(relativePath = path, uri = uri)

    @Test
    fun `matches covers by exact title within the aliased system dir`() {
        val result = EsdeImportMatcher.match(
            mediaFiles = listOf(
                media("n64/covers/Mario Kart 64.png", "content://esde/mk64"),
                media("gamecube/covers/Wind Waker.jpg", "content://esde/ww"),
            ),
            games = games,
            platforms = platforms,
        )
        assertEquals(
            mapOf("uri-mk" to "content://esde/mk64", "uri-ww" to "content://esde/ww"),
            result.covers,
        )
    }

    @Test
    fun `normalized fallback matches punctuation differences`() {
        val result = EsdeImportMatcher.match(
            mediaFiles = listOf(media("n64/covers/goldeneye.png", "content://esde/ge")),
            games = games,
            platforms = platforms,
        )
        assertEquals(mapOf("uri-ge" to "content://esde/ge"), result.covers)
    }

    @Test
    fun `counts videos without importing and ignores other media types`() {
        val result = EsdeImportMatcher.match(
            mediaFiles = listOf(
                media("n64/videos/Mario Kart 64.mp4", "content://esde/v1"),
                media("n64/screenshots/Mario Kart 64.png", "content://esde/s1"),
                media("n64/covers/Mario Kart 64.png", "content://esde/c1"),
            ),
            games = games,
            platforms = platforms,
        )
        assertEquals(1, result.covers.size)
        assertEquals(1, result.videoCount)
    }

    @Test
    fun `unknown system dirs and unmatched titles are skipped silently`() {
        val result = EsdeImportMatcher.match(
            mediaFiles = listOf(
                media("atari2600/covers/Pitfall.png", "content://esde/p"),
                media("n64/covers/Banjo-Tooie.png", "content://esde/b"),
            ),
            games = games,
            platforms = platforms,
        )
        assertEquals(emptyMap(), result.covers)
        assertEquals(0, result.videoCount)
    }
}
