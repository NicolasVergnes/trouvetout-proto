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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/** What went wrong with the last backup, as the settings say it. */
public enum class BackupProblem {
    /** The destination refused the file, the storage is full, the export failed. */
    FAILED,

    /** The export is over the size an import accepts: it could not be restored, so it is not written. */
    TOO_LARGE,

    /** The chosen file is gone or the app lost access to it: only choosing another one helps, with the same key. */
    DESTINATION_LOST,
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
    /** Keeps the launch's clearing of leftovers away from the files of a backup running in this process. */
    private val working = Mutex()
    private var running = false

    /** A new key to show once; nothing is kept until [enable]. */
    @Throws(Exception::class) // to Swift: an error it can handle, not the end of the process
    public fun newRecoveryKey(): String =
        RecoveryKey.encode(checkNotNull(crypto) { "no crypto" }.randomBytes(RecoveryKey.KEY_BYTES))

    /** Whether [typed] is the last group of [recoveryKey], with the same forgiveness as reading a key back. */
    public fun confirmsKeyEnd(
        recoveryKey: String,
        typed: String,
    ): Boolean = RecoveryKey.confirmsEnd(recoveryKey, typed)

    /** The user confirmed having written [recoveryKey] down and chose [location]. False when the key is not one. */
    @Throws(Exception::class)
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

    /** Another file for the same key, after [BackupProblem.DESTINATION_LOST]; the next return backs up there. */
    @Throws(Exception::class)
    public suspend fun relocate(location: String) {
        withContext(io) {
            db.metaQueries.transaction {
                db.metaQueries.set(MetaKeys.BACKUP_LOCATION, location)
                db.metaQueries.remove(MetaKeys.BACKUP_PROBLEM)
                db.metaQueries.remove(MetaKeys.BACKUP_LAST_TRIED)
                db.metaQueries.remove(MetaKeys.BACKUP_LAST_HEAD)
            }
        }
    }

