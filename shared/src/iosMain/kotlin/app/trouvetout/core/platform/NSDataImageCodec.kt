package app.trouvetout.core.platform

import platform.Foundation.NSData

/** Result of [NSDataImageCodec.transcode], in Foundation terms. */
public class NSDataEncodedImage(
    public val data: NSData,
    public val width: Int,
    public val height: Int,
)

/**
 * Adapter for the Swift implementation of [ImageCodec]: Swift works with `Data`, the byte copies stay here.
 * `iosApp/Platform/UIImageCodec.swift` subclasses it.
 */
public abstract class NSDataImageCodec : ImageCodec {
    public abstract fun transcode(
        source: NSData,
        maxSide: Int,
        quality: Int,
    ): NSDataEncodedImage?

    final override fun transcodeJpeg(
        source: ByteArray,
        maxSide: Int,
        quality: Int,
    ): EncodedImage? = transcode(source.toNSData(), maxSide, quality)?.let {
        EncodedImage(it.data.toByteArray(), it.width, it.height)
    }
}
