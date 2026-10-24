package app.trouvetout.core.data.db

import app.cash.sqldelight.db.SqlDriver

/** Current schema version; written to `meta` so exports can state what produced them. */
public const val SCHEMA_VERSION: Long = 1

/** Keys of the `meta` table. */
public object MetaKeys {
    public const val SCHEMA_VERSION: String = "schema_version"
    public const val DEVICE_ID: String = "device_id"
}

/** Opens (creating or migrating) the SQLite database; implemented per platform, the caller owns the driver. */
public fun interface DriverFactory {
    public fun create(): SqlDriver
}

/** Wraps an opened driver into the typed database and records the schema version. */
public fun openDatabase(driver: SqlDriver): TrouveToutDb {
    driver.execute(null, "PRAGMA foreign_keys = ON", 0)
    val database = TrouveToutDb(driver)
    database.metaQueries.set(MetaKeys.SCHEMA_VERSION, SCHEMA_VERSION.toString())
    return database
}
