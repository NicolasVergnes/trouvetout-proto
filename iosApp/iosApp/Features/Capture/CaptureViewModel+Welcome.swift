import Shared

// MARK: - First steps (I10) and language

extension CaptureViewModel {
    /// The card goes away for good; a failed write only leaves it on screen, to be dismissed again.
    func dismissWelcome() {
        Task { try? await container.shared.welcome.dismiss() }
    }

    /// The UI language at the time of the call.
    func uiLang() -> Lang {
        Locale.current.language.languageCode?.identifier == "fr" ? .fr : .en
    }
}
