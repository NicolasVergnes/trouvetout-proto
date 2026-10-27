package app.trouvetout.android.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trouvetout.core.platform.AndroidImageCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** The ImageDecoder-based codec on a real Android 10 (lot 1 review): orientation, transparency, garbage. */
@RunWith(AndroidJUnit4::class)
class AndroidImageCodecTest {
    private val codec = AndroidImageCodec()

    @Test
    fun applies_exif_orientation_6_and_drops_metadata() {
        val landscape = jpeg(width = 40, height = 20, color = Color.RED)
        val rotated = withExifOrientation(landscape, orientation = 6)

        val out = checkNotNull(codec.transcodeJpeg(rotated, maxSide = 2048, quality = 85))

        assertEquals("a 90° rotation swaps the sides", 20, out.width)
        assertEquals(40, out.height)
        val decoded = BitmapFactory.decodeByteArray(out.bytes, 0, out.bytes.size)
        assertEquals(20, decoded.width)
        assertTrue("no EXIF segment survives", indexOf(out.bytes, "Exif".toByteArray()) < 0)
    }

    @Test
    fun flattens_png_transparency_on_white() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

        val out = checkNotNull(codec.transcodeJpeg(png, maxSide = 2048, quality = 90))

        val decoded = BitmapFactory.decodeByteArray(out.bytes, 0, out.bytes.size)
        val pixel = decoded.getPixel(8, 8)
        assertTrue("white, not black: ${Integer.toHexString(pixel)}", Color.red(pixel) > 240 && Color.blue(pixel) > 240)
    }

    @Test
    fun downsizes_to_max_side_without_upscaling() {
        val big = jpeg(width = 300, height = 100, color = Color.BLUE)
        val out = checkNotNull(codec.transcodeJpeg(big, maxSide = 150, quality = 80))
        assertEquals(150, out.width)
        assertEquals(50, out.height)

        val small = jpeg(width = 30, height = 10, color = Color.BLUE)
        val same = checkNotNull(codec.transcodeJpeg(small, maxSide = 150, quality = 80))
        assertEquals(30, same.width)
    }

    @Test
    fun returns_null_for_bytes_that_are_not_an_image() {
        assertNull(codec.transcodeJpeg(ByteArray(64) { it.toByte() }, maxSide = 2048, quality = 85))
        assertNull(codec.transcodeJpeg(ByteArray(0), maxSide = 2048, quality = 85))
    }

    private fun jpeg(
        width: Int,
        height: Int,
        color: Int,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
    }

    /** Inserts a minimal APP1/EXIF segment (little-endian TIFF, one IFD0 entry: Orientation) after SOI. */
    private fun withExifOrientation(
        jpeg: ByteArray,
        orientation: Int,
    ): ByteArray {
        val tiff =
            byteArrayOf(
                'I'.code.toByte(),
                'I'.code.toByte(),
                42,
                0,
                8,
                0,
                0,
                0, // header, IFD0 at offset 8
                1,
                0, // one entry
                0x12,
                0x01,
                3,
                0,
                1,
                0,
                0,
                0,
                orientation.toByte(),
                0,
                0,
                0, // tag 0x0112 SHORT x1 = orientation
                0,
                0,
                0,
                0, // no next IFD
            )
        val payload = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII) + tiff
        val length = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun indexOf(
        haystack: ByteArray,
        needle: ByteArray,
    ): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
