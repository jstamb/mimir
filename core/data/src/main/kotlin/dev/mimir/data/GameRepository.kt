package dev.mimir.data

import dev.mimir.scanner.ScanResult
import kotlinx.coroutines.flow.Flow

class GameRepository(private val dao: LibraryDao) {
    val games: Flow<List<GameEntity>> = dao.games()
    val skipped: Flow<List<SkippedFileEntity>> = dao.skippedFiles()
    val media: Flow<List<MediaEntity>> = dao.media()
    val platformPrefs: Flow<List<PlatformPrefEntity>> = dao.platformPrefs()
    val gamePrefs: Flow<List<GamePrefEntity>> = dao.gamePrefs()
    val customPlayers: Flow<List<CustomPlayerEntity>> = dao.customPlayers()

    suspend fun saveCustomPlayer(player: CustomPlayerEntity) = dao.upsertCustomPlayer(player)
    suspend fun deleteCustomPlayer(id: String) = dao.deleteCustomPlayer(id)

    suspend fun setPlatformDefault(platformId: String, playerId: String) =
        dao.setPlatformPref(PlatformPrefEntity(platformId, playerId))

    suspend fun setGameOverride(gameUri: String, playerId: String) =
        dao.setGamePref(GamePrefEntity(gameUri, playerId))

    suspend fun clearGameOverride(gameUri: String) = dao.clearGamePref(gameUri)

    suspend fun gamesWithoutArt(): List<GameEntity> = dao.gamesWithoutArt()

    suspend fun saveArt(items: List<MediaEntity>) {
        if (items.isNotEmpty()) dao.insertMedia(items)
    }

    suspend fun applyScan(result: ScanResult) {
        val change = DiffEngine.diff(dao.gamesOnce(), result.games)
        dao.applyChanges(
            toUpsert = change.toUpsert,
            toDeleteUris = change.toDeleteUris,
            skipped = result.skipped.map { SkippedFileEntity(it.relativePath, it.reason) },
        )
    }
}
