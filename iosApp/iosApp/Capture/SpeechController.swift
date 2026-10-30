import AVFoundation
import Shared
import Speech

/// What one listening run reports.
enum SpeechEvent {
    case partial(String)
    case final(String)
    case failed(SpeechFailure)
}

enum SpeechFailure {
    case noMatch
    case failed
}

/// Microphone and speech recognition rights, read together: dictation needs both.
enum SpeechPermission {
    case granted
    case denied
    case undetermined
}

/// What the capture screen needs from dictation; a fake replaces it in tests.
@MainActor
protocol SpeechListening: AnyObject {
    func availability(lang: Lang) -> SpeechAvailability
    func permission() -> SpeechPermission
    /// Shows the system prompts; true when dictation may start.
    func requestPermission() async -> Bool
    /// Events arrive on the main actor until a final result, a failure or `cancel()`.
    func start(lang: Lang, onEvent: @escaping (SpeechEvent) -> Void) throws
    /// Closes the microphone at once; nothing more is reported.
    func cancel()
}

/// On-device dictation (B2): `requiresOnDeviceRecognition` is always set and never relaxed; without an on-device
/// model for the language the host offers text. The audio is never written anywhere; the transcript goes to the
/// core parser like a typed sentence.
@MainActor
final class SpeechController: SpeechListening {
    /// A pause this long after the last words ends the sentence; before any word, the longer one applies.
    private static let silenceAfterSpeech: UInt64 = 1_500_000_000
    private static let silenceBeforeSpeech: UInt64 = 5_000_000_000

    private var recognizer: SFSpeechRecognizer?
    private var engine: AVAudioEngine?
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var silenceTimer: Task<Void, Never>?
    private var onEvent: ((SpeechEvent) -> Void)?
    private var lastText = ""

    func availability(lang: Lang) -> SpeechAvailability {
        let recognizer = SFSpeechRecognizer(locale: Self.locale(lang))
        let onDevice = recognizer.map { KotlinBoolean(bool: $0.supportsOnDeviceRecognition) }
        return SpeechPolicy.shared.decide(onDeviceSupported: onDevice, recognizerInstalled: recognizer != nil)
    }

    func permission() -> SpeechPermission {
        let speech = SFSpeechRecognizer.authorizationStatus()
        let microphone = Self.microphonePermission()
        if speech == .denied || speech == .restricted || microphone == .denied {
            return .denied
        }
        return speech == .authorized && microphone == .granted ? .granted : .undetermined
    }

    /// Shows the two system prompts (speech, then microphone); true when both are granted.
    func requestPermission() async -> Bool {
        let speech = await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { continuation.resume(returning: $0) }
        }
        guard speech == .authorized else { return false }
        return await withCheckedContinuation { continuation in
            if #available(iOS 17.0, *) {
                AVAudioApplication.requestRecordPermission { continuation.resume(returning: $0) }
            } else {
                AVAudioSession.sharedInstance().requestRecordPermission { continuation.resume(returning: $0) }
            }
        }
    }

    func start(lang: Lang, onEvent: @escaping (SpeechEvent) -> Void) throws {
        cancel()
        guard let recognizer = SFSpeechRecognizer(locale: Self.locale(lang)),
              recognizer.supportsOnDeviceRecognition
        else {
            onEvent(.failed(.failed))
            return
        }
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.record, mode: .measurement, options: .duckOthers)
        try session.setActive(true, options: .notifyOthersOnDeactivation)

        let request = SFSpeechAudioBufferRecognitionRequest()
        request.requiresOnDeviceRecognition = true
        request.shouldReportPartialResults = true
        request.taskHint = .dictation
        let engine = AVAudioEngine()
        let input = engine.inputNode
        input.installTap(onBus: 0, bufferSize: 1024, format: input.outputFormat(forBus: 0)) { buffer, _ in
            request.append(buffer)
        }
        engine.prepare()
        try engine.start()

        self.recognizer = recognizer
        self.engine = engine
        self.request = request
        self.onEvent = onEvent
        // The recogniser calls back on its queue, the main operation queue by default.
        recognizer.queue = .main
        task = recognizer.recognitionTask(with: request) { [weak self] result, error in
            let text = result?.bestTranscription.formattedString
            let isFinal = result?.isFinal ?? false
            MainActor.assumeIsolated {
                self?.handle(text: text, isFinal: isFinal, failed: error != nil)
            }
        }
        armSilenceTimer(Self.silenceBeforeSpeech)
    }

    func cancel() {
        silenceTimer?.cancel()
        silenceTimer = nil
        onEvent = nil
        lastText = ""
        task?.cancel()
        task = nil
        recognizer = nil
        stopAudio()
    }

    private func handle(text: String?, isFinal: Bool, failed: Bool) {
        guard let onEvent else { return }
        if let text, !text.isEmpty {
            lastText = text
        }
        if isFinal || failed {
            let heard = lastText
            cancel()
            if heard.isEmpty {
                // Silence ends with an error from the recogniser: nothing heard, not a failure.
                onEvent(.failed(.noMatch))
            } else {
                onEvent(.final(heard))
            }
            return
        }
        if let text, !text.isEmpty {
            onEvent(.partial(text))
            armSilenceTimer(Self.silenceAfterSpeech)
        }
    }

    /// After a pause the audio ends: the recogniser then delivers its final result.
    private func armSilenceTimer(_ nanoseconds: UInt64) {
        silenceTimer?.cancel()
        silenceTimer = Task { [weak self] in
            try? await Task.sleep(nanoseconds: nanoseconds)
            guard !Task.isCancelled else { return }
            self?.stopAudio()
        }
    }

    private func stopAudio() {
        engine?.stop()
        engine?.inputNode.removeTap(onBus: 0)
        engine = nil
        request?.endAudio()
        request = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private static func microphonePermission() -> SpeechPermission {
        if #available(iOS 17.0, *) {
            switch AVAudioApplication.shared.recordPermission {
            case .granted: .granted
            case .denied: .denied
            default: .undetermined
            }
        } else {
            switch AVAudioSession.sharedInstance().recordPermission {
            case .granted: .granted
            case .denied: .denied
            default: .undetermined
            }
        }
    }

    /// The user's own variant when it is the capture language (fr-CA, en-GB…), whose on-device model is the one
    /// most likely installed.
    private static func locale(_ lang: Lang) -> Locale {
        let code = lang == .fr ? "fr" : "en"
        let current = Locale.current
        if current.language.languageCode?.identifier == code,
           SFSpeechRecognizer.supportedLocales().contains(where: { $0.identifier == current.identifier })
        {
            return current
        }
        return Locale(identifier: lang == .fr ? "fr-FR" : "en-US")
    }
}
