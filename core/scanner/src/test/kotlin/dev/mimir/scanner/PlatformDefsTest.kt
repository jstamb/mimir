package dev.mimir.scanner

import kotlinx.serialization.json.Json
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

    @Test
    fun `bundled registry aliases and extensions are lowercase and trimmed`() {
        val rawText = requireNotNull(
            PlatformDefsTest::class.java.getResourceAsStream("/platforms.json"),
        ) { "platforms.json missing from resources" }.bufferedReader().readText()
        val raw = Json.decodeFromString<List<PlatformDef>>(rawText)
        for (platform in raw) {
            for (alias in platform.folderAliases) {
                assertEquals(
                    alias.lowercase().trim(), alias,
                    "folder alias \"$alias\" of ${platform.id} is not lowercase+trimmed",
                )
            }
            for (ext in platform.extensions) {
                assertEquals(
                    ext.lowercase().trim(), ext,
                    "extension \"$ext\" of ${platform.id} is not lowercase+trimmed",
                )
            }
        }
    }

    @Test
    fun `every platform has a libretro thumbnails system name`() {
        val platforms = PlatformDefs.load()
        for (p in platforms) {
            assertTrue(p.libretroName.isNotBlank(), "platform ${p.id} missing libretroName")
            assertTrue(" - " in p.libretroName, "libretroName for ${p.id} should be 'Maker - System' form: ${p.libretroName}")
        }
    }

    @Test
    fun `no folder alias is claimed by two different platforms`() {
        val platforms = PlatformDefs.load()
        val claims = platforms.flatMap { p -> p.folderAliases.map { it to p.id } }
        val duplicates = claims.groupBy({ it.first }, { it.second })
            .filterValues { it.toSet().size > 1 }
        assertTrue(duplicates.isEmpty(), "aliases claimed by multiple platforms: $duplicates")
    }
}
