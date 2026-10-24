package app.trouvetout.core.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import java.io.File

/**
 * Framework SQLite driver. [absolutePath] must live under `noBackupFilesDir` (never the default databases
 * directory) so the journal stays out of system backups; WAL is enabled for concurrent readers (Flows).
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
            callback =
                object : AndroidSqliteDriver.Callback(TrouveToutDb.Schema) {
                    override fun onConfigure(db: SupportSQLiteDatabase) {
                        super.onConfigure(db)
                        db.enableWriteAheadLogging()
                    }
                },
        )
    }
}
