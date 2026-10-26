package app.trouvetout.android.ui.settings

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands an export to the system share sheet. The private data directory is never exposed: the archive is
 * copied to a dedicated cache folder that the FileProvider serves (`res/xml/file_paths.xml`), with a one-shot
 * read grant for the chosen app.
 */
object ExportSharing {
    private const val SHARE_DIR = "share"
    private const val MIME_ZIP = "application/zip"

    fun share(
        context: Context,
        exportAbsolutePath: String,
    ) {
        val source = File(exportAbsolutePath)
        val shareDir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        shareDir.listFiles()?.forEach { it.delete() }
        val copy = File(shareDir, source.name)
        source.copyTo(copy, overwrite = true)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", copy)
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = MIME_ZIP
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
