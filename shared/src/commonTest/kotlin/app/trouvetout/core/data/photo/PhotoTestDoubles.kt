package app.trouvetout.core.data.photo

import app.trouvetout.core.platform.EncodedImage
import app.trouvetout.core.platform.FileStore
import app.trouvetout.core.platform.ImageCodec
import app.trouvetout.core.platform.isListableName
import app.trouvetout.core.platform.requireSafeRelativePath
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Treats the source bytes as a `"WxH"` description and scales it like a real codec would. */
class FakeImageCodec : ImageCodec {
    override fun transcodeJpeg(
        source: ByteArray,
        maxSide: Int,
        quality: Int,
    ): EncodedImage? {
        val text = source.decodeToString()
        val (w, h) =
            text
                .removePrefix("jpeg:")
                .substringBefore(":")
                .split("x")
                .takeIf { it.size == 2 }
                ?.map { it.toIntOrNull() ?: return null }
                ?: return null
        val scale = min(1.0, maxSide.toDouble() / max(w, h))
        val width = max(1, (w * scale).roundToInt())
        val height = max(1, (h * scale).roundToInt())
        return EncodedImage("jpeg:${width}x$height:q$quality".encodeToByteArray(), width, height)
    }
}

class InMemoryFileStore : FileStore {
    val files = LinkedHashMap<String, ByteArray>()
    override val rootDirectory: String = "/mem"

    /** When set, writes whose path contains this text throw (simulates a disk failure). */
    var failWritesContaining: String? = null

    override fun absolutePath(relativePath: String): String {
        requireSafeRelativePath(relativePath)
        return "$rootDirectory/$relativePath"
    }

    override fun write(
        relativePath: String,
        bytes: ByteArray,
    ) {
        requireSafeRelativePath(relativePath)
        failWritesContaining?.let { check(it !in relativePath) { "write failed" } }
        files[relativePath] = bytes.copyOf()
    }

    override fun read(relativePath: String): ByteArray? = files[relativePath]?.copyOf()

    override fun delete(relativePath: String): Boolean = files.remove(relativePath) != null

    override fun exists(relativePath: String): Boolean = relativePath in files

    override fun list(relativeDirectory: String): List<String> = files.keys
        .filter { it.startsWith("$relativeDirectory/") }
        .map { it.removePrefix("$relativeDirectory/") }
        .filter { '/' !in it && isListableName(it) }
        .sorted()
}
