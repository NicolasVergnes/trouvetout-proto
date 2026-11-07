package app.trouvetout.core.data.db

import app.cash.sqldelight.db.SqlDriver

/** Current schema version; written to `meta` so exports can state what produced them. */
public const val SCHEMA_VERSION: Long = 2

/** Keys of the `meta` table. */
public object MetaKeys {
    public const val SCHEMA_VERSION: String = "schema_version"
    public const val DEVICE_ID: String = "device_id"
    public const val MODEL_VARIANT: String = "model_variant"
    public const val FORCE_TEXT_ONLY: String = "force_text_only"
    public const val PHOTO_SEARCH: String = "photo_search"

    /** Set while the text+vision file loads: still there at the next launch, the system killed the app (memory). */
    public const val VISION_LOADING: String = "vision_loading"

    /** H3: the app lock, its delay, and the Android keyboard-learning switch (lot 8). */
    public const val APP_LOCK: String = "app_lock"
    public const val LOCK_DELAY: String = "lock_delay"
    public const val NO_KEYBOARD_LEARNING: String = "no_keyboard_learning"

    /** J5: the last answer of the store, asked again after a wipe; the start of the trial is not here (ADR-025). */
    public const val PURCHASED: String = "purchased"

    /** A9: the recovery key, the file chosen, the last success and the state of the journal it covered, what failed. */
    public const val BACKUP_KEY: String = "backup_key"
    public const val BACKUP_LOCATION: String = "backup_location"
    public const val BACKUP_LAST_AT: String = "backup_last_at"
    public const val BACKUP_LAST_TRIED: String = "backup_last_tried"
    public const val BACKUP_LAST_HEAD: String = "backup_last_head"
    public const val BACKUP_PROBLEM: String = "backup_problem"

    /** Set while the destination is being written: still there later, the copy was cut and the file may be damaged. */
    public const val BACKUP_COPYING: String = "backup_copying"
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
