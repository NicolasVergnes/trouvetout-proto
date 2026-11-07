package app.trouvetout.core.data.backup

import app.trouvetout.core.data.db.MetaKeys
import app.trouvetout.core.data.db.TrouveToutDb
import app.trouvetout.core.data.export.ExportService
import app.trouvetout.core.data.export.ImportRejection
import app.trouvetout.core.data.export.ImportReport
import app.trouvetout.core.data.export.ImportResult
import app.trouvetout.core.data.export.ImportService
import app.trouvetout.core.data.export.MAX_ARCHIVE_BYTES
import app.trouvetout.core.platform.FileStore
import app.trouvetout.core.platform.use
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/** What went wrong with the last backup, as the settings say it. */
public enum class BackupProblem {
    /** The destination refused the file, the storage is full, the export failed. */
    FAILED,

    /** The export is over the size an import accepts: it could not be restored, so it is not written. */
    TOO_LARGE,
}

public data class BackupState(
    /** The file the user chose, as the host wrote it (Android: a URI; iOS: a bookmark); null when off. */
    val location: String?,
    val lastSuccessMillis: Long?,
    val problem: BackupProblem?,
)

public sealed interface PreparedBackup {
    /** An encrypted file to copy to the destination, then [BackupService.finished] removes it. */
    public data class Ready(
        val absolutePath: String,
        internal val head: String,
    ) : PreparedBackup

    public data object TooLarge : PreparedBackup

    public data object NotEnabled : PreparedBackup
}

public sealed interface BackupRestoreResult {
    public data class Imported(
        val report: ImportReport,
    ) : BackupRestoreResult

    /** The archive inside was refused by the import. */
    public data class Refused(
        val reason: ImportRejection,
    ) : BackupRestoreResult

    /** The text is not a recovery key: a typing mistake. */
    public data object BadKey : BackupRestoreResult

    public data object NotABackup : BackupRestoreResult

    /** A well-formed key that does not open the file, or a damaged file; nothing tells them apart. */
    public data object CannotOpen : BackupRestoreResult
}

/**
 * The optional encrypted backup (A9, ADR-025). An export (A7), encrypted with a key only the user holds in writing,
 * goes to a file the user chose with the system picker, in their own cloud or elsewhere: the app has no network of its
 * own, the host copies the bytes. It runs in the foreground only (N6), at most once a day and only after a change. The
 * key, in `meta`, sits in the encrypted database, is never exported, and goes with the total wipe (H6).
 */
