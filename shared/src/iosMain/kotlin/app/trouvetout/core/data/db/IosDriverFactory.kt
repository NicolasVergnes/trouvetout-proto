package app.trouvetout.core.data.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration

/**
 * Native SQLite driver. [directory] is the app's data directory under Application Support, excluded from
 * backups by the host app; several reader connections keep Flows responsive (WAL is the driver default).
 */
public class IosDriverFactory(
    private val directory: String,
    private val fileName: String = "trouvetout.db",
) : DriverFactory {
    override fun create(): SqlDriver = NativeSqliteDriver(
        schema = TrouveToutDb.Schema,
        name = fileName,
        maxReaderConnections = 4,
        onConfiguration = { config ->
            config.copy(extendedConfig = DatabaseConfiguration.Extended(basePath = directory))
        },
    )
}
