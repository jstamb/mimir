package dev.mimir.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [GameEntity::class, SkippedFileEntity::class, MediaEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class MimirDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
}
