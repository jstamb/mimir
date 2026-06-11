package dev.mimir.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [GameEntity::class, SkippedFileEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class MimirDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
}
