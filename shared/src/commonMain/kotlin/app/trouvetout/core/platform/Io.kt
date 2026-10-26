package app.trouvetout.core.platform

/** Sequential writer to a file; [close] makes the content visible atomically (temp file + rename). */
public interface ByteSink {
    public fun write(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    )

    /** Bytes written so far. */
    public val position: Long

    public fun close()
}

/** Random-access reader of a file (ZIP archives are read from their end). */
public interface RandomAccessSource {
    public val size: Long

    /** Exactly [length] bytes at [offset]; throws [IllegalArgumentException] when out of range. */
    public fun read(
        offset: Long,
        length: Int,
    ): ByteArray

    public fun close()
}

public inline fun <T> ByteSink.use(block: (ByteSink) -> T): T {
    var ok = false
    try {
        return block(this).also { ok = true }
    } finally {
        if (ok) close() else runCatching { close() }
    }
}

public inline fun <T> RandomAccessSource.use(block: (RandomAccessSource) -> T): T = try {
    block(this)
} finally {
    close()
}
