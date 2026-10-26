package app.trouvetout.core.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.zlib.MAX_MEM_LEVEL
import platform.zlib.ZLIB_VERSION
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2_
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream

/** Raw deflate: negative window bits tell zlib to omit the zlib header and trailer. */
private const val RAW_WINDOW_BITS = -15

@OptIn(ExperimentalForeignApi::class)
public actual fun deflateRaw(bytes: ByteArray): ByteArray = memScoped {
    val stream = alloc<z_stream>()
    val init =
        deflateInit2_(
            stream.ptr,
            Z_DEFAULT_COMPRESSION,
            Z_DEFLATED,
            RAW_WINDOW_BITS,
            MAX_MEM_LEVEL,
            Z_DEFAULT_STRATEGY,
            ZLIB_VERSION,
            kotlinx.cinterop.sizeOf<z_stream>().toInt(),
        )
    check(init == Z_OK) { "deflateInit failed" }
    try {
        val chunks = ArrayList<ByteArray>()
        val outBuffer = ByteArray(BUFFER)
        val input = if (bytes.isEmpty()) ByteArray(1) else bytes
        input.usePinned { pinnedIn ->
            stream.next_in = pinnedIn.addressOf(0).reinterpret()
            stream.avail_in = bytes.size.convert()
            var status = Z_OK
            do {
                val produced =
                    outBuffer.usePinned { pinnedOut ->
                        stream.next_out = pinnedOut.addressOf(0).reinterpret()
                        stream.avail_out = BUFFER.convert()
                        status = deflate(stream.ptr, Z_FINISH)
                        BUFFER - stream.avail_out.toInt()
                    }
                if (produced > 0) chunks += outBuffer.copyOf(produced)
            } while (status == Z_OK || status == Z_BUF_ERROR && stream.avail_out.toInt() == 0)
            check(status == Z_STREAM_END) { "deflate failed" }
        }
        concat(chunks)
    } finally {
        deflateEnd(stream.ptr)
    }
}

@OptIn(ExperimentalForeignApi::class)
public actual fun inflateRaw(
    bytes: ByteArray,
    expectedSize: Int,
): ByteArray {
    require(expectedSize >= 0) { "Negative size" }
    return memScoped {
        val stream = alloc<z_stream>()
        val init = inflateInit2_(stream.ptr, RAW_WINDOW_BITS, ZLIB_VERSION, kotlinx.cinterop.sizeOf<z_stream>().toInt())
        check(init == Z_OK) { "inflateInit failed" }
        try {
            val out = ByteArray(expectedSize + 1) // one spare byte to detect a stream longer than declared
            val input = if (bytes.isEmpty()) ByteArray(1) else bytes
            val status =
                input.usePinned { pinnedIn ->
                    out.usePinned { pinnedOut ->
                        stream.next_in = pinnedIn.addressOf(0).reinterpret()
                        stream.avail_in = bytes.size.convert()
                        stream.next_out = pinnedOut.addressOf(0).reinterpret()
                        stream.avail_out = out.size.convert()
                        inflate(stream.ptr, Z_FINISH)
                    }
                }
            val produced = out.size - stream.avail_out.toInt()
            require(status == Z_STREAM_END && produced == expectedSize) { "Deflate size mismatch" }
            out.copyOf(expectedSize)
        } finally {
            inflateEnd(stream.ptr)
        }
    }
}

private fun concat(chunks: List<ByteArray>): ByteArray {
    val total = chunks.sumOf { it.size }
    val result = ByteArray(total)
    var offset = 0
    for (chunk in chunks) {
        chunk.copyInto(result, offset)
        offset += chunk.size
    }
    return result
}

private const val BUFFER = 16 * 1024

@Suppress("unused")
private val keepNoFlush = Z_NO_FLUSH
