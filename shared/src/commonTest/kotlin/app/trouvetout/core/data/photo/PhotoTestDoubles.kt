package app.trouvetout.core.data.photo

import app.trouvetout.core.platform.ByteSink
import app.trouvetout.core.platform.EncodedImage
import app.trouvetout.core.platform.FileStore
import app.trouvetout.core.platform.ImageCodec
import app.trouvetout.core.platform.RandomAccessSource
import app.trouvetout.core.platform.isListableName
import app.trouvetout.core.platform.requireSafeRelativePath
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Treats the source bytes as a `"WxH"` description (optionally `"jpeg:WxH:qN"`) and scales it like a real codec. */
class FakeImageCodec : ImageCodec {
    override fun transcodeJpeg(
        source: ByteArray,
        maxSide: Int,
        quality: Int,
    ): EncodedImage? {
        val text = source.decodeToString()
        val dims = text.removePrefix("jpeg:").substringBefore(":").split("x")
        val w = dims.getOrNull(0)?.toIntOrNull()
        val h = dims.getOrNull(1)?.toIntOrNull()
        if (dims.size != 2 || w == null || h == null) return null
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

    override fun read(relativePath: String): ByteArray? {
        requireSafeRelativePath(relativePath)
        return files[relativePath]?.copyOf()
    }

    override fun delete(relativePath: String): Boolean {
        requireSafeRelativePath(relativePath)
        return files.remove(relativePath) != null
    }

    override fun exists(relativePath: String): Boolean {
        requireSafeRelativePath(relativePath)
        return relativePath in files
    }

    override fun list(relativeDirectory: String): List<String> = files.keys
        .filter { it.startsWith("$relativeDirectory/") }
        .map { it.removePrefix("$relativeDirectory/") }
        .filter { '/' !in it && isListableName(it) }
        .sorted()

    override fun sink(relativePath: String): ByteSink {
        requireSafeRelativePath(relativePath)
        return object : ByteSink {
            private var buffer = ByteArray(0)
            override var position: Long = 0

            override fun write(
                bytes: ByteArray,
                offset: Int,
                length: Int,
            ) {
                buffer += bytes.copyOfRange(offset, offset + length)
                position += length
            }

            override fun close() {
                files[relativePath] = buffer
            }
        }
    }

    override fun source(relativePath: String): RandomAccessSource? {
        requireSafeRelativePath(relativePath)
        val data = files[relativePath] ?: return null
        return object : RandomAccessSource {
            override val size: Long = data.size.toLong()

            override fun read(
                offset: Long,
                length: Int,
            ): ByteArray {
                require(offset >= 0 && length >= 0 && offset + length <= size) { "Read out of range" }
                return data.copyOfRange(offset.toInt(), offset.toInt() + length)
            }

            override fun close() = Unit
        }
    }

    override fun deleteDirectory(relativeDirectory: String): Boolean {
        requireSafeRelativePath(relativeDirectory)
        val keys = files.keys.filter { it.startsWith("$relativeDirectory/") }
        keys.forEach { files.remove(it) }
        return keys.isNotEmpty()
    }
}
