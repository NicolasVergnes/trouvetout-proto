package app.trouvetout.core.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ImageDecoder-based transcoding (API 28+, so unconditional at minSdk 29): the image is decoded directly at
 * the target size (no full-resolution bitmap for a 100-megapixel source), the EXIF orientation is applied by
 * the decoder, HEIC and the first frame of animated formats are handled, every other metadata is dropped and
 * transparency is flattened on white (same result as the iOS and JVM codecs).
 */
public class AndroidImageCodec : ImageCodec {
    override fun transcodeJpeg(
        source: ByteArray,
        maxSide: Int,
        quality: Int,
    ): EncodedImage? {
        val decoded = decode(source, maxSide) ?: return null
        val flattened = flattenOnWhite(decoded)
        val out = ByteArrayOutputStream()
        val written = flattened.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(0, MAX_QUALITY), out)
        return if (written) EncodedImage(out.toByteArray(), flattened.width, flattened.height) else null
    }

    private fun decode(
        source: ByteArray,
        maxSide: Int,
    ): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(source))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            // info.size is the oriented size; keep the aspect ratio, never upscale.
            val scale = min(1.0, maxSide.toDouble() / max(info.size.width, info.size.height))
            if (scale < 1.0) {
                decoder.setTargetSize(
                    max(1, (info.size.width * scale).roundToInt()),
                    max(1, (info.size.height * scale).roundToInt()),
                )
            }
        }
    } catch (_: IOException) {
        // ImageDecoder.DecodeException (not an image, unsupported codec such as 10-bit HEIC on API 29).
        null
    }

    private fun flattenOnWhite(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val opaque = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(opaque).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        bitmap.recycle()
        return opaque
    }

    private companion object {
        const val MAX_QUALITY = 100
    }
}
