import Foundation

/// Entry points (widgets, App Intent, Action button) ask the home screen for a capture (B5). Speech needs the
/// foreground, so every entry opens the app; the home screen then listens or focuses the field.
@MainActor
final class CaptureRouter: ObservableObject {
    enum Mode: String {
        case voice
        case text
    }

    struct Request: Equatable {
        let mode: Mode
        /// Any app can open the URL scheme: a sentence heard after a URL waits for the user's tap to be recorded.
        let fromURL: Bool
        let serial: Int
    }

    static let shared = CaptureRouter()
    static let scheme = "trouvetout-proto"

    @Published private(set) var request: Request?
    private var serial = 0

    func open(_ mode: Mode, fromURL: Bool = false) {
        serial += 1
        request = Request(mode: mode, fromURL: fromURL, serial: serial)
    }

    func consume() {
        request = nil
    }

    /// `trouvetout-proto://capture?mode=voice|text`; no mode means voice, anything else is ignored. The URL
    /// carries no identifier and no text: it can only open the capture.
    nonisolated static func mode(from url: URL) -> Mode? {
        guard url.scheme == scheme, url.host == "capture" else { return nil }
        let value = URLComponents(url: url, resolvingAgainstBaseURL: false)?
            .queryItems?.first { $0.name == "mode" }?.value
        guard let value else { return .voice }
        return Mode(rawValue: value)
    }
}
