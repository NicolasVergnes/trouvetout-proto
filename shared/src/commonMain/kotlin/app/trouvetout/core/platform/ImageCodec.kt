package app.trouvetout.core.platform

/** A JPEG produced by [ImageCodec]: bytes and pixel dimensions. */
public class EncodedImage(
    public val bytes: ByteArray,
    public val width: Int,
    public val height: Int,
)

/**
 * Platform image transcoding for the photo store (A10). Implementations decode the source (JPEG, PNG, HEIC…),
 * apply its orientation, scale it so the long side is at most [maxSide] pixels and re-encode a JPEG at
 * [quality] (0–100). Re-encoding drops every metadata (EXIF, GPS) by construction.
 */
public interface ImageCodec {
    /** Null when the bytes are not a decodable image. */
    public fun transcodeJpeg(
        source: ByteArray,
        maxSide: Int,
        quality: Int,
    ): EncodedImage?
}
