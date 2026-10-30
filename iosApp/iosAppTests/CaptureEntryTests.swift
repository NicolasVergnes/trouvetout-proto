@testable import iosApp
import Shared
import XCTest

/// Lot 5 through the Swift side: the URLs the widgets open, the speech policy seen from Swift, and the batch
/// mode (B10) committed as a voice capture.
final class CaptureEntryTests: XCTestCase {
    func testOnlyTheCaptureURLOpensTheCapture() throws {
        func mode(_ text: String) throws -> CaptureRouter.Mode? {
            try CaptureRouter.mode(from: XCTUnwrap(URL(string: text)))
        }
        XCTAssertEqual(try mode("trouvetout-proto://capture?mode=voice"), .voice)
        XCTAssertEqual(try mode("trouvetout-proto://capture?mode=text"), .text)
        XCTAssertEqual(try mode("trouvetout-proto://capture"), .voice, "no mode: listen")
        XCTAssertNil(try mode("trouvetout-proto://capture?mode=delete"))
        XCTAssertNil(try mode("trouvetout-proto://item?id=01H"))
        XCTAssertNil(try mode("https://capture?mode=voice"))
    }

    func testSpeechIsOnlyAllowedOnTheDevice() {
        let policy = SpeechPolicy.shared
        XCTAssertEqual(
            policy.decide(onDeviceSupported: KotlinBoolean(bool: true), recognizerInstalled: true),
            .onDevice
        )
        XCTAssertEqual(
            policy.decide(onDeviceSupported: KotlinBoolean(bool: false), recognizerInstalled: true),
            .notOnDevice,
            "a server-only recogniser means text"
        )
        XCTAssertEqual(policy.decide(onDeviceSupported: nil, recognizerInstalled: false), .noRecognizer)
    }

    @MainActor
    func testAnIntentRequestReachesTheRouter() async throws {
        let router = CaptureRouter.shared
        router.consume()
        var intent = CaptureIntent()
        intent.mode = .text
        _ = try await intent.perform()
        XCTAssertEqual(router.request?.mode, .text)
        XCTAssertEqual(router.request?.fromURL, false, "an intent is the user's own gesture")
        router.consume()
        // A fresh router: the shared one is watched by the host app's screen.
        let other = CaptureRouter()
        other.open(.voice, fromURL: true)
        XCTAssertEqual(other.request?.fromURL, true)
        XCTAssertEqual(other.request?.serial, 1)
    }

    func testABatchSentenceGoesIntoTheChosenPlaceAsAVoiceCapture() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("tt-entry-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let factory = SharedFactory(config: SharedConfig(
            dataDirectory: directory.path,
            deviceInfo: IosDeviceInfo(),
            driverFactory: IosDriverFactory(directory: directory.appendingPathComponent("db").path, fileName: "t.db"),
            imageCodec: UIImageCodec(),
            logger: NoopLogger.shared,
            storageReady: true,
            appVersion: "test",
            captureLang: .fr
        ))
        defer { factory.close() }
        _ = try await factory.warmUp()

        let first = try await factory.capture.propose(text: "perceuse dans le garage", lang: .fr)
        let unknown = try XCTUnwrap(first.place as? PlaceResolutionUnknown)
        let created = try await factory.capture.commit(
            proposal: first,
            placeChoice: PlaceChoiceCreateNew(names: unknown.names, underId: nil, underIsContainer: false),
            itemChoices: [],
            source: .text
        )
        let garage = try XCTUnwrap((created as? CaptureOutcomeRecorded)?.location)

        let batch = try await factory.capture.proposeIn(text: "marteau et tournevis", location: garage, lang: .fr)
        XCTAssertFalse(batch.needsPlaceDecision, "the batch place answers the question")
        let outcome = try await factory.capture.commit(
            proposal: batch,
            placeChoice: nil,
            itemChoices: [],
            source: .voice
        )
        let recorded = try XCTUnwrap(outcome as? CaptureOutcomeRecorded)
        XCTAssertEqual(recorded.itemIds.count, 2)
        XCTAssertTrue(recorded.events.allSatisfy { $0.source == .voice })
    }
}
