package app.trouvetout.core.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** BitmapFactory-based transcoding; the EXIF orientation is applied, every other metadata is dropped. */
public class AndroidImageCodec : ImageCodec {
    override fun transcodeJpeg(
        source: ByteArray,
        maxSide: Int,
        quality: Int,
    ): EncodedImage? {
        val oriented = decode(source, maxSide) ?: return null
        val scaled = scaleDown(oriented, maxSide)
        val out = ByteArrayOutputStream()
        val written = scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(0, MAX_QUALITY), out)
        return if (written) EncodedImage(out.toByteArray(), scaled.width, scaled.height) else null
    }

    /** Decodes with a power-of-two subsampling close to [maxSide], then applies the EXIF orientation. */
    private fun decode(
        source: ByteArray,
        maxSide: Int,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize =
                sampleSize(max(bounds.outWidth, bounds.outHeight), maxSide)
        }
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size, options)
        return decoded?.let { applyOrientation(it, orientationOf(source)) }
    }

    private fun scaleDown(
        bitmap: Bitmap,
        maxSide: Int,
    ): Bitmap {
        val scale = min(1f, maxSide.toFloat() / max(bitmap.width, bitmap.height))
        if (scale >= 1f) return bitmap
        return Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * scale).roundToInt()),
            max(1, (bitmap.height * scale).roundToInt()),
            true,
        )
    }

    /** Largest power of two keeping the decoded long side at or above [maxSide] (then scaled precisely). */
    private fun sampleSize(
        longSide: Int,
        maxSide: Int,
    ): Int {
        var sample = 1
        while (longSide / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    private fun orientationOf(source: ByteArray): Int = runCatching {
        ExifInterface(
            ByteArrayInputStream(source),
        ).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun applyOrientation(
        bitmap: Bitmap,
        orientation: Int,
    ): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(QUARTER_TURN)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(HALF_TURN)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(THREE_QUARTER_TURN)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(QUARTER_TURN)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(THREE_QUARTER_TURN)
                matrix.preScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private companion object {
        const val MAX_QUALITY = 100
        const val QUARTER_TURN = 90f
        const val HALF_TURN = 180f
        const val THREE_QUARTER_TURN = 270f
    }
}
