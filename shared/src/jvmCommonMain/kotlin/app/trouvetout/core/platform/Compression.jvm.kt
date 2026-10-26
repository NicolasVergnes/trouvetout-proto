package app.trouvetout.core.platform

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

private const val EXPECTED_RATIO = 2
private const val HEADROOM = 64

public actual fun deflateRaw(bytes: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
    try {
        deflater.setInput(bytes)
        deflater.finish()
        val out = ByteArrayOutputStream(bytes.size / EXPECTED_RATIO + HEADROOM)
        val buffer = ByteArray(BUFFER)
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer))
        }
        return out.toByteArray()
    } finally {
        deflater.end()
    }
}

public actual fun inflateRaw(
    bytes: ByteArray,
    expectedSize: Int,
): ByteArray {
    require(expectedSize >= 0) { "Negative size" }
    val inflater = Inflater(true)
    try {
        inflater.setInput(bytes)
        // One spare byte: a stream longer than declared is detected without reading it whole.
        val out = ByteArray(expectedSize + 1)
        var produced = 0
        while (!inflater.finished() && produced < out.size) {
            val n =
                try {
                    inflater.inflate(out, produced, out.size - produced)
                } catch (e: DataFormatException) {
                    throw IllegalArgumentException("Corrupt deflate stream", e)
                }
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            produced += n
        }
        require(inflater.finished() && produced == expectedSize) { "Deflate size mismatch" }
        return out.copyOf(expectedSize)
    } finally {
        inflater.end()
    }
}

private const val BUFFER = 16 * 1024
