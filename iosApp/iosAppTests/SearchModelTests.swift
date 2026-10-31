@testable import iosApp
import Shared
import UIKit
import XCTest

/// Lots 6 and 7 on the device side: the bundled EmbeddingGemma 2 files load with LiteRT-LM, the text vectors carry
/// meaning, the text+vision file puts a photo in the same space and the label catalog is bundled.
final class SearchModelTests: XCTestCase {
    func testTheBundledModelEmbedsOnTheDevice() async throws {
        let model = LiteRtTextModel()
        let modelId = try XCTUnwrap(model.modelId, "the app bundle carries the model (ADR-021)")
        XCTAssertTrue(modelId.hasPrefix("embeddinggemma-2-text-270m@"))
        let embedder = try await model.load(withImages: false)
        defer { embedder.close() }
        let vectors = try await embedder.embed(
            texts: [
                PromptStyle.modelCard.query(text: "le chargeur du Kindle"),
                PromptStyle.modelCard.document(text: "Câble USB-C blanc"),
                PromptStyle.modelCard.document(text: "Tire-bouchon"),
            ],
            dim: 256
        )
        XCTAssertEqual(vectors.count, 3)
        XCTAssertEqual(vectors[0].size, 256)
        func dot(_ lhs: KotlinFloatArray, _ rhs: KotlinFloatArray) -> Float {
            (0 ..< lhs.size).reduce(Float(0)) { $0 + lhs.get(index: $1) * rhs.get(index: $1) }
        }
        XCTAssertEqual(dot(vectors[1], vectors[1]), 1, accuracy: 0.01, "normalised")
        XCTAssertGreaterThan(dot(vectors[0], vectors[1]), dot(vectors[0], vectors[2]), "U3: a charger is a cable")
    }

    func testTheVisionFileEmbedsAPhoto() async throws {
        let model = LiteRtTextModel()
        let visionId = try XCTUnwrap(model.imageModelId, "the app bundle carries the text+vision file (ADR-022)")
        XCTAssertTrue(visionId.hasPrefix("embeddinggemma-2-text-vision-440m@"))
        XCTAssertGreaterThan(try XCTUnwrap(model.labelCatalogData()).count, 100_000, "label catalog (B8)")
        let loaded = try await model.load(withImages: true)
        let embedder = try XCTUnwrap(loaded as? CallbackImageEmbedder)
        defer { embedder.close() }
        // A generated picture, never a real photo in the repository: a yellow disc on blue.
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 320, height: 320))
        let picture = renderer.jpegData(withCompressionQuality: 0.9) { context in
            UIColor.blue.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 320, height: 320))
            UIColor.yellow.setFill()
            context.cgContext.fillEllipse(in: CGRect(x: 60, y: 60, width: 200, height: 200))
        }
        let vectors: [Data] = try await withCheckedThrowingContinuation { continuation in
            embedder.embedJpegs(jpegs: [picture, picture], dim: 256) { vectors, failure in
                if let vectors {
                    continuation.resume(returning: vectors)
                } else {
                    continuation.resume(throwing: NSError(domain: failure ?? "embedding failed", code: 1))
                }
            }
        }
        let floats = vectors.map { data in data.withUnsafeBytes { Array($0.bindMemory(to: Float.self)) } }
        XCTAssertEqual(floats[0].count, 256)
        func dot(_ lhs: [Float], _ rhs: [Float]) -> Float {
            zip(lhs, rhs).reduce(Float(0)) { $0 + $1.0 * $1.1 }
        }
        XCTAssertEqual(dot(floats[0], floats[0]), 1, accuracy: 0.01, "normalised")
        XCTAssertEqual(dot(floats[0], floats[1]), 1, accuracy: 0.01, "deterministic")
    }
}
