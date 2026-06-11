package dev.mimir.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.mimir.scanner.Game
import dev.mimir.scanner.ScanResult
import dev.mimir.scanner.SkippedFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class LibraryDaoTest {
    private fun db(): MimirDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        MimirDatabase::class.java,
    ).allowMainThreadQueries().build()

    @Test
    fun `games round trip with upsert delete and ordering`() = runBlocking {
        val dao = db().libraryDao()
        dao.upsertGames(
            listOf(
                GameEntity("uri-b", "Banjo", "n64", "n64/Banjo.z64", 1),
                GameEntity("uri-a", "Aero", "n64", "n64/Aero.z64", 1),
            )
        )
        assertEquals(listOf("Aero", "Banjo"), dao.games().first().map { it.title })

        dao.upsertGames(listOf(GameEntity("uri-b", "Banjo-Kazooie", "n64", "n64/Banjo.z64", 2)))
        assertEquals("Banjo-Kazooie", dao.games().first().first { it.uri == "uri-b" }.title)

        dao.deleteGames(listOf("uri-a"))
        assertEquals(listOf("uri-b"), dao.gamesOnce().map { it.uri })
    }

    @Test
    fun `skipped files replace wholesale`() = runBlocking {
        val dao = db().libraryDao()
        dao.insertSkipped(listOf(SkippedFileEntity("a.txt", "reason one")))
        dao.clearSkipped()
        dao.insertSkipped(listOf(SkippedFileEntity("b.txt", "reason two")))
        assertEquals(listOf("b.txt"), dao.skippedFiles().first().map { it.relativePath })
    }

    @Test
    fun `applyScan diffs into the database idempotently`() = runBlocking {
        val dao = db().libraryDao()
        val repo = GameRepository(dao)
        val scan = ScanResult(
            games = listOf(Game("Mario Kart 64", "uri-m", "n64", "n64/Mario Kart 64.z64", 10)),
            skipped = listOf(SkippedFile("notes.txt", "no platform registered for extension .txt and no platform folder in path")),
        )
        repo.applyScan(scan)
        repo.applyScan(scan) // idempotent
        assertEquals(1, dao.gamesOnce().size)
        assertEquals(1, dao.skippedFiles().first().size)

        repo.applyScan(ScanResult(games = emptyList(), skipped = emptyList()))
        assertEquals(0, dao.gamesOnce().size)
        assertEquals(0, dao.skippedFiles().first().size)
    }
}
