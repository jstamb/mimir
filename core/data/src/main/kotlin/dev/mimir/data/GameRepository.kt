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

    suspend fun mediaSnapshot(): List<MediaEntity> = dao.mediaOnce()

    suspend fun saveArt(items: List<MediaEntity>) {
        val accepted = items.filter { item ->
            val existing = dao.mediaFor(item.gameUri, item.kind)
            existing == null || ArtPriority.canReplace(existing.source, item.source)
        }
        if (accepted.isNotEmpty()) dao.insertMedia(accepted)
    }

    val playStates: Flow<List<PlayStateEntity>> = dao.playStates()
    suspend fun stampPlayed(uri: String, at: Long) = dao.upsertPlayState(PlayStateEntity(uri, at))
    suspend fun recentGames(limit: Int = 8): List<GameEntity> = dao.recentGames(limit)

    suspend fun applyScan(result: ScanResult) {
        val change = DiffEngine.diff(dao.gamesOnce(), result.games)
        dao.applyChanges(
            toUpsert = change.toUpsert,
            toDeleteUris = change.toDeleteUris,
            skipped = result.skipped.map { SkippedFileEntity(it.relativePath, it.reason) },
        )
    }
}
