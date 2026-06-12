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
    primaryKeys = ["gameUri", "kind"],
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
    val gameUri: String,
    val boxartUrl: String,   // image uri for this kind (name kept for compatibility; semantically "url")
    val kind: String = "boxart",     // boxart | hero | logo
    val source: String = "libretro", // folder | esde | sgdb | libretro
)

@Entity(
    tableName = "play_state",
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["uri"],
            childColumns = ["gameUri"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class PlayStateEntity(
    @PrimaryKey val gameUri: String,
    val lastPlayedAt: Long,
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

@Entity(tableName = "custom_players")
data class CustomPlayerEntity(
    @PrimaryKey val id: String,            // "custom-" + packageName
    val name: String,
    val packageName: String,
    val activityClass: String?,
    val action: String,
    val platformIds: String,               // comma-joined platform ids
)

fun CustomPlayerEntity.toPlayerDef() = dev.mimir.launcher.PlayerDef(
    id = id,
    name = name,
    packageName = packageName,
    activityClass = activityClass,
    action = action,
    platformIds = platformIds.split(',').filter { it.isNotBlank() },
)
