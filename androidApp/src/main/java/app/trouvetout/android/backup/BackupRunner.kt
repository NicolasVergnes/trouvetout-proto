package app.trouvetout.android.backup

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import app.trouvetout.core.data.backup.PreparedBackup
import app.trouvetout.core.di.SharedFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

/**
 * Copies a prepared backup (A9) to the file the user chose, through the system's document provider: the app has no
 * network, the provider (Google Drive, for one) sends it. Foreground only (N6): the export and the encryption stop when
 * the app leaves. The copy goes on, as it overwrites the previous backup; if the process dies during it, the core finds
 * its mark, says so and tries again (ADR-025: the picker gives one file, so nothing can replace it atomically).
 */
class BackupRunner(
    private val resolver: ContentResolver,
) {
    private val running = Mutex()

    /** Keeps access to the file the picker returned, across restarts. */
    fun keepAccess(uri: Uri) {
        resolver.takePersistableUriPermission(uri, ACCESS)
    }

    /** The app keeps access to no other document: all of it goes when backups stop or the data is erased. */
    fun releaseAccess() {
        resolver.persistedUriPermissions.forEach { permission ->
            runCatching { resolver.releasePersistableUriPermission(permission.uri, ACCESS) }
        }
    }

    suspend fun runIfDue(shared: SharedFactory) {
        running.withLock { if (shared.backup.isDue()) backUp(shared) }
    }

    /** The outcome is in the state of the backup. */
    suspend fun runNow(shared: SharedFactory) {
        running.withLock { backUp(shared) }
    }

    private suspend fun backUp(shared: SharedFactory) {
        val target = shared.backup.state().location?.let(Uri::parse) ?: return
        if (!reachable(target)) {
            shared.backup.reportDestinationLost()
            return
        }
        val prepared = shared.backup.prepare() as? PreparedBackup.Ready ?: return
        withContext(NonCancellable + Dispatchers.IO) {
            var copy = Copy.FAILED
            try {
                shared.backup.copying()
                copy = copy(prepared.absolutePath, target)
            } finally {
                shared.backup.finished(prepared, copy == Copy.DONE)
            }
            if (copy == Copy.LOST) shared.backup.reportDestinationLost()
        }
    }

    /** Without its permission, no use spending an export. Only a provider's document has one to lose. */
    private fun reachable(target: Uri): Boolean = target.scheme != ContentResolver.SCHEME_CONTENT ||
        resolver.persistedUriPermissions.any { it.uri == target && it.isWritePermission }

    private enum class Copy { DONE, LOST, FAILED }

    private fun copy(
        path: String,
        target: Uri,
    ): Copy = try {
        File(path).inputStream().use { input ->
            resolver.openOutputStream(target, "wt")?.use { input.copyTo(it) } ?: throw FileNotFoundException()
        }
        Copy.DONE
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: FileNotFoundException) {
        Copy.LOST
    } catch (_: SecurityException) {
        Copy.LOST
    } catch (_: Exception) {
        // The provider gone, the disk full: said in the settings, tried again later.
        Copy.FAILED
    }

    private companion object {
        const val ACCESS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
