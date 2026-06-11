package dev.mimir.scanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** In-memory TreeSource: map of dirUri -> children. */
private class FakeTree(private val tree: Map<String, List<TreeSource.Node>>) : TreeSource {
    override fun children(dirUri: String): List<TreeSource.Node> =
        tree[dirUri] ?: throw TreeAccessException("no such dir: $dirUri")
}

private fun dir(name: String, uri: String) = TreeSource.Node(name, uri, isDirectory = true, lastModified = 0)
private fun file(name: String, uri: String, modified: Long = 7) =
    TreeSource.Node(name, uri, isDirectory = false, lastModified = modified)

class WalkEngineTest {
    @Test
    fun `walks nested directories building relative paths`() {
        val source = FakeTree(
            mapOf(
                "root" to listOf(dir("n64", "d1"), file("notes.txt", "f0")),
                "d1" to listOf(file("Mario Kart 64.z64", "f1", modified = 42), dir("hacks", "d2")),
                "d2" to listOf(file("Kaizo.z64", "f2")),
            )
        )
        val files = WalkEngine.walk(source, "root").sortedBy { it.relativePath }
        assertEquals(
            listOf("n64/Mario Kart 64.z64", "n64/hacks/Kaizo.z64", "notes.txt").sorted(),
            files.map { it.relativePath },
        )
        val mario = files.first { it.uri == "f1" }
        assertEquals(42, mario.lastModified)
    }

    @Test
    fun `empty root yields empty list`() {
        assertEquals(emptyList(), WalkEngine.walk(FakeTree(mapOf("root" to emptyList())), "root"))
    }

    @Test
    fun `access failure propagates as TreeAccessException`() {
        assertFailsWith<TreeAccessException> { WalkEngine.walk(FakeTree(emptyMap()), "root") }
    }
}
