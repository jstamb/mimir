package dev.mimir.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM games ORDER BY title")
    fun games(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games")
    suspend fun gamesOnce(): List<GameEntity>

    @Upsert
    suspend fun upsertGames(games: List<GameEntity>)

    @Query("DELETE FROM games WHERE uri IN (:uris)")
    suspend fun deleteGames(uris: List<String>)

    @Query("SELECT * FROM skipped_files ORDER BY relativePath")
    fun skippedFiles(): Flow<List<SkippedFileEntity>>

    @Query("DELETE FROM skipped_files")
    suspend fun clearSkipped()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSkipped(items: List<SkippedFileEntity>)

    @Query("SELECT * FROM media")
    fun media(): Flow<List<MediaEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMedia(items: List<MediaEntity>)

    @Query("SELECT g.* FROM games g LEFT JOIN media m ON g.uri = m.gameUri WHERE m.gameUri IS NULL")
    suspend fun gamesWithoutArt(): List<GameEntity>

    @Query("SELECT * FROM platform_prefs")
    fun platformPrefs(): Flow<List<PlatformPrefEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setPlatformPref(pref: PlatformPrefEntity)

    @Query("SELECT * FROM game_prefs")
    fun gamePrefs(): Flow<List<GamePrefEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setGamePref(pref: GamePrefEntity)

    @Query("DELETE FROM game_prefs WHERE gameUri = :gameUri")
    suspend fun clearGamePref(gameUri: String)

    @Query("SELECT * FROM custom_players")
    fun customPlayers(): Flow<List<CustomPlayerEntity>>

    @Upsert
    suspend fun upsertCustomPlayer(player: CustomPlayerEntity)

    @Query("DELETE FROM custom_players WHERE id = :id")
    suspend fun deleteCustomPlayer(id: String)

    @Transaction
    suspend fun applyChanges(
        toUpsert: List<GameEntity>,
        toDeleteUris: List<String>,
        skipped: List<SkippedFileEntity>,
    ) {
        if (toUpsert.isNotEmpty()) upsertGames(toUpsert)
        if (toDeleteUris.isNotEmpty()) deleteGames(toDeleteUris)
        clearSkipped()
        if (skipped.isNotEmpty()) insertSkipped(skipped)
    }
}
