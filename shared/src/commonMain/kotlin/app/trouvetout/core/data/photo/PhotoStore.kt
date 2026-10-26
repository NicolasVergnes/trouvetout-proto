package app.trouvetout.core.data.photo

import app.trouvetout.core.domain.usecase.PhotoFile
import app.trouvetout.core.platform.FileStore
import app.trouvetout.core.platform.ImageCodec
import app.trouvetout.core.platform.sha256
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Photo files (A10): every photo is re-encoded (no metadata), bounded to [MAX_SIDE] pixels, stored with a
 * [THUMB_SIDE] thumbnail (made from the source, not from the already compressed copy) under `photos/` in the
 * private data directory, named by a ULID, hashed with SHA-256. Encoding runs on [dispatcher], never on the
 * caller's thread. Attaching the result to a record is a separate command (`AttachmentCommands.attachPhoto`).
 */
public class PhotoStore internal constructor(
    private val files: FileStore,
    private val codec: ImageCodec,
    private val newId: suspend () -> String,
    private val dispatcher: CoroutineDispatcher,
) {
    /** Null when the bytes are not a decodable image; nothing is left on disk unless both files were written. */
    public suspend fun store(source: ByteArray): PhotoFile? = withContext(dispatcher) {
        val full = codec.transcodeJpeg(source, MAX_SIDE, QUALITY) ?: return@withContext null
        val thumb = codec.transcodeJpeg(source, THUMB_SIDE, THUMB_QUALITY) ?: return@withContext null
        val id = newId()
        val fileName = "$id.jpg"
        val thumbName = "${id}_t.jpg"
        files.write("$DIRECTORY/$fileName", full.bytes)
        val thumbWrite = runCatching { files.write("$DIRECTORY/$thumbName", thumb.bytes) }
        if (thumbWrite.isFailure) files.delete("$DIRECTORY/$fileName")
        thumbWrite.getOrThrow()
        PhotoFile(fileName, thumbName, full.width, full.height, sha256(full.bytes).toHexString())
    }

    public fun absolutePath(fileName: String): String = files.absolutePath(path(fileName))

    public suspend fun read(fileName: String): ByteArray? = withContext(dispatcher) { files.read(path(fileName)) }

    public fun exists(fileName: String): Boolean = files.exists(path(fileName))

    public suspend fun delete(fileName: String): Boolean = withContext(dispatcher) { files.delete(path(fileName)) }

    public fun listFiles(): List<String> = files.list(DIRECTORY)

    /** Only names this store generates are accepted (an imported `PhotoAttached` cannot point elsewhere). */
    private fun path(fileName: String): String {
        require(isValidFileName(fileName)) { "Not a photo file name" }
        return "$DIRECTORY/$fileName"
    }

    public companion object {
        public const val DIRECTORY: String = "photos"
        private val FILE_NAME: Regex = Regex("^[0-9A-HJKMNP-TV-Z]{26}(_t)?\\.jpg$")

        /** `<ULID>.jpg` or `<ULID>_t.jpg`. */
        public fun isValidFileName(fileName: String): Boolean = FILE_NAME.matches(fileName)

        public const val MAX_SIDE: Int = 2048
        public const val THUMB_SIDE: Int = 256
        public const val QUALITY: Int = 85
        public const val THUMB_QUALITY: Int = 80
    }
}
