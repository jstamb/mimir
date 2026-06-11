package dev.mimir.data

import androidx.room.Entity
import androidx.room.ForeignKey
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

@Entity(
    tableName = "media",
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["uri"],
            childColumns = ["gameUri"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class MediaEntity(
    @PrimaryKey val gameUri: String,
    val boxartUrl: String,
)