@Suppress("LongParameterList") // the collaborators of one service, set where the core is built
public class BackupService internal constructor(
    private val db: TrouveToutDb,
    private val files: FileStore,
    private val exports: ExportService,
    private val imports: ImportService,
    /** Null where the host gives no crypto: tools and tests. */
    private val crypto: BackupCrypto?,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
) {
    /** A new key to show once; nothing is kept until [enable]. */
    public fun newRecoveryKey(): String =
        RecoveryKey.encode(checkNotNull(crypto) { "no crypto" }.randomBytes(RecoveryKey.KEY_BYTES))

    /** The user confirmed having written [recoveryKey] down and chose [location]. False when the key is not one. */
    @Throws(Exception::class) // to Swift: an error it can handle, not the end of the process
    public suspend fun enable(
        recoveryKey: String,
        location: String,
    ): Boolean = withContext(io) {
        val key = RecoveryKey.decode(recoveryKey) ?: return@withContext false
        db.metaQueries.transaction {
            db.metaQueries.set(MetaKeys.BACKUP_KEY, RecoveryKey.encode(key))
            db.metaQueries.set(MetaKeys.BACKUP_LOCATION, location)
            forgetProgress()
        }
        true
    }

    /** Backups stop; the file already written stays where it is, and the key is forgotten here. */
    @Throws(Exception::class)
    public suspend fun disable() {
        withContext(io) {
            db.metaQueries.transaction {
                db.metaQueries.remove(MetaKeys.BACKUP_KEY)
                db.metaQueries.remove(MetaKeys.BACKUP_LOCATION)
                forgetProgress()
            }
        }
    }

    @Throws(Exception::class)
    public suspend fun state(): BackupState = withContext(io) {
        BackupState(
            location = meta(MetaKeys.BACKUP_LOCATION),
            lastSuccessMillis = meta(MetaKeys.BACKUP_LAST_AT)?.toLongOrNull(),
            problem =
                meta(MetaKeys.BACKUP_PROBLEM)?.let { stored ->
                    BackupProblem.entries.firstOrNull { it.name == stored }
                },
        )
    }

    /**
     * On, with something new, a day since the last success and an hour since the last try: a destination that keeps
     * failing or an archive too large does not cost a whole export at each return to the app.
     */
    @Throws(Exception::class)
    public suspend fun isDue(): Boolean = withContext(io) {
        if (meta(MetaKeys.BACKUP_KEY) == null) return@withContext false
        val head = head() ?: return@withContext false
        val now = clock.now().toEpochMilliseconds()
        head != meta(MetaKeys.BACKUP_LAST_HEAD) &&
            passed(MetaKeys.BACKUP_LAST_AT, MIN_INTERVAL_MILLIS, now) &&
            passed(MetaKeys.BACKUP_LAST_TRIED, RETRY_INTERVAL_MILLIS, now)
    }

    /** Exports and encrypts into the private directory; the plain archive is gone when this returns. */
    @Throws(Exception::class)
    public suspend fun prepare(): PreparedBackup = withContext(io) {
        val key =
            meta(MetaKeys.BACKUP_KEY)?.let(RecoveryKey::decode) ?: return@withContext PreparedBackup.NotEnabled
        val format = BackupFormat(checkNotNull(crypto) { "no crypto" })
        val head = head().orEmpty()
        db.metaQueries.set(MetaKeys.BACKUP_LAST_TRIED, clock.now().toEpochMilliseconds().toString())
        val archive = exports.export()
        try {
            val source = checkNotNull(files.source(archive.relativePath)) { "no archive" }
            source.use {
                if (it.size > MAX_ARCHIVE_BYTES) {
                    db.metaQueries.set(MetaKeys.BACKUP_PROBLEM, BackupProblem.TOO_LARGE.name)
                    return@withContext PreparedBackup.TooLarge
                }
                files.sink(FILE).use { sink -> format.encrypt(this, key, it, sink) }
            }
        } finally {
            files.delete(archive.relativePath)
        }
        PreparedBackup.Ready(files.absolutePath(FILE), head)
    }

    /** The host copied [prepared] to the destination, or could not: the private copy goes, the outcome is kept. */
    @Throws(Exception::class)
    public suspend fun finished(
        prepared: PreparedBackup.Ready,
        succeeded: Boolean,
    ) {
        withContext(io) {
            files.delete(FILE)
            // Not after a disable that came meanwhile: the settings would show a backup that is off.
            if (meta(MetaKeys.BACKUP_KEY) == null) return@withContext
            if (succeeded) {
                db.metaQueries.set(MetaKeys.BACKUP_LAST_AT, clock.now().toEpochMilliseconds().toString())
                db.metaQueries.set(MetaKeys.BACKUP_LAST_HEAD, prepared.head)
                db.metaQueries.remove(MetaKeys.BACKUP_PROBLEM)
            } else {
                db.metaQueries.set(MetaKeys.BACKUP_PROBLEM, BackupProblem.FAILED.name)
            }
        }
    }

    /**
     * Opens the backup the host copied to [relativePath] (under `imports/`, a size the host bounded with
     * [MAX_BACKUP_BYTES]) and hands the archive inside to the import, with all its checks. Both private copies are
     * removed, whatever the outcome.
     */
    @Throws(Exception::class)
    public suspend fun restore(
        relativePath: String,
        recoveryKey: String,
    ): BackupRestoreResult = withContext(io) {
        val decrypted = "$relativePath.zip"
        try {
            val key = RecoveryKey.decode(recoveryKey) ?: return@withContext BackupRestoreResult.BadKey
            val format = BackupFormat(checkNotNull(crypto) { "no crypto" })
            val source = checkNotNull(files.source(relativePath)) { "no backup" }
            source.use {
                files.sink(decrypted).use { sink -> format.decrypt(this, key, it, sink) }
            }
            when (val result = imports.import(decrypted)) {
                is ImportResult.Done -> BackupRestoreResult.Imported(result.report)
                is ImportResult.Rejected -> BackupRestoreResult.Refused(result.reason)
            }
        } catch (_: BackupFormat.NotABackup) {
            BackupRestoreResult.NotABackup
        } catch (_: BackupFormat.CannotOpen) {
            BackupRestoreResult.CannotOpen
        } finally {
            files.delete(relativePath)
            files.delete(decrypted)
        }
    }

    internal fun purge() {
        files.deleteDirectory(DIRECTORY)
    }

    private fun meta(key: String): String? = db.metaQueries.get(key).executeAsOneOrNull()

    private fun passed(
        key: String,
        interval: Long,
        now: Long,
    ): Boolean {
        val last = meta(key)?.toLongOrNull() ?: return true
        return now - last >= interval
    }

    private fun forgetProgress() {
        db.metaQueries.remove(MetaKeys.BACKUP_LAST_AT)
        db.metaQueries.remove(MetaKeys.BACKUP_LAST_TRIED)
        db.metaQueries.remove(MetaKeys.BACKUP_LAST_HEAD)
        db.metaQueries.remove(MetaKeys.BACKUP_PROBLEM)
    }

    private fun head(): String? = db.eventQueries.head().executeAsOneOrNull()?.id

    public companion object {
        public const val DIRECTORY: String = "backups"
        private const val FILE = "$DIRECTORY/backup.ttbk"
        private const val RETRY_INTERVAL_MILLIS = 60L * 60 * 1000
        private const val MIN_INTERVAL_MILLIS = 24 * RETRY_INTERVAL_MILLIS

        /** What a host may copy into `imports/` before calling [restore]. */
        public val MAX_BACKUP_BYTES: Long = BackupFormat.maxSize(MAX_ARCHIVE_BYTES)

        /** A neutral name for the file the system picker creates (no brand of the app, no business data). */
        public const val SUGGESTED_FILE_NAME: String = "backup.ttbk"
    }
}
