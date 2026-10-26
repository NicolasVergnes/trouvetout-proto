package app.trouvetout.core.platform

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JvmImageCodecTest {
    private val codec = JvmImageCodec()

    private fun png(
        width: Int,
        height: Int,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply {
            color = Color.ORANGE
            fillRect(0, 0, width, height)
            dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    @Test
    fun should_bound_the_long_side_and_emit_jpeg() {
        val encoded = assertNotNull(codec.transcodeJpeg(png(3000, 1500), maxSide = 2048, quality = 85))
        assertEquals(2048, encoded.width)
        assertEquals(1024, encoded.height)
        assertEquals(listOf(0xFF, 0xD8, 0xFF), encoded.bytes.take(3).map { it.toInt() and 0xFF })
        val reread = assertNotNull(ImageIO.read(ByteArrayInputStream(encoded.bytes)))
        assertEquals(2048, reread.width)
    }

    @Test
    fun should_not_upscale_and_should_reject_garbage() {
        val small = assertNotNull(codec.transcodeJpeg(png(300, 200), maxSide = 2048, quality = 85))
        assertEquals(300, small.width)
        assertEquals(200, small.height)
        assertNull(codec.transcodeJpeg(byteArrayOf(0, 1, 2, 3), maxSide = 2048, quality = 85))
    }

    /** Inserts a fake Exif APP1 segment (as a camera would) right after the SOI marker. */
    private fun withExifSegment(jpeg: ByteArray): ByteArray {
        val payload = "Exif\u0000\u0000GPSLatitude=48.8566".encodeToByteArray()
        val length = payload.size + 2
        val app1 =
            byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), (length and 0xFF).toByte()) + payload
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun app1Segments(jpeg: ByteArray): Int {
        var count = 0
        var i = 2
        while (i + 3 < jpeg.size && jpeg[i] == 0xFF.toByte()) {
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xDA) break // start of scan: no more headers
            if (marker == 0xE1) count++
            val len = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            i += 2 + len
        }
        return count
    }

    @Test
    fun should_drop_the_exif_segment_of_the_source() {
        val original = assertNotNull(codec.transcodeJpeg(png(400, 300), maxSide = 2048, quality = 85)).bytes
        val tagged = withExifSegment(original)
        assertEquals(1, app1Segments(tagged), "fixture carries an APP1 segment")
        assertNotNull(ImageIO.read(ByteArrayInputStream(tagged)), "fixture still decodes")

        val cleaned = assertNotNull(codec.transcodeJpeg(tagged, maxSide = 2048, quality = 85)).bytes

        assertEquals(0, app1Segments(cleaned), "no APP1/Exif segment in the stored photo")
    }
}
