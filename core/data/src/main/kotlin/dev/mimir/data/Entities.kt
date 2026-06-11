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

@Entity(tableName = "platform_prefs")
data class PlatformPrefEntity(
    @PrimaryKey val platformId: String,
    val playerId: String,
)

@Entity(
    tableName = "game_prefs",
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["uri"],
            childColumns = ["gameUri"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class GamePrefEntity(
    @PrimaryKey val gameUri: String,
    val playerId: String,
)
