package app.trouvetout.core.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.sql.DriverManager
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/** A database created by the lot 1 schema opens under the current one with its data intact (additive only). */
class MigrationTest {
    private fun schemaDump(version: Int): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "shared/src/commonMain/sqldelight/databases/$version.db")
            if (candidate.isFile) return candidate
            val local = File(dir, "src/commonMain/sqldelight/databases/$version.db")
            if (local.isFile) return local
            dir = dir.parentFile
        }
        fail("schema dump $version.db not found above ${System.getProperty("user.dir")}")
    }

    private fun readSchema(dump: File): List<String> =
        DriverManager.getConnection("jdbc:sqlite:${dump.absolutePath}").use { connection ->
            connection.createStatement().executeQuery(
                "SELECT sql FROM sqlite_master WHERE sql IS NOT NULL",
            ).use { rows ->
                generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
            }
        }

    @Test
    fun a_v1_database_migrates_to_the_current_schema() {
        val file = File.createTempFile("trouvetout-v1", ".db").apply { deleteOnExit() }
        // Recreate the v1 tables from the published dump, with one row of data.
        val v1Schema = readSchema(schemaDump(1))
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { target ->
            v1Schema.forEach { target.createStatement().execute(it) }
            target.createStatement().execute("INSERT INTO meta(key, value) VALUES ('device_id', 'dev-1')")
            target.createStatement().execute("PRAGMA user_version = 1")
        }
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}", Properties(), TrouveToutDb.Schema)
        val db = openDatabase(driver)
        assertEquals("dev-1", db.metaQueries.get(MetaKeys.DEVICE_ID).executeAsOne(), "existing data kept")
        assertEquals(SCHEMA_VERSION.toString(), db.metaQueries.get(MetaKeys.SCHEMA_VERSION).executeAsOne())
        db.searchUsageQueries.recordOpened("item-1", 5L)
        db.searchUsageQueries.recordOpened("item-1", 9L)
        assertEquals(2L, db.searchUsageQueries.selectAll().executeAsOne().opened_count)
        assertEquals(0L, db.embeddingQueries.countFor("item", "m").executeAsOne())
        driver.close()
    }
}
