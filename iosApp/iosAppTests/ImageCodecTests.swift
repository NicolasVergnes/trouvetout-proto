@testable import iosApp
import Shared
import UIKit
import XCTest

final class ImageCodecTests: XCTestCase {
    private func pngData(width: CGFloat, height: CGFloat) -> Data {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format).image { context in
            UIColor.systemTeal.setFill()
            context.fill(CGRect(x: 0, y: 0, width: width, height: height))
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
        let encoded = try XCTUnwrap(UIImageCodec().transcode(source: pngData(width: 300, height: 200), maxSide: 2048, quality: 85))
        XCTAssertEqual(encoded.width, 300)
        XCTAssertEqual(encoded.height, 200)
    }

    func testUndecodableInputGivesNil() {
        XCTAssertNil(UIImageCodec().transcode(source: Data([0x00, 0x01, 0x02]), maxSide: 2048, quality: 85))
    }
}
