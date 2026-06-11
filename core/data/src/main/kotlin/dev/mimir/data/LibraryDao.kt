package dev.mimir.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
}
