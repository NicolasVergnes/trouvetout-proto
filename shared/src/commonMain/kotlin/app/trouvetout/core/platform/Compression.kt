package app.trouvetout.core.platform

/** Raw DEFLATE (RFC 1951, no zlib/gzip header) of [bytes], as stored in ZIP entries of method 8. */
public expect fun deflateRaw(bytes: ByteArray): ByteArray

/**
 * Inflates a raw DEFLATE stream whose decompressed size is known to be [expectedSize] bytes.
 * Throws [IllegalArgumentException] when the stream is corrupt or does not produce exactly that size
 * (an export entry never lies about its size; a mismatch means a damaged or hostile file).
 */
public expect fun inflateRaw(
    bytes: ByteArray,
    expectedSize: Int,
): ByteArray
