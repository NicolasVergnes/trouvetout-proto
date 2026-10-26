package app.trouvetout.android.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands an export to the system share sheet. The private data directory is never exposed: the archive is
 * copied to a dedicated cache folder that the FileProvider serves (`res/xml/file_paths.xml`), with a one-shot
 * read grant for the chosen app. The copy lives until the next share, the next launch or the total wipe.
 */
object ExportSharing {
    const val SHARE_DIR = "share"
    private const val MIME_ZIP = "application/zip"

    /** Copies the export into the served cache folder; file I/O, call it off the main thread. */
    fun prepare(
        context: Context,
        exportAbsolutePath: String,
    ): Uri {
        val source = File(exportAbsolutePath)
        val shareDir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        shareDir.listFiles()?.forEach { it.delete() }
        val copy = File(shareDir, source.name)
        source.copyTo(copy, overwrite = true)
        return FileProvider.getUriForFile(context, "${context.packageName}.exports", copy)
    }

    /** Opens the system chooser on a prepared copy. */
    fun launch(
        context: Context,
        uri: Uri,
    ) {
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = MIME_ZIP
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(Intent.createChooser(send, null))
    }

    /** Removes the shared copies (launch, total wipe). */
    fun clear(context: Context) {
        File(context.cacheDir, SHARE_DIR).deleteRecursively()
    }
}
