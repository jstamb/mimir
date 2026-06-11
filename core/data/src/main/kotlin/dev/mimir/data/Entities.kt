package dev.mimir.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "games")
data class GameEntity(
    @PrimaryKey val uri: String,
    val title: String,
    val platformId: String,
    val relativePath: String,
    val lastModified: Long,
)

@Entity(tableName = "skipped_files")
data class SkippedFileEntity(
    @PrimaryKey val relativePath: String,
    val reason: String,
)
