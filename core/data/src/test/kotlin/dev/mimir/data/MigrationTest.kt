package dev.mimir.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MimirDatabase::class.java,
    )

    @Test
    fun `migrate 2 to 3 preserves games and media`() {
        helper.createDatabase("migration-test", 2).apply {
            execSQL(
                "INSERT INTO games (uri, title, platformId, relativePath, lastModified) " +
                    "VALUES ('uri-m', 'Mario Kart 64', 'n64', 'n64/Mario Kart 64.z64', 10)"
            )
            execSQL("INSERT INTO media (gameUri, boxartUrl) VALUES ('uri-m', 'https://example.test/mk64.png')")
            close()
        }
        val db = helper.runMigrationsAndValidate("migration-test", 3, true, MIGRATION_2_3)
        db.query("SELECT COUNT(*) FROM games").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.query("SELECT COUNT(*) FROM media").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.query("SELECT COUNT(*) FROM platform_prefs").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        db.query("SELECT COUNT(*) FROM game_prefs").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
    }
}
