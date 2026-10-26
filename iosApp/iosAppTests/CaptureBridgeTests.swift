@testable import iosApp
import Shared
import XCTest

/// The capture pipeline through the Swift side of the bridge: flattened nested types, positional item
/// choices, question sequencing and the whole-capture undo (U1 text, A14).
final class CaptureBridgeTests: XCTestCase {
    private var directory: URL!
    private var factory: SharedFactory!

    override func setUp() async throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("tt-capture-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let config = SharedConfig(
            dataDirectory: directory.path,
            deviceInfo: IosDeviceInfo(),
            driverFactory: IosDriverFactory(
                directory: directory.appendingPathComponent("db").path,
                fileName: "test.db"
            ),
            imageCodec: UIImageCodec(),
            logger: NoopLogger.shared,
            storageReady: true,
            appVersion: "test",
            captureLang: .fr
        )
        factory = SharedFactory(config: config)
        _ = try await factory.warmUp()
    }

    override func tearDown() {
        factory.close()
        try? FileManager.default.removeItem(at: directory)
    }

    private func createChoice(_ proposal: CaptureProposal) throws -> PlaceChoiceCreateNew {
        let unknown = try XCTUnwrap(proposal.place as? PlaceResolutionUnknown)
        return PlaceChoiceCreateNew(names: unknown.names, underId: unknown.under?.candidate.id, underIsContainer: false)
    }

    func testCapturesAnObjectInANewPlaceAndUndoesTheWholeCapture() async throws {
        let proposal = try await factory.capture.propose(text: "clés @ entrée", lang: .fr)
        XCTAssertEqual(proposal.parse.intent, .store)
        XCTAssertTrue(proposal.needsPlaceDecision)

        let choice = try createChoice(proposal)
        let outcome = try await factory.capture.commit(
            proposal: proposal,
            placeChoice: choice,
            itemChoices: [],
            source: .text
        )
        let recorded = try XCTUnwrap(outcome as? CaptureOutcomeRecorded)
        XCTAssertEqual(recorded.itemIds.count, 1)
        XCTAssertEqual(recorded.events.count, 3, "place created, item created, item stored")

        let undone = try await factory.undo.undo(events: recorded.events, source: .text)
        XCTAssertTrue(undone is CommandResultDone)
        let again = try await factory.capture.propose(text: "clés @ entrée", lang: .fr)
        XCTAssertTrue(again.needsPlaceDecision, "the place is gone too")
    }

    func testPositionalItemChoicesReachTheRightTarget() async throws {
        let first = try await factory.capture.propose(text: "perceuse dans le garage", lang: .fr)
        let firstChoice = try createChoice(first)
        let firstOutcome = try await factory.capture.commit(
            proposal: first,
            placeChoice: firstChoice,
            itemChoices: [],
            source: .text
        )
        let stored = try XCTUnwrap(firstOutcome as? CaptureOutcomeRecorded)
        let drill = try XCTUnwrap(stored.itemIds.first)

        let second = try await factory.capture.propose(text: "marteau et perceuse dans la cave", lang: .fr)
        XCTAssertEqual(second.nextQuestion(answered: []), 1, "only the drill has a look-alike")
        XCTAssertNil(second.nextQuestion(answered: [1]))
        let choices = [ItemChoice(index: 1, existingId: drill)]
        let secondChoice = try createChoice(second)
        let outcome = try await factory.capture.commit(
            proposal: second,
            placeChoice: secondChoice,
            itemChoices: choices,
            source: .text
        )
        let recorded = try XCTUnwrap(outcome as? CaptureOutcomeRecorded)
        XCTAssertEqual(recorded.itemIds.count, 2)
        XCTAssertTrue(recorded.itemIds.contains(drill), "the existing drill moved, no twin created")
    }

    func testAQuestionBecomesASearchAndAnOverlongSentenceIsRefused() async throws {
        let question = try await factory.capture.propose(text: "où sont les clés ?", lang: .fr)
        let asked = try await factory.capture.commit(
            proposal: question,
            placeChoice: nil,
            itemChoices: [],
            source: .text
        )
        let search = try XCTUnwrap(asked as? CaptureOutcomeSearch)
        XCTAssertEqual(search.query, "clés")

        let wall = String(repeating: "a", count: 10000)
        let refused = try await factory.capture.propose(text: wall, lang: .fr)
        XCTAssertEqual(refused.problem, .inputTooLong)
    }
}
