package app.trouvetout.core.data.db

import android.content.Context
import android.system.Os
import android.system.OsConstants
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

/**
 * SQLCipher driver (H2, ADR-023). [absolutePath] must live under `noBackupFilesDir` (never the default databases
 * directory) so the journal stays out of system backups. A plain database left by lots 1 to 7 is encrypted once
 * before the open ([DatabaseEncryption]). WAL is enabled for concurrent readers (Flows) with `synchronous=FULL`:
 * the journal has no other copy, a power cut must not lose a committed capture.
 */
public class AndroidDriverFactory(
    context: Context,
    private val absolutePath: String,
    private val keys: DatabaseKeyStore,
) : DriverFactory {
    private val appContext = context.applicationContext

    override fun create(): SqlDriver {
        loadSqlCipher()
        File(absolutePath).parentFile?.mkdirs()
        val literal = DatabaseEncryption.prepare(absolutePath, keys, AndroidEncryptionSupport)
        val driver =
            AndroidSqliteDriver(
                schema = TrouveToutDb.Schema,
                context = appContext,
                name = absolutePath,
                factory = SupportOpenHelperFactory(literal.encodeToByteArray()),
                callback = Callback(absolutePath),
            )
        try {
            DatabaseEncryption.requireCipher(
                driver.executeQuery(null, "PRAGMA cipher_version", { cursor ->
                    QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
                }, 0).value,
            )
        } catch (failure: CipherMissingException) {
            driver.close()
            throw failure
        }
        return driver
    }

    private class Callback(
        private val absolutePath: String,
    ) : AndroidSqliteDriver.Callback(TrouveToutDb.Schema) {
        override fun onConfigure(db: SupportSQLiteDatabase) {
            super.onConfigure(db)
            db.enableWriteAheadLogging()
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            db.execSQL("PRAGMA synchronous = FULL")
        }

        /**
         * The framework default deletes a corrupt database and starts afresh. The journal is the only copy of
         * the user's inventory, so the files are set aside instead (recoverable by the repair tooling) and a
         * fresh database is created next to them. Nothing but file suffixes is logged.
         */
        override fun onCorruption(db: SupportSQLiteDatabase) {
            runCatching { if (db.isOpen) db.close() }
            val stamp = System.currentTimeMillis()
            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                val file = File(absolutePath + suffix)
                if (file.exists()) file.renameTo(File("$absolutePath.corrupt-$stamp$suffix"))
            }
        }
    }

    private companion object {
        @Volatile
        private var loaded = false

        fun loadSqlCipher() {
            if (!loaded) {
                System.loadLibrary("sqlcipher")
                loaded = true
            }
        }
    }
}

/** Raw SQLCipher connections and plain file operations for the migration of a plain database. */
internal object AndroidEncryptionSupport : EncryptionSupport {
    override fun open(
        path: String,
        keyLiteral: String?,
    ): RawConnection {
        val db =
            SQLiteDatabase.openDatabase(
                path,
                keyLiteral?.encodeToByteArray() ?: ByteArray(0),
                null,
                // CREATE: an attached database inherits the flags, and the ATTACH must create the encrypted copy.
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
                null,
                null,
            )
        // One connection for every statement: with the framework's compatibility WAL the pool would run a SELECT
        // (the export) on a second connection that never saw the ATTACH.
        db.disableWriteAheadLogging()
        return object : RawConnection {
            override fun exec(sql: String) {
                db.rawExecSQL(sql)
            }

            override fun attach(
                path: String,
                keyLiteral: String,
            ) {
                // execSQL, which the framework handles apart for an ATTACH (one connection from then on, the
                // attachment kept on it); both values bound, so the key is in no SQL text.
                db.execSQL("ATTACH DATABASE ? AS enc KEY ?", arrayOf<Any>(path, keyLiteral))
            }

            override fun long(sql: String): Long = db.rawQuery(sql, null).use { cursor ->
                check(cursor.moveToFirst()) { "no row" }
                cursor.getLong(0)
            }

            override fun texts(sql: String): List<String> = db.rawQuery(sql, null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0) ?: "") }
            }

            override fun close() {
                db.close()
            }
        }
    }

    override fun header(path: String): ByteArray? {
        val file = File(path)
        if (!file.isFile) return null
        return file.inputStream().use { input ->
            val buffer = ByteArray(HEADER_BYTES)
            var read = 0
            while (read < HEADER_BYTES) {
                val n = input.read(buffer, read, HEADER_BYTES - read)
                if (n < 0) break
                read += n
            }
            buffer.copyOf(read)
        }
    }

    override fun delete(path: String) {
        File(path).delete()
    }

    override fun replace(
        from: String,
        to: String,
    ) {
        // rename(2): atomic, replaces the destination on the same file system; the directory entry is synced so a
        // power cut cannot bring the plain file back after its side files are gone.
        check(File(from).renameTo(File(to))) { "rename failed" }
        runCatching {
            val fd = Os.open(checkNotNull(File(to).parent), OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        }
    }

    private const val HEADER_BYTES = 16
}
