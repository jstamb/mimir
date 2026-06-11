package dev.mimir.data

import dev.mimir.scanner.Game
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiffEngineTest {
    private val storedMario = GameEntity("uri-m", "Mario Kart 64", "n64", "n64/Mario Kart 64.z64", 10)

    private fun scannedMario(modified: Long = 10) =
        Game("Mario Kart 64", "uri-m", "n64", "n64/Mario Kart 64.z64", modified)

    @Test
    fun `unchanged game produces empty changeset`() {
        val change = DiffEngine.diff(listOf(storedMario), listOf(scannedMario()))
        assertTrue(change.toUpsert.isEmpty())
        assertTrue(change.toDeleteUris.isEmpty())
    }

    @Test
    fun `new game is upserted`() {
        val new = Game("GoldenEye", "uri-g", "n64", "n64/GoldenEye.z64", 5)
        val change = DiffEngine.diff(listOf(storedMario), listOf(scannedMario(), new))
        assertEquals(listOf("uri-g"), change.toUpsert.map { it.uri })
        assertTrue(change.toDeleteUris.isEmpty())
    }

    @Test
    fun `modified lastModified is upserted`() {
        val change = DiffEngine.diff(listOf(storedMario), listOf(scannedMario(modified = 99)))
        assertEquals(listOf(99L), change.toUpsert.map { it.lastModified })
    }

    @Test
    fun `missing game is deleted`() {
        val change = DiffEngine.diff(listOf(storedMario), emptyList())
        assertEquals(listOf("uri-m"), change.toDeleteUris)
        assertTrue(change.toUpsert.isEmpty())
    }
}
