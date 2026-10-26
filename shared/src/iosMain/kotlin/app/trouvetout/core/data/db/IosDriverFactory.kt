package app.trouvetout.core.data.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey

/**
 * Native SQLite driver. [directory] is the app's data directory under Application Support, excluded from
 * backups by the host app (the file is `<directory>/<fileName>`, no `databases/` subfolder); several reader
 * connections keep Flows responsive (WAL is the driver default).
 */
@OptIn(ExperimentalForeignApi::class)
public class IosDriverFactory(
    private val directory: String,
    private val fileName: String = "trouvetout.db",
) : DriverFactory {
    override fun create(): SqlDriver {
        NSFileManager.defaultManager.createDirectoryAtPath(
            directory,
            withIntermediateDirectories = true,
            attributes = mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication),
            error = null,
        )
        return NativeSqliteDriver(
            schema = TrouveToutDb.Schema,
            name = fileName,
            maxReaderConnections = 4,
            onConfiguration = { config ->
                config.copy(extendedConfig = config.extendedConfig.copy(basePath = directory))
            },
        )
    }
}
