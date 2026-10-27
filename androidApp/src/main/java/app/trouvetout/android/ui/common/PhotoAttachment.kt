package app.trouvetout.android.ui.common

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A picked or captured image, read once on demand (A10: the core re-encodes it, the source is never kept). */
class PhotoAttachment(
    private val context: Context,
    private val uri: Uri,
    private val temporary: File? = null,
) {
    suspend fun read(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } finally {
            temporary?.delete()
        }
    }
}