    /** The host found the chosen file gone or out of reach, before spending an export on it. */
    @Throws(Exception::class)
    public suspend fun reportDestinationLost() {
        withContext(io) { record(BackupProblem.DESTINATION_LOST) }
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
            problem = problem(),
        )
    }

    /**
     * On, with something new and a day since the last success. After a failure it tries again, even with nothing new as
     * the file may be damaged, at most once an hour: a destination that keeps failing does not cost a whole export at
     * each return to the app. An archive too large waits for a day; a lost destination waits for the user.
     */
    @Throws(Exception::class)
    public suspend fun isDue(): Boolean = withContext(io) {
        if (meta(MetaKeys.BACKUP_KEY) == null) return@withContext false
        val head = head() ?: return@withContext false
        val now = clock.now().toEpochMilliseconds()
        when (problem()) {
            BackupProblem.DESTINATION_LOST -> false
            BackupProblem.FAILED -> passed(MetaKeys.BACKUP_LAST_TRIED, RETRY_INTERVAL_MILLIS, now)
            BackupProblem.TOO_LARGE ->
                head != meta(MetaKeys.BACKUP_LAST_HEAD) && passed(MetaKeys.BACKUP_LAST_TRIED, MIN_INTERVAL_MILLIS, now)
            null -> head != meta(MetaKeys.BACKUP_LAST_HEAD) && passed(MetaKeys.BACKUP_LAST_AT, MIN_INTERVAL_MILLIS, now)
        }
    }

    /**
     * Exports and encrypts into the private directory. Whatever happens the plain archive does not outlive this call,
     * and if the process dies here, it goes at the next launch ([purge]). A failure is kept for the settings, then
     * thrown; leaving the app (a cancellation) is no failure.
     */
    @Suppress("TooGenericExceptionCaught") // whatever it was, it is said and thrown on
    @Throws(Exception::class)
    public suspend fun prepare(): PreparedBackup = withContext(io) {
        val key =
            meta(MetaKeys.BACKUP_KEY)?.let(RecoveryKey::decode) ?: return@withContext PreparedBackup.NotEnabled
        val format = BackupFormat(checkNotNull(crypto) { "no crypto" })
        working.withLock { running = true }
        var ready = false
        try {
            val prepared = encryptedExport(format, key)
            ready = prepared is PreparedBackup.Ready
            prepared
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            record(BackupProblem.FAILED)
            throw failure
        } finally {
            files.delete(ARCHIVE)
            if (!ready) {
                files.delete(FILE)
                working.withLock { running = false }
            }
        }
    }

    private suspend fun CoroutineScope.encryptedExport(
        format: BackupFormat,
        key: ByteArray,
    ): PreparedBackup {
        val head = head().orEmpty()
        exports.exportTo(ARCHIVE)
        checkNotNull(files.source(ARCHIVE)) { "no archive" }.use { source ->
            if (source.size > MAX_ARCHIVE_BYTES) {
                record(BackupProblem.TOO_LARGE)
                return PreparedBackup.TooLarge
            }
            files.sink(FILE).use { sink -> format.encrypt(this, key, source, sink) }
        }
        return PreparedBackup.Ready(files.absolutePath(FILE), head)
    }

    /**
     * The host is about to overwrite the destination. If nothing reports back (the app killed during the copy), the
     * previous backup may be damaged: the settings say so instead of showing the last success, and it tries again.
     */
    @Throws(Exception::class)
    public suspend fun copying() {
        withContext(io) { db.metaQueries.set(MetaKeys.BACKUP_COPYING, "true") }
    }

    /** The host copied [prepared] to the destination, or could not: the private copy goes, the outcome is kept. */
    @Throws(Exception::class)
    public suspend fun finished(
        prepared: PreparedBackup.Ready,
        succeeded: Boolean,
    ) {
        withContext(io) {
            files.delete(FILE)
            working.withLock { running = false }
            db.metaQueries.remove(MetaKeys.BACKUP_COPYING)
            // Not after a disable that came meanwhile: the settings would show a backup that is off.
            if (meta(MetaKeys.BACKUP_KEY) == null) return@withContext
            if (succeeded) {
                val now = clock.now().toEpochMilliseconds().toString()
                db.metaQueries.set(MetaKeys.BACKUP_LAST_AT, now)
                db.metaQueries.set(MetaKeys.BACKUP_LAST_TRIED, now)
                db.metaQueries.set(MetaKeys.BACKUP_LAST_HEAD, prepared.head)
                db.metaQueries.remove(MetaKeys.BACKUP_PROBLEM)
            } else {
                record(BackupProblem.FAILED)
            }
        }
    }

    /**
     * Opens the backup the host copied to [relativePath] (under `imports/`, at most [MAX_BACKUP_BYTES]) and hands the
     * archive inside to the import, with all its checks. Both private copies are removed, whatever the outcome.
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
                if (it.size > MAX_BACKUP_BYTES) return@withContext BackupRestoreResult.NotABackup
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

    /** What a killed process left, the plain archive included; never the files of a backup running now. */
    internal suspend fun purge() {
        working.withLock { if (!running) files.deleteDirectory(DIRECTORY) }
    }

    private fun meta(key: String): String? = db.metaQueries.get(key).executeAsOneOrNull()

    /** A copy marked as started and not reported, with no backup running here: it was cut. */
    private fun problem(): BackupProblem? {
        val name = meta(MetaKeys.BACKUP_PROBLEM)
        val stored = BackupProblem.entries.firstOrNull { it.name == name }
        val cut = !running && meta(MetaKeys.BACKUP_COPYING) != null
        return stored ?: BackupProblem.FAILED.takeIf { cut }
    }

    private fun record(problem: BackupProblem) {
        db.metaQueries.set(MetaKeys.BACKUP_PROBLEM, problem.name)
        db.metaQueries.set(MetaKeys.BACKUP_LAST_TRIED, clock.now().toEpochMilliseconds().toString())
    }

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
        db.metaQueries.remove(MetaKeys.BACKUP_COPYING)
    }

    /** The journal only grows: its size says whether something is new, older events merged by an import included. */
    private fun head(): String? = db.eventQueries.count().executeAsOne().takeIf { it > 0 }?.toString()

    public companion object {
        public const val DIRECTORY: String = "backups"
        private const val FILE = "$DIRECTORY/backup.ttbk"
        private const val ARCHIVE = "$DIRECTORY/archive.zip"
        private const val RETRY_INTERVAL_MILLIS = 60L * 60 * 1000
        private const val MIN_INTERVAL_MILLIS = 24 * RETRY_INTERVAL_MILLIS

        /** What a host may copy into `imports/` before calling [restore]. */
        public val MAX_BACKUP_BYTES: Long = BackupFormat.maxSize(MAX_ARCHIVE_BYTES)

        /** A neutral name for the file the system picker creates (no brand of the app, no business data). */
        public const val SUGGESTED_FILE_NAME: String = "backup.ttbk"
    }
}
