package app.trouvetout.android.platform

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.db.QueryResult
import app.trouvetout.core.data.db.AndroidDriverFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** The framework SQLite driver on a real Android 10 (lot 1 review): file placement and journal settings. */
@RunWith(AndroidJUnit4::class)
class AndroidDriverFactoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun opens_under_no_backup_with_wal_and_full_sync() {
        val file = File(context.noBackupFilesDir, "db/test-${UUID.randomUUID()}.db")
        val driver = AndroidDriverFactory(context, file.absolutePath).create()
        try {
            assertEquals("wal", pragma(driver, "journal_mode"))
            // 2 = FULL
            assertEquals("2", pragma(driver, "synchronous"))
            assertTrue("database file created where asked", file.isFile)
            assertTrue("under no_backup", file.absolutePath.startsWith(context.noBackupFilesDir.absolutePath))
            assertTrue("never in the backed-up databases directory", context.databaseList().none { it == file.name })
        } finally {
            driver.close()
            file.parentFile?.listFiles()?.filter { it.name.startsWith(file.name) }?.forEach { it.delete() }
        }
    }

    private fun pragma(
        driver: app.cash.sqldelight.db.SqlDriver,
        name: String,
    ): String? = driver
        .executeQuery(
            identifier = null,
            sql = "PRAGMA $name",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getString(0))
            },
            parameters = 0,
        ).value
}
