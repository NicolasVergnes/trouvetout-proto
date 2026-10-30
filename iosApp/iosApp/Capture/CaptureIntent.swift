import AppIntents

/// How the capture starts.
enum CaptureModeOption: String, AppEnum {
    case voice
    case text

    static var typeDisplayRepresentation = TypeDisplayRepresentation(name: "intent_mode")
    static var caseDisplayRepresentations: [CaptureModeOption: DisplayRepresentation] = [
        .voice: DisplayRepresentation(title: "intent_mode_voice"),
        .text: DisplayRepresentation(title: "intent_mode_text"),
    ]
}

/// « Ranger » from Shortcuts, Siri or the Action button (B5): opens the app on the capture, listening by default.
struct CaptureIntent: AppIntent {
    static var title: LocalizedStringResource = "intent_capture_title"
    static var description = IntentDescription("intent_capture_description")
    static var openAppWhenRun = true

    @Parameter(title: "intent_mode", default: .voice)
    var mode: CaptureModeOption

    @MainActor
    func perform() async throws -> some IntentResult {
        CaptureRouter.shared.open(mode == .voice ? .voice : .text)
        return .result()
    }
}

/// Makes the capture available in Shortcuts and to the Action button without any setup.
struct TrouveToutShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: CaptureIntent(),
            phrases: [
                "Put something away with \(.applicationName)",
                "Record where I put something in \(.applicationName)",
            ]
        )
    }
}
