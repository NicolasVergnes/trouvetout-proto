package app.trouvetout.core.data.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.trouvetout.core.platform.toByteArray
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.JournalMode
import co.touchlab.sqliter.NO_VERSION_CHECK
import co.touchlab.sqliter.createDatabaseManager
import co.touchlab.sqliter.getStringOrNull
import co.touchlab.sqliter.longForQuery
import co.touchlab.sqliter.withStatement
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.closeFile
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.readDataOfLength

/**
 * SQLCipher driver (H2, ADR-023): the app links SQLCipher (SQLCipher.swift package) instead of the system SQLite,
 * and every connection is keyed. [directory] is the app's data directory under Application Support, excluded from
 * backups by the host app (the file is `<directory>/<fileName>`, no `databases/` subfolder); several reader
 * connections keep Flows responsive (WAL is the driver default). A plain database left by lots 1 to 7 is encrypted
 * once before the open ([DatabaseEncryption]).
 */
@OptIn(ExperimentalForeignApi::class)
public class IosDriverFactory(
    private val directory: String,
    private val keys: DatabaseKeyStore,
    private val fileName: String = "trouvetout.db",
) : DriverFactory {
    override fun create(): SqlDriver {
        NSFileManager.defaultManager.createDirectoryAtPath(
            directory,
            withIntermediateDirectories = true,
            attributes = mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication),
            error = null,
        )
        val literal = DatabaseEncryption.prepare("$directory/$fileName", keys, IosEncryptionSupport)
        val driver =
            NativeSqliteDriver(
                schema = TrouveToutDb.Schema,
                name = fileName,
                maxReaderConnections = 4,
                onConfiguration = { config ->
                    config.copy(
                        extendedConfig = config.extendedConfig.copy(basePath = directory),
                        encryptionConfig = DatabaseConfiguration.Encryption(key = literal),
                    )
                },
            )
        try {
            DatabaseEncryption.requireCipher(
                driver.executeQuery(null, "PRAGMA cipher_version", { cursor ->
                    QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
                }, 0).value,
            )
        } catch (failure: CipherMissingException) {
            driver.close()
            throw failure
        }
        return driver
    }
}

/** Raw SQLCipher connections (no schema handling) and plain file operations for the migration. */
@OptIn(ExperimentalForeignApi::class)
internal object IosEncryptionSupport : EncryptionSupport {
    override fun open(
        path: String,
        keyLiteral: String?,
    ): RawConnection {
        val manager =
            createDatabaseManager(
                DatabaseConfiguration(
                    name = path.substringAfterLast('/'),
                    version = NO_VERSION_CHECK,
                    create = {},
                    journalMode = JournalMode.DELETE,
                    extendedConfig = DatabaseConfiguration.Extended(basePath = path.substringBeforeLast('/')),
                    encryptionConfig = DatabaseConfiguration.Encryption(key = keyLiteral),
                ),
            )
        val connection = manager.createMultiThreadedConnection()
        return object : RawConnection {
            override fun exec(sql: String) {
                connection.rawExecSql(sql)
            }

            override fun long(sql: String): Long = connection.longForQuery(sql)

            override fun texts(sql: String): List<String> = connection.withStatement(sql) {
                val cursor = query()
                buildList { while (cursor.next()) add(cursor.getStringOrNull(0) ?: "") }
            }

            override fun close() {
                connection.close()
            }
        }
    }

    override fun header(path: String): ByteArray? {
        val handle = NSFileHandle.fileHandleForReadingAtPath(path) ?: return null
        return try {
            handle.readDataOfLength(HEADER_BYTES.toULong()).toByteArray()
        } finally {
            handle.closeFile()
        }
    }

    override fun delete(path: String) {
        if (NSFileManager.defaultManager.fileExistsAtPath(path)) {
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        }
    }

    override fun replace(
        from: String,
        to: String,
    ) {
        // rename(2): atomic, replaces the destination on the same file system.
        check(platform.posix.rename(from, to) == 0) { "rename failed" }
    }

    private const val HEADER_BYTES = 16
}
