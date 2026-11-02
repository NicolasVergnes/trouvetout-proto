package app.trouvetout.android.platform

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.trouvetout.core.data.db.AndroidDatabaseKeys
import app.trouvetout.core.data.db.AndroidDriverFactory
import app.trouvetout.core.data.db.DatabaseKeyLostException
import app.trouvetout.core.data.db.TrouveToutDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * The SQLCipher driver on a real Android 10 (H2, ADR-023): file placement, journal settings, encryption, the
 * migration of a plain database written by lots 1 to 7, and the key's deletion by the total wipe.
 */
@RunWith(AndroidJUnit4::class)
class AndroidDriverFactoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val run = UUID.randomUUID().toString()
    private val directory = File(context.noBackupFilesDir, "test-$run")
    private val file = File(directory, "db/test.db")
    private val keys = AndroidDatabaseKeys(context, File(directory, "keys"), alias = "test-$run")

    @After
    fun cleanUp() {
        keys.delete()
        directory.deleteRecursively()
    }

    @Test
    fun opens_encrypted_under_no_backup_with_wal_and_full_sync() {
        val driver = AndroidDriverFactory(context, file.absolutePath, keys).create()
        try {
            TrouveToutDb(driver).metaQueries.set("probe", "value")
            assertEquals("wal", pragma(driver, "journal_mode"))
            // 2 = FULL
            assertEquals("2", pragma(driver, "synchronous"))
            assertTrue("SQLCipher, not the system SQLite", !pragma(driver, "cipher_version").isNullOrBlank())
            assertTrue("database file created where asked", file.isFile)
            assertTrue("under no_backup", file.absolutePath.startsWith(context.noBackupFilesDir.absolutePath))
            assertTrue("never in the backed-up databases directory", context.databaseList().none { it == file.name })
        } finally {
            driver.close()
        }
        assertFalse("no plain SQLite header", isPlain(file))
        assertFalse("no readable text in the file", String(file.readBytes(), Charsets.ISO_8859_1).contains("probe"))
    }

    @Test
    fun a_plain_database_of_10_000_items_is_encrypted_once_without_loss() {
        val count = 10_000L
        writePlain(count)
        assertTrue(isPlain(file))

        val started = System.nanoTime()
        val driver = AndroidDriverFactory(context, file.absolutePath, keys).create()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        try {
            val db = TrouveToutDb(driver)
            assertEquals(count, db.eventQueries.count().executeAsOne())
            assertEquals(count, db.itemQueries.countActive().executeAsOne())
            assertEquals("objet 9999", db.itemQueries.selectById("i9999").executeAsOne().name)
        } finally {
            driver.close()
        }
        // Once per install, at the first launch of lot 8 (budget: the 1.5 s cold start, with margin on an emulator).
        assertTrue("migration took $elapsedMs ms", elapsedMs < MIGRATION_BUDGET_MS)
        assertFalse(isPlain(file))
        assertFalse("the copy was renamed over the plain file", File(file.path + ".enc").exists())

        val reopened = AndroidDriverFactory(context, file.absolutePath, keys).create()
        try {
            assertEquals(count, TrouveToutDb(reopened).eventQueries.count().executeAsOne())
        } finally {
            reopened.close()
        }
    }

    /** A plain database as lots 1 to 7 left it: the schema of the current version, through the system SQLite. */
    private fun writePlain(count: Long) {
        file.parentFile!!.mkdirs()
        val plain = AndroidSqliteDriver(TrouveToutDb.Schema, context, file.absolutePath)
        try {
            val db = TrouveToutDb(plain)
            db.transaction {
                for (i in 0 until count) {
                    db.eventQueries.insert("e$i", i, "device", "me", "ItemCreated", "{}", "TEXT", null)
                    db.itemQueries.insert("i$i", "objet $i", null, "[]", null, null, false, false, i)
                }
            }
        } finally {
            plain.close()
        }
    }

    @Test
    fun a_plain_database_with_commits_still_in_its_wal_loses_nothing() {
        file.parentFile!!.mkdirs()
        val plain = AndroidSqliteDriver(TrouveToutDb.Schema, context, file.absolutePath)
        val copy = File(directory, "crashed/test.db")
        try {
            assertEquals("wal", pragma(plain, "journal_mode = WAL"))
            pragma(plain, "wal_autocheckpoint = 0")
            val db = TrouveToutDb(plain)
            db.transaction {
                for (i in 0 until 500L) {
                    db.eventQueries.insert(
                        "w$i",
                        i,
                        "device",
                        "me",
                        "ItemCreated",
                        "{}",
                        "TEXT",
                        null,
                    )
                }
            }
            // The files as a process killed now would leave them: the commits are only in the journal.
            copy.parentFile!!.mkdirs()
            for (suffix in listOf("", "-wal", "-shm")) {
                File(file.path + suffix).takeIf { it.exists() }?.copyTo(File(copy.path + suffix), overwrite = true)
            }
        } finally {
            plain.close()
        }
        assertTrue("the journal holds the commits", File(copy.path + "-wal").length() > 0)

        val driver = AndroidDriverFactory(context, copy.absolutePath, keys).create()
        try {
            assertEquals(500L, TrouveToutDb(driver).eventQueries.count().executeAsOne())
        } finally {
            driver.close()
        }
        assertFalse(isPlain(copy))
    }

    @Test
    fun without_its_key_the_encrypted_database_is_not_replaced() {
        AndroidDriverFactory(context, file.absolutePath, keys).create().close()
        val before = file.readBytes()
        keys.delete()
        assertFalse(keys.exists())
        try {
            AndroidDriverFactory(context, file.absolutePath, keys).create().close()
            fail("opened without its key")
        } catch (_: DatabaseKeyLostException) {
            // expected
        }
        assertFalse("no key created to hide the loss", keys.exists())
        assertNotEquals(0, before.size)
        assertTrue("the file is left as it was", before.contentEquals(file.readBytes()))
    }

    private companion object {
        const val MIGRATION_BUDGET_MS = 15_000L
    }

    private fun isPlain(file: File): Boolean = file.inputStream().use { input -> ByteArray(16).also { input.read(it) } }
        .contentEquals("SQLite format 3\u0000".toByteArray())

    private fun pragma(
        driver: SqlDriver,
        name: String,
    ): String? = driver
        .executeQuery(
            identifier = null,
            sql = "PRAGMA $name",
            mapper = { cursor ->
                QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
            },
            parameters = 0,
        ).value
}
