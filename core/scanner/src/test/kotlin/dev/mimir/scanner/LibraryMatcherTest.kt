package dev.mimir.scanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryMatcherTest {
    private val matcher = LibraryMatcher(PlatformDefs.load())

    private fun file(path: String) = ScannedFile(
        relativePath = path,
        uri = "content://test/${path.replace('/', '_')}",
    )

    @Test
    fun `matches by folder alias case-insensitively`() {
        val result = matcher.match(listOf(file("GameCube/Wind Waker.rvz")))
        assertEquals("gc", result.games.single().platformId)
        assertEquals("Wind Waker", result.games.single().title)
    }

    @Test
    fun `matches lowercase short alias`() {
        val result = matcher.match(listOf(file("gc/Wind Waker.rvz")))
        assertEquals("gc", result.games.single().platformId)
    }

    @Test
    fun `deepest folder alias wins`() {
        val result = matcher.match(listOf(file("roms/Nintendo 64/Mario Kart 64.z64")))
        assertEquals("n64", result.games.single().platformId)
    }

    @Test
    fun `extension-unique fallback matches without folder hint`() {
        val result = matcher.match(listOf(file("stuff/Pokemon Emerald.gba")))
        assertEquals("gba", result.games.single().platformId)
    }

    @Test
    fun `unknown extension without folder hint is skipped with reason`() {
        val result = matcher.match(listOf(file("misc/readme.txt")))
        assertTrue(result.games.isEmpty())
        val skipped = result.skipped.single()
        assertEquals("misc/readme.txt", skipped.relativePath)
        assertTrue("txt" in skipped.reason)
    }

    @Test
    fun `alias folder with wrong extension is skipped and reason names the platform`() {
        val result = matcher.match(listOf(file("n64/Mario.gba")))
        assertTrue(result.games.isEmpty())
        assertTrue("Nintendo 64" in result.skipped.single().reason)
    }
}
