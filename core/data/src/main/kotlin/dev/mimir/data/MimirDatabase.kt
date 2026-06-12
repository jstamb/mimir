package dev.mimir.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        GameEntity::class,
        SkippedFileEntity::class,
        MediaEntity::class,
        PlatformPrefEntity::class,
        GamePrefEntity::class,
        CustomPlayerEntity::class,
        PlayStateEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class MimirDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `platform_prefs` " +
                "(`platformId` TEXT NOT NULL, `playerId` TEXT NOT NULL, PRIMARY KEY(`platformId`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `game_prefs` " +
                "(`gameUri` TEXT NOT NULL, `playerId` TEXT NOT NULL, PRIMARY KEY(`gameUri`), " +
                "FOREIGN KEY(`gameUri`) REFERENCES `games`(`uri`) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `custom_players` " +
                "(`id` TEXT NOT NULL, `name` TEXT NOT NULL, `packageName` TEXT NOT NULL, " +
                "`activityClass` TEXT, `action` TEXT NOT NULL, `platformIds` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `play_state` " +
                "(`gameUri` TEXT NOT NULL, `lastPlayedAt` INTEGER NOT NULL, PRIMARY KEY(`gameUri`), " +
                "FOREIGN KEY(`gameUri`) REFERENCES `games`(`uri`) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `media_new` " +
                "(`gameUri` TEXT NOT NULL, `boxartUrl` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL DEFAULT 'boxart', `source` TEXT NOT NULL DEFAULT 'libretro', " +
                "PRIMARY KEY(`gameUri`, `kind`), " +
                "FOREIGN KEY(`gameUri`) REFERENCES `games`(`uri`) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL("INSERT INTO media_new (gameUri, boxartUrl) SELECT gameUri, boxartUrl FROM media")
        db.execSQL("DROP TABLE media")
        db.execSQL("ALTER TABLE media_new RENAME TO media")
    }
}
