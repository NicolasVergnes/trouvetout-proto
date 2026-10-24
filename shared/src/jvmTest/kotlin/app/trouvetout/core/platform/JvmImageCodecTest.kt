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
}
