import Shared
import SwiftUI

/// « J'écoute… » while the microphone is open, otherwise why it is not (with the way out).
struct VoiceStatusView: View {
    let listening: Bool
    let notice: VoiceNotice?
    let onOpenSettings: () -> Void

    var body: some View {
        if listening {
            Text("capture_listening")
                .font(.callout)
                .foregroundStyle(.tint)
                .accessibilityAddTraits(.updatesFrequently)
        } else if let notice {
            VStack(alignment: .leading, spacing: 4) {
                Text(key(notice))
                    .font(.callout)
                    .foregroundStyle(.secondary)
                if notice == .permissionDenied {
                    Button("action_phone_settings", action: onOpenSettings)
                        .frame(minHeight: 44)
                }
            }
        }
    }

    private func key(_ notice: VoiceNotice) -> LocalizedStringKey {
        switch notice {
        case .notOnDevice: "voice_not_on_device"
        case .noRecognizer: "voice_no_recognizer"
        case .noMatch: "voice_no_match"
        case .permissionDenied: "voice_permission_denied"
        case .failed: "voice_failed"
        }
    }
}
