package app.trouvetout.core.data.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.util.Properties

/** JDBC SQLite driver for the evaluation tools and JVM tests; `null` path means an in-memory database. */
public class JvmDriverFactory(
    private val path: String?,
) : DriverFactory {
    override fun create(): SqlDriver {
        val url =
            if (path == null) {
                JdbcSqliteDriver.IN_MEMORY
            } else {
                File(path).parentFile?.mkdirs()
                "jdbc:sqlite:$path"
            }
        return JdbcSqliteDriver(url, Properties(), TrouveToutDb.Schema)
    }
}
