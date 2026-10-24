package app.trouvetout.core.data.photo

import app.trouvetout.core.domain.usecase.PhotoFile
import app.trouvetout.core.platform.FileStore
import app.trouvetout.core.platform.ImageCodec
import app.trouvetout.core.platform.sha256

/**
 * Photo files (A10): every photo is re-encoded (no metadata), bounded to [MAX_SIDE] pixels, stored with a
 * [THUMB_SIDE] thumbnail under `photos/` in the private data directory, named by a ULID, hashed with SHA-256.
 * Attaching the result to a record is a separate command (`AttachmentCommands.attachPhoto`).
 */
public class PhotoStore internal constructor(
    private val files: FileStore,
    private val codec: ImageCodec,
    private val newId: () -> String,
) {
    /** Null when the bytes are not a decodable image; nothing is written in that case. */
    public fun store(source: ByteArray): PhotoFile? {
        val full = codec.transcodeJpeg(source, MAX_SIDE, QUALITY) ?: return null
        val thumb = codec.transcodeJpeg(full.bytes, THUMB_SIDE, THUMB_QUALITY) ?: return null
        val id = newId()
        val fileName = "$id.jpg"
        val thumbName = "${id}_t.jpg"
        files.write("$DIRECTORY/$fileName", full.bytes)
        files.write("$DIRECTORY/$thumbName", thumb.bytes)
        return PhotoFile(fileName, thumbName, full.width, full.height, sha256(full.bytes).toHexString())
    }

    public fun absolutePath(fileName: String): String = files.absolutePath("$DIRECTORY/$fileName")

    public fun read(fileName: String): ByteArray? = files.read("$DIRECTORY/$fileName")

    public fun exists(fileName: String): Boolean = files.exists("$DIRECTORY/$fileName")

    public fun delete(fileName: String): Boolean = files.delete("$DIRECTORY/$fileName")

    public fun listFiles(): List<String> = files.list(DIRECTORY)

    public companion object {
        public const val DIRECTORY: String = "photos"
        public const val MAX_SIDE: Int = 2048
        public const val THUMB_SIDE: Int = 256
        public const val QUALITY: Int = 85
        public const val THUMB_QUALITY: Int = 80
    }
}
