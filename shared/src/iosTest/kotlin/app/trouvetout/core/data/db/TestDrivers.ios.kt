package app.trouvetout.core.data.db

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import platform.Foundation.NSUUID

/**
 * A named in-memory database is shared by every connection with that name while one stays open, so each test
 * database gets a unique name: tests never see each other's rows.
 */
actual fun inMemoryDriverFactory(): DriverFactory = DriverFactory {
    NativeSqliteDriver(
        schema = TrouveToutDb.Schema,
        name = "test-${NSUUID().UUIDString}.db",
        onConfiguration = { config -> config.copy(inMemory = true) },
    )
}
