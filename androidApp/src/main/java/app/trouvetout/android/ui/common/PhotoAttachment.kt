package app.trouvetout.android.ui.common

import android.content.Context
import android.net.Uri
import app.trouvetout.core.data.photo.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** What reading a picked image gave. */
sealed interface PhotoBytes {
    class Ok(
        val bytes: ByteArray,
    ) : PhotoBytes

    data object TooLarge : PhotoBytes

    data object Unreadable : PhotoBytes
}

/**
 * A picked or captured image, read once on demand (A10: the core re-encodes it, the source is never kept).
 * The read is bounded by [PhotoStore.MAX_SOURCE_BYTES] before anything is loaded in memory.
 */
class PhotoAttachment(
    context: Context,
    private val uri: Uri,
    private val temporary: File? = null,
) {
    private val appContext = context.applicationContext

    suspend fun read(): PhotoBytes = withContext(Dispatchers.IO) {
        try {
            val resolver = appContext.contentResolver
            val declared = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull()
            if (declared != null && declared > PhotoStore.MAX_SOURCE_BYTES) return@withContext PhotoBytes.TooLarge
            val bytes = resolver.openInputStream(uri)?.use { it.readUpTo(PhotoStore.MAX_SOURCE_BYTES + 1) }
            when {
                bytes == null -> PhotoBytes.Unreadable
                bytes.size > PhotoStore.MAX_SOURCE_BYTES -> PhotoBytes.TooLarge
                else -> PhotoBytes.Ok(bytes)
            }
        } catch (_: IOException) {
            PhotoBytes.Unreadable
        } catch (_: SecurityException) {
            PhotoBytes.Unreadable
        } finally {
            temporary?.delete()
        }
    }
}

/** Reads at most [limit] bytes (API 29 has no `readNBytes`). */
private fun java.io.InputStream.readUpTo(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(READ_CHUNK)
    var remaining = limit
    while (remaining > 0) {
        val read = read(buffer, 0, minOf(buffer.size, remaining))
        if (read < 0) break
        out.write(buffer, 0, read)
        remaining -= read
    }
    return out.toByteArray()
}

private const val READ_CHUNK = 64 * 1024
