package app.trouvetout.core.data.db

import app.cash.sqldelight.db.SqlDriver

/** Current schema version; written to `meta` so exports can state what produced them. */
public const val SCHEMA_VERSION: Long = 2

/** Keys of the `meta` table. */
public object MetaKeys {
    public const val SCHEMA_VERSION: String = "schema_version"
    public const val DEVICE_ID: String = "device_id"
    public const val MODEL_VARIANT: String = "model_variant"
}

/** Opens (creating or migrating) the SQLite database; implemented per platform, the caller owns the driver. */
public fun interface DriverFactory {
    public fun create(): SqlDriver
}

/**
 * Wraps an opened driver into the typed database. The recorded schema version only moves forward: a file
 * already migrated by a newer build is never stamped back to an older version.
 */
public fun openDatabase(driver: SqlDriver): TrouveToutDb {
    val database = TrouveToutDb(driver)
    val recorded = database.metaQueries.get(MetaKeys.SCHEMA_VERSION).executeAsOneOrNull()?.toLongOrNull() ?: 0L
    if (recorded < SCHEMA_VERSION) {
        database.metaQueries.set(MetaKeys.SCHEMA_VERSION, SCHEMA_VERSION.toString())
    }
    return database
}
