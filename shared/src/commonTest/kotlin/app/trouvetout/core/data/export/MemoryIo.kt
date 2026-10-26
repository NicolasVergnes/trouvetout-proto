package app.trouvetout.core.data.export

import app.trouvetout.core.platform.ByteSink
import app.trouvetout.core.platform.RandomAccessSource

/** In-memory sink/source pair for ZIP tests. */
class MemorySink : ByteSink {
    private var buffer = ByteArray(4096)
    override var position: Long = 0
        private set
    var closed: Boolean = false
        private set

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        check(!closed)
        val end = position.toInt() + length
        if (end > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, end))
        bytes.copyInto(buffer, position.toInt(), offset, offset + length)
        position = end.toLong()
    }

    override fun close() {
        closed = true
    }

    fun toByteArray(): ByteArray = buffer.copyOf(position.toInt())
}

class MemorySource(
    private val data: ByteArray,
) : RandomAccessSource {
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

fun zipOf(vararg entries: Triple<String, ByteArray, Boolean>): ByteArray {
    val sink = MemorySink()
    val writer = ZipWriter(sink)
    for ((name, bytes, store) in entries) writer.add(name, bytes, store)
    writer.finish()
    sink.close()
    return sink.toByteArray()
}

fun entry(
    name: String,
    bytes: ByteArray,
    store: Boolean = false,
) = Triple(name, bytes, store)
