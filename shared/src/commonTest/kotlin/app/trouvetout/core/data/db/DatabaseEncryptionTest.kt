package app.trouvetout.core.data.db

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sequence of the plain → encrypted migration, over a fake SQLCipher (the real one runs in device tests). */
class DatabaseEncryptionTest {
    private val path = "/data/db/trouvetout.db"
    private val plainHeader = "SQLite format 3\u0000".encodeToByteArray()
    private val cipherHeader = ByteArray(16) { (it * 37 + 11).toByte() }

    private class Keys : DatabaseKeyStore {
        var key: ByteArray? = null
        var created = 0

        override fun exists() = key != null

        override fun obtain(): ByteArray = (
            key ?: ByteArray(32) { it.toByte() }.also {
                key = it
                created++
            }
        ).copyOf()

        override fun delete() {
            key = null
        }
    }

    /** Files are a header and their tables' row counts; `sqlcipher_export` copies them unless [corruptCopy]. */
    private inner class Support : EncryptionSupport {
        val files = HashMap<String, Pair<ByteArray, Map<String, Long>>>()
        val log = ArrayList<String>()
        var corruptCopy = false
        val openKeys = ArrayList<String?>()

        override fun open(
            path: String,
            keyLiteral: String?,
        ): RawConnection {
            openKeys += keyLiteral
            val versions = HashMap<String, Long>()
            versions[path] = 2
            return object : RawConnection {
                var attached: String? = null

                override fun exec(sql: String) {
                    log += sql.substringBefore(" KEY")
                    when {
                        sql.startsWith("ATTACH") -> attached = sql.substringAfter("'").substringBefore("'")
                        sql.startsWith("PRAGMA enc.user_version") ->
                            versions[attached!!] = sql.substringAfter("= ").toLong()
                    }
                }

                override fun long(sql: String): Long = when {
                    sql == "PRAGMA user_version" -> if (path.endsWith(".enc")) (versions[path] ?: 2) else 2
                    sql.startsWith("SELECT COUNT(*)") -> {
                        val table = sql.substringAfter("FROM \"").substringBefore("\"")
                        files.getValue(path).second.getValue(table)
                    }
                    else -> error(sql)
                }

                override fun texts(sql: String): List<String> = when {
                    sql.startsWith("SELECT name FROM sqlite_master") -> files.getValue(path).second.keys.sorted()
                    sql.startsWith("SELECT sqlcipher_export") -> {
                        val rows = files.getValue(path).second
                        files[attached!!] = cipherHeader to if (corruptCopy) rows.mapValues { it.value - 1 } else rows
                        log += "export"
                        emptyList()
                    }
                    else -> error(sql)
                }

                override fun close() {
                    log += "close"
                }
            }
        }

        override fun header(path: String): ByteArray? = files[path]?.first

        override fun delete(path: String) {
            if (files.remove(path) != null) log += "delete $path"
        }

        override fun replace(
            from: String,
            to: String,
        ) {
            files[to] = files.remove(from)!!
            log += "replace"
        }
    }

    @Test
    fun a_new_install_only_creates_the_key() {
        val keys = Keys()
        val support = Support()
        val literal = DatabaseEncryption.prepare(path, keys, support)
        assertEquals("x'" + ByteArray(32) { it.toByte() }.toHexString() + "'", literal)
        assertEquals(1, keys.created)
        assertTrue(support.files.isEmpty())
        assertTrue(support.openKeys.isEmpty(), "the driver creates the encrypted file")
    }

    @Test
    fun a_plain_database_is_copied_checked_then_replaced() {
        val keys = Keys()
        val support = Support()
        val rows = mapOf("event" to 120L, "item" to 40L, "meta" to 3L)
        support.files[path] = plainHeader to rows
        support.files["$path-wal"] = ByteArray(0) to emptyMap()
        val literal = DatabaseEncryption.prepare(path, keys, support)

        assertContentEquals(cipherHeader, support.header(path))
        assertEquals(rows, support.files.getValue(path).second)
        assertNull(support.header("$path.enc"))
        assertNull(support.header("$path-wal"), "a plain side file never sits next to the encrypted file")
        assertEquals(listOf(null, literal), support.openKeys, "plain first, then the copy with the key")
        val log = support.log
        assertEquals("PRAGMA wal_checkpoint(TRUNCATE)", log.first())
        assertTrue(log.indexOf("export") < log.indexOf("replace"))
        assertTrue(log.indexOf("delete $path-wal") < log.indexOf("replace"))
    }

    @Test
    fun a_copy_that_differs_leaves_the_plain_file_and_the_next_launch_starts_again() {
        val keys = Keys()
        val support = Support()
        val rows = mapOf("event" to 5L)
        support.files[path] = plainHeader to rows
        support.corruptCopy = true
        assertFailsWith<EncryptionCheckException> { DatabaseEncryption.prepare(path, keys, support) }
        assertContentEquals(plainHeader, support.header(path))
        assertFalse("replace" in support.log)

        support.corruptCopy = false
        support.log.clear()
        DatabaseEncryption.prepare(path, keys, support)
        assertEquals("delete $path.enc", support.log.first(), "the leftover copy is dropped first")
        assertContentEquals(cipherHeader, support.header(path))
        assertEquals(1, keys.created, "the same key all along")
    }

    @Test
    fun an_encrypted_database_without_its_key_is_left_untouched() {
        val keys = Keys()
        val support = Support()
        support.files[path] = cipherHeader to mapOf("event" to 1L)
        assertFailsWith<DatabaseKeyLostException> { DatabaseEncryption.prepare(path, keys, support) }
        assertEquals(0, keys.created, "no new key that would hide the loss")
        assertContentEquals(cipherHeader, support.header(path))
    }

    @Test
    fun an_encrypted_database_with_its_key_opens_as_is() {
        val keys = Keys()
        keys.obtain()
        val support = Support()
        support.files[path] = cipherHeader to mapOf("event" to 1L)
        DatabaseEncryption.prepare(path, keys, support)
        assertTrue(support.openKeys.isEmpty())
        assertTrue(support.log.isEmpty())
    }

    @Test
    fun an_empty_file_is_dropped() {
        val support = Support()
        support.files[path] = ByteArray(0) to emptyMap()
        DatabaseEncryption.prepare(path, Keys(), support)
        assertNull(support.header(path))
    }

    @Test
    fun the_system_sqlite_is_refused() {
        assertFailsWith<CipherMissingException> { DatabaseEncryption.requireCipher(null) }
        assertFailsWith<CipherMissingException> { DatabaseEncryption.requireCipher("") }
        DatabaseEncryption.requireCipher("4.19.0 community")
    }
}
