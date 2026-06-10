package dev.mimir.scanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformDefsTest {
    @Test
    fun `bundled platforms load and contain n64 with aliases and extensions`() {
        val platforms = PlatformDefs.load()
        assertTrue(platforms.size >= 5, "expected at least 5 bundled platforms")
        val n64 = platforms.first { it.id == "n64" }
        assertEquals("Nintendo 64", n64.name)
        assertTrue("n64" in n64.folderAliases)
        assertTrue("nintendo64" in n64.folderAliases)
        assertTrue("z64" in n64.extensions)
    }

    @Test
    fun `ids are unique`() {
        val platforms = PlatformDefs.load()
        assertEquals(platforms.size, platforms.map { it.id }.toSet().size)
    }
}
