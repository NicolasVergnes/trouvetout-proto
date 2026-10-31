@testable import iosApp
import Shared
import XCTest

/// Lot 6 on the device side: the bundled EmbeddingGemma 2 file loads with LiteRT-LM and its vectors carry meaning.
final class SearchModelTests: XCTestCase {
    func testTheBundledModelEmbedsOnTheDevice() async throws {
        let model = LiteRtTextModel()
        let modelId = try XCTUnwrap(model.modelId, "the app bundle carries the model (ADR-021)")
        XCTAssertTrue(modelId.hasPrefix("embeddinggemma-2-text-270m@"))
        let embedder = try await model.load()
        defer { embedder.close() }
        let vectors = try await embedder.embed(
            texts: [
                PromptStyle.litert.query(text: "le chargeur du Kindle"),
                PromptStyle.litert.document(text: "Câble USB-C blanc"),
                PromptStyle.litert.document(text: "Tire-bouchon"),
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
}
