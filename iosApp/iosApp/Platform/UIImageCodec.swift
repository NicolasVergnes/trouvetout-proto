import Shared
import UIKit

/// JPEG transcoding with UIKit for the photo store (A10). Decoding and redrawing drops every metadata
/// (EXIF, GPS) and applies the orientation; the long side is bounded to `maxSide` pixels.
final class UIImageCodec: NSDataImageCodec {
    override func transcode(source: Data, maxSide: Int32, quality: Int32) -> NSDataEncodedImage? {
        guard let image = UIImage(data: source), image.size.width > 0, image.size.height > 0 else {
            return nil
        }
        let pixelWidth = image.size.width * image.scale
        let pixelHeight = image.size.height * image.scale
        let ratio = min(1, CGFloat(maxSide) / max(pixelWidth, pixelHeight))
        let target = CGSize(
            width: max(1, (pixelWidth * ratio).rounded(.down)),
            height: max(1, (pixelHeight * ratio).rounded(.down))
        )
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let rendered = UIGraphicsImageRenderer(size: target, format: format).image { context in
            UIColor.white.setFill()
            context.fill(CGRect(origin: .zero, size: target))
            image.draw(in: CGRect(origin: .zero, size: target))
        }
        guard let jpeg = rendered.jpegData(compressionQuality: CGFloat(quality) / 100) else {
            return nil
        }
        return NSDataEncodedImage(data: jpeg, width: Int32(target.width), height: Int32(target.height))
    }
}
