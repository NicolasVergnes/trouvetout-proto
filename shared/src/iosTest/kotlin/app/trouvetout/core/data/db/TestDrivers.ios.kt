package app.trouvetout.core.data.db

import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual fun inMemoryDriverFactory(): DriverFactory = DriverFactory {
    NativeSqliteDriver(
        schema = TrouveToutDb.Schema,
        name = "test.db",
        onConfiguration = { config -> config.copy(inMemory = true) },
    )
}
