import ImageIO
import Shared
import UIKit
import UniformTypeIdentifiers

/// JPEG transcoding for the photo store (A10), built on ImageIO.
/// The source is decoded *subsampled* to at most `maxSide` pixels (`CGImageSourceCreateThumbnailAtIndex`), so a
/// 100-megapixel photo or a decompression bomb never materialises at full size on a 2 GB phone. Redrawing on an
/// opaque white canvas applies the orientation and drops every metadata (EXIF, GPS) by construction.
final class UIImageCodec: NSDataImageCodec {
    override func transcode(source: Data, maxSide: Int32, quality: Int32) -> NSDataEncodedImage? {
        let sourceOptions: [CFString: Any] = [kCGImageSourceShouldCache: false]
        guard let imageSource = CGImageSourceCreateWithData(source as CFData, sourceOptions as CFDictionary),
              CGImageSourceGetCount(imageSource) > 0
        else {
            return nil
        }
        let thumbnailOptions: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true, // applies the EXIF orientation
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: Int(maxSide)
        ]
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(imageSource, 0, thumbnailOptions as CFDictionary) else {
            return nil
        }
        let size = CGSize(width: cgImage.width, height: cgImage.height)
        guard size.width >= 1, size.height >= 1 else {
            return nil
        }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let rendered = UIGraphicsImageRenderer(size: size, format: format).image { context in
            UIColor.white.setFill()
            context.fill(CGRect(origin: .zero, size: size))
            UIImage(cgImage: cgImage).draw(in: CGRect(origin: .zero, size: size))
        }
        guard let jpeg = rendered.jpegData(compressionQuality: CGFloat(quality) / 100) else {
            return nil
        }
        return NSDataEncodedImage(data: jpeg, width: Int32(size.width), height: Int32(size.height))
    }
}
