@testable import iosApp
import ImageIO
import Shared
import UIKit
import UniformTypeIdentifiers
import XCTest

final class ImageCodecTests: XCTestCase {
    private func pngData(width: CGFloat, height: CGFloat) -> Data {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let size = CGSize(width: width, height: height)
        let image = UIGraphicsImageRenderer(size: size, format: format).image { context in
            UIColor.systemTeal.setFill()
            context.fill(CGRect(origin: .zero, size: size))
        }
        return image.pngData() ?? Data()
    }

    func testTranscodeBoundsTheLongSideAndProducesJpeg() throws {
        let source = pngData(width: 3000, height: 1500)

        let encoded = try XCTUnwrap(UIImageCodec().transcode(source: source, maxSide: 2048, quality: 85))

        XCTAssertEqual(encoded.width, 2048)
        XCTAssertEqual(encoded.height, 1024)
        let bytes = [UInt8](encoded.data.prefix(3))
        XCTAssertEqual(bytes, [0xFF, 0xD8, 0xFF], "JPEG magic bytes")
        let reread = try XCTUnwrap(UIImage(data: encoded.data))
        XCTAssertEqual(reread.size.width * reread.scale, 2048)
    }

    func testSmallImagesAreNotUpscaled() throws {
        let source = pngData(width: 300, height: 200)
        let encoded = try XCTUnwrap(UIImageCodec().transcode(source: source, maxSide: 2048, quality: 85))
        XCTAssertEqual(encoded.width, 300)
        XCTAssertEqual(encoded.height, 200)
    }

    /// A JPEG as a phone camera writes it: GPS position, camera make and model, orientation 6 (rotated).
    private func cameraJpeg(width: CGFloat, height: CGFloat) throws -> Data {
        let pixels = try XCTUnwrap(UIImage(data: pngData(width: width, height: height))?.cgImage)
        let output = NSMutableData()
        let destination = try XCTUnwrap(
            CGImageDestinationCreateWithData(output, UTType.jpeg.identifier as CFString, 1, nil)
        )
        let properties: [CFString: Any] = [
            kCGImagePropertyOrientation: 6,
            kCGImagePropertyGPSDictionary: [
                kCGImagePropertyGPSLatitude: 48.8566,
                kCGImagePropertyGPSLatitudeRef: "N",
                kCGImagePropertyGPSLongitude: 2.3522,
                kCGImagePropertyGPSLongitudeRef: "E",
            ],
            kCGImagePropertyTIFFDictionary: [
                kCGImagePropertyTIFFMake: "TestMaker",
                kCGImagePropertyTIFFModel: "TestPhone",
            ],
            kCGImagePropertyExifDictionary: [kCGImagePropertyExifUserComment: "secret note"],
        ]
        CGImageDestinationAddImage(destination, pixels, properties as CFDictionary)
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        return output as Data
    }

    /// Lot 8 (dette des lots 4 et 7): the stored photo, and the one the model reads, carry no position nor camera
    /// metadata, and the orientation is applied to the pixels.
    func testTranscodeDropsGpsAndCameraMetadataAndAppliesTheOrientation() throws {
        let source = try cameraJpeg(width: 400, height: 200)
        let sourceImage = try XCTUnwrap(CGImageSourceCreateWithData(source as CFData, nil))
        let sourceProperties = try XCTUnwrap(
            CGImageSourceCopyPropertiesAtIndex(sourceImage, 0, nil) as? [CFString: Any]
        )
        XCTAssertNotNil(sourceProperties[kCGImagePropertyGPSDictionary], "the fixture does carry a position")

        let encoded = try XCTUnwrap(UIImageCodec().transcode(source: source, maxSide: 2048, quality: 85))

        XCTAssertEqual(encoded.width, 200, "orientation 6 applied: the image is turned")
        XCTAssertEqual(encoded.height, 400)
        let image = try XCTUnwrap(CGImageSourceCreateWithData(encoded.data as CFData, nil))
        let properties = try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(image, 0, nil) as? [CFString: Any])
        XCTAssertNil(properties[kCGImagePropertyGPSDictionary], "no GPS position")
        let tiff = properties[kCGImagePropertyTIFFDictionary] as? [CFString: Any]
        XCTAssertNil(tiff?[kCGImagePropertyTIFFMake], "no camera make")
        XCTAssertNil(tiff?[kCGImagePropertyTIFFModel], "no camera model")
        let exif = properties[kCGImagePropertyExifDictionary] as? [CFString: Any]
        XCTAssertNil(exif?[kCGImagePropertyExifUserComment], "no EXIF comment")
        let orientation = properties[kCGImagePropertyOrientation] as? Int
        XCTAssertTrue(orientation == nil || orientation == 1, "no rotation left for a reader to apply")
        XCTAssertFalse(String(decoding: encoded.data, as: UTF8.self).contains("secret note"))
    }

    func testUndecodableInputGivesNil() {
        XCTAssertNil(UIImageCodec().transcode(source: Data([0x00, 0x01, 0x02]), maxSide: 2048, quality: 85))
    }
}
