package dev.mimir.data

import dev.mimir.scanner.ScanResult
import kotlinx.coroutines.flow.Flow

class GameRepository(private val dao: LibraryDao) {
    val games: Flow<List<GameEntity>> = dao.games()
    val skipped: Flow<List<SkippedFileEntity>> = dao.skippedFiles()

    suspend fun applyScan(result: ScanResult) {
        val change = DiffEngine.diff(dao.gamesOnce(), result.games)
        dao.applyChanges(
            toUpsert = change.toUpsert,
            toDeleteUris = change.toDeleteUris,
            skipped = result.skipped.map { SkippedFileEntity(it.relativePath, it.reason) },
        )
    }
}
