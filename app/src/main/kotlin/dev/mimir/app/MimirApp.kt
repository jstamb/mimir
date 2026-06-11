package dev.mimir.app

import android.app.Application
import androidx.room.Room
import dev.mimir.data.MIGRATION_2_3
import dev.mimir.data.MIGRATION_3_4
import dev.mimir.data.MimirDatabase

class MimirApp : Application() {
    val db: MimirDatabase by lazy {
        Room.databaseBuilder(this, MimirDatabase::class.java, "mimir.db")
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
            .fallbackToDestructiveMigration(dropAllTables = true) // pre-release safety net for v1 installs only
            .build()
    }
}
