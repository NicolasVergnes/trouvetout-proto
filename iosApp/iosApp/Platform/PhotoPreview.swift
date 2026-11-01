import UIKit

/// A small preview of a photo the user just picked (capture B8, search C10). Decoded subsampled by the store's
/// codec, off the main thread: an image with huge dimensions in a small file never materialises at full size.
enum PhotoPreview {
    /// Nil when the bytes are not an image the platform decodes.
    static func make(from data: Data, maxSide: Int32 = 320) async -> UIImage? {
        let jpeg: Data? = await Task.detached(priority: .userInitiated) {
            UIImageCodec().transcode(source: data, maxSide: maxSide, quality: 80)?.data
        }.value
        return jpeg.flatMap { UIImage(data: $0) }
    }
}
