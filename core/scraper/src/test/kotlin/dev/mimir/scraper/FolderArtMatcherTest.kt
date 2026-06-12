package dev.mimir.scraper

import dev.mimir.scanner.Game
import dev.mimir.scanner.ScannedFile
import kotlin.test.Test
import kotlin.test.assertEquals

class FolderArtMatcherTest {
    private val games = listOf(
        Game("Mario Kart 64", "uri-mk", "n64", "n64/Mario Kart 64.z64", 1),
        Game("GoldenEye", "uri-ge", "n64", "n64/GoldenEye.z64", 1),
        Game("Wind Waker", "uri-ww", "gc", "gc/Wind Waker.rvz", 1),
    )
    private fun f(path: String, uri: String = "content://$path") = ScannedFile(path, uri)

    @Test
    fun `sibling image matches by exact title`() {
        val art = FolderArtMatcher.match(listOf(f("n64/Mario Kart 64.png")), games)
        assertEquals(mapOf("uri-mk" to "content://n64/Mario Kart 64.png"), art)
    }

    @Test
    fun `art subdir matches normalized and case-insensitively`() {
        val art = FolderArtMatcher.match(listOf(f("gc/Covers/wind waker.JPG")), games)
        assertEquals(mapOf("uri-ww" to "content://gc/Covers/wind waker.JPG"), art)
    }

    @Test
    fun `sibling beats subdir and non-images plus wrong-dir images are ignored`() {
        val art = FolderArtMatcher.match(
            listOf(
                f("n64/boxart/GoldenEye.png"),
                f("n64/GoldenEye.jpg"),
                f("n64/GoldenEye.txt"),
                f("gc/GoldenEye.png"), // wrong directory for an n64 game
            ),
            games,
        )
        assertEquals(mapOf("uri-ge" to "content://n64/GoldenEye.jpg"), art)
    }
}
