@testable import iosApp
import Shared
import XCTest

/// Dictation seen from the capture screen, with a fake recogniser: what a URL may do (any app can send one)
/// and what the user's own microphone tap does (ADR-020).
final class CaptureVoiceTests: XCTestCase {
    @MainActor
    func testASentenceHeardAfterAURLWaitsForTheUser() {
        let speech = FakeSpeech()
        let viewModel = CaptureViewModel(container: AppContainer(), speech: speech)
        viewModel.micTapped(recordWhenHeard: false)
        XCTAssertTrue(viewModel.listening)

        speech.say("marteau dans le garage")
        XCTAssertFalse(viewModel.listening)
        XCTAssertEqual(viewModel.input, "marteau dans le garage")
        XCTAssertFalse(viewModel.busy, "nothing is being recorded")
        XCTAssertNil(viewModel.card)
    }

    @MainActor
    func testTheMicrophoneButtonRecordsWhatItHears() {
        let speech = FakeSpeech()
        let viewModel = CaptureViewModel(container: AppContainer(), speech: speech)
        viewModel.micTapped(recordWhenHeard: false)
        viewModel.micTapped()
        XCTAssertFalse(viewModel.listening, "a second tap stops listening")

        viewModel.micTapped()
        speech.say("tournevis dans la cave")
        XCTAssertTrue(viewModel.busy, "the sentence went straight to the capture")
    }

    @MainActor
    func testWithoutOnDeviceRecognitionTheMicrophoneStaysOff() {
        let speech = FakeSpeech()
        speech.available = .notOnDevice
        let viewModel = CaptureViewModel(container: AppContainer(), speech: speech)
        viewModel.micTapped()
        XCTAssertFalse(viewModel.listening)
        XCTAssertFalse(speech.started)
        XCTAssertEqual(viewModel.voiceNotice, .notOnDevice)
    }
}

@MainActor
private final class FakeSpeech: SpeechListening {
    var available: SpeechAvailability = .onDevice
    private(set) var started = false
    private var onEvent: ((SpeechEvent) -> Void)?

    func availability(lang _: Lang) -> SpeechAvailability {
        available
    }

    func permission() -> SpeechPermission {
        .granted
    }

    func requestPermission() async -> Bool {
        true
    }

    func start(lang _: Lang, onEvent: @escaping (SpeechEvent) -> Void) throws {
        started = true
        self.onEvent = onEvent
    }

    func cancel() {
        onEvent = nil
    }

    func say(_ text: String) {
        let deliver = onEvent
        cancel()
        deliver?(.final(text))
    }
}
