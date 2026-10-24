package app.trouvetout.core.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties

/** JVM and Android host tests share a real in-memory SQLite through the JDBC driver (no emulator needed). */
actual fun inMemoryDriverFactory(): DriverFactory =
    DriverFactory { JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), TrouveToutDb.Schema) }
