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

/**
 * Copies a prepared backup (A9) to the file the user chose, through the system's document provider: the app has no
 * network, the provider (Google Drive, for one) sends it. Foreground only (N6): the export and the encryption stop when
 * the app leaves; the copy, once started, finishes, as it replaces the previous backup.
 */
class BackupRunner(
    private val resolver: ContentResolver,
) {
    private val running = Mutex()

    /** Keeps access to the file the picker returned, across restarts. */
    fun keepAccess(uri: Uri) {
        resolver.takePersistableUriPermission(uri, ACCESS)
    }

    fun releaseAccess(location: String) {
        runCatching { resolver.releasePersistableUriPermission(Uri.parse(location), ACCESS) }
    }

    suspend fun runIfDue(shared: SharedFactory) {
        running.withLock { if (shared.backup.isDue()) backUp(shared) }
    }

    /** The outcome is in the state of the backup. */
    suspend fun runNow(shared: SharedFactory) {
        running.withLock { backUp(shared) }
    }

    private suspend fun backUp(shared: SharedFactory) {
        val location = shared.backup.state().location ?: return
        val prepared = shared.backup.prepare() as? PreparedBackup.Ready ?: return
        withContext(NonCancellable + Dispatchers.IO) {
            val copied = copy(prepared.absolutePath, Uri.parse(location))
            shared.backup.finished(prepared, copied)
        }
    }

    private fun copy(
        path: String,
        target: Uri,
    ): Boolean = try {
        File(path).inputStream().use { input ->
            val output = resolver.openOutputStream(target, "wt") ?: return false
            output.use { input.copyTo(it) }
        }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Access revoked, file gone, provider gone, disk full: said in the settings, tried again at the next return.
        false
    }

    private companion object {
        const val ACCESS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
