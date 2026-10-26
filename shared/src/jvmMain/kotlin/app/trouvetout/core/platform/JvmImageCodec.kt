package app.trouvetout.core.platform

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** ImageIO-based transcoding for the evaluation tools and JVM tests (EXIF orientation is not applied here). */
public class JvmImageCodec : ImageCodec {
    override fun transcodeJpeg(
        source: ByteArray,
        maxSide: Int,
        quality: Int,
    ): EncodedImage? {
        val image = runCatching { ImageIO.read(ByteArrayInputStream(source)) }.getOrNull() ?: return null
        val scale = min(1.0, maxSide.toDouble() / max(image.width, image.height))
        val width = max(1, (image.width * scale).roundToInt())
        val height = max(1, (image.height * scale).roundToInt())
        val rgb = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply {
            color = Color.WHITE
            fillRect(0, 0, width, height)
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(image, 0, 0, width, height, null)
            dispose()
        }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val params =
            writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality.coerceIn(0, MAX_QUALITY) / MAX_QUALITY.toFloat()
            }
        val out = ByteArrayOutputStream()
        MemoryCacheImageOutputStream(out).use { stream ->
            writer.output = stream
            writer.write(null, IIOImage(rgb, null, null), params)
        }
        writer.dispose()
        return EncodedImage(out.toByteArray(), width, height)
    }

    private companion object {
        const val MAX_QUALITY = 100
    }
}
