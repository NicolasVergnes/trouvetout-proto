package app.trouvetout.core.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import java.io.File

/**
 * Framework SQLite driver. [absolutePath] must live under `noBackupFilesDir` (never the default databases
 * directory) so the journal stays out of system backups. WAL is enabled for concurrent readers (Flows) with
 * `synchronous=FULL`: the journal has no other copy, a power cut must not lose a committed capture.
 */
public class AndroidDriverFactory(
    context: Context,
    private val absolutePath: String,
) : DriverFactory {
    private val appContext = context.applicationContext

    override fun create(): SqlDriver {
        File(absolutePath).parentFile?.mkdirs()
        return AndroidSqliteDriver(
            schema = TrouveToutDb.Schema,
            context = appContext,
            name = absolutePath,
            callback = Callback(absolutePath),
        )
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
}
