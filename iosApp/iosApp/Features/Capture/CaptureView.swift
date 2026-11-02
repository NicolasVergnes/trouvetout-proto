import Shared
import SwiftUI

/// Home screen since lot 3: one field to say where things go, a confirmation card, the objects below.
struct CaptureView: View {
    @StateObject private var viewModel: CaptureViewModel
    @EnvironmentObject private var router: CaptureRouter
    @EnvironmentObject private var appContainer: AppContainer
    @Environment(\.scenePhase) private var scenePhase
    @FocusState private var fieldFocused: Bool
    @AppStorage(UiPrefs.simpleModeKey) private var simpleMode = false
    @State private var pickingBatch = false
    /// Requests are replayed to every new subscriber: each serial is handled once.
    @State private var handledSerial = 0

    /// Opens the search screen (lot 6) with a question asked here (C5).
    private let onSearch: (String) -> Void

    init(container: AppContainer, onSearch: @escaping (String) -> Void = { _ in }) {
        _viewModel = StateObject(wrappedValue: CaptureViewModel(container: container))
        self.onSearch = onSearch
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Group {
                if simpleMode {
                    SimpleModeButtons(onStore: simpleStore, onFind: { onSearch("") })
                }
                field
                VoiceStatusView(
                    listening: viewModel.listening,
                    notice: viewModel.voiceNotice,
                    onOpenSettings: CaptureTexts.openAppSettings
                )
                if !simpleMode {
                    batchRow
                    CapturePhotoRow(viewModel: viewModel) { fieldFocused = true }
                }
                if !viewModel.suggestions.isEmpty {
                    suggestionRow
                }
                if let card = viewModel.card {
                    CaptureCardView(card: card, onEdit: { viewModel.editLast() }, onUndo: { viewModel.undo() })
                        .task(id: card.token) {
                            // VoiceOver users read at their own pace: the card then stays until they act.
                            guard !UIAccessibility.isVoiceOverRunning else { return }
                            try? await Task.sleep(nanoseconds: 3_000_000_000)
                            viewModel.dismissCard(token: card.token)
                        }
                }
                if let problem = viewModel.problem {
                    Text(CaptureTexts.problemKey(problem))
                        .font(.callout)
                        .foregroundStyle(.red)
                        .accessibilityAddTraits(.updatesFrequently)
                }
            }
            .padding(.horizontal, 16)
            if !simpleMode {
                itemList
            } else {
                Spacer()
            }
        }
        .sheet(isPresented: pendingPresented) {
            if let pending = viewModel.pending {
                DecisionSheet(pending: pending, viewModel: viewModel)
            }
        }
        .alert("voice_rationale_title", isPresented: rationalePresented) {
            Button("action_continue") { viewModel.micRationaleContinue() }
            Button("action_cancel", role: .cancel) { viewModel.dismissMicRationale() }
        } message: {
            Text("voice_rationale_text")
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active {
                handle(router.request)
            } else {
                viewModel.stopListening()
            }
        }
        .onReceive(router.$request) { handle($0) }
        .onChange(of: appContainer.locked) { _ in handle(router.request) }
        // A change, not the current value: a new subscription must not replay an old question.
        .onChange(of: viewModel.searchQuery) { query in
            guard let query else { return }
            viewModel.dismissMessages() // the search screen takes the question over
            onSearch(query)
        }
        .onDisappear { viewModel.stopListening() }
    }

    /// I7 « Ranger »: listens when the phone recognises speech on the device, otherwise opens the keyboard.
    private func simpleStore() {
        viewModel.micTapped()
        if !viewModel.listening, viewModel.voiceNotice != nil {
            fieldFocused = true
        }
    }

    /// A capture asked by a widget, a URL or the App Intent, once the app is really in front (the audio session
    /// cannot start in the background); the questions and pickers in the way are closed first.
    private func handle(_ request: CaptureRouter.Request?) {
        guard let request, request.serial > handledSerial, scenePhase == .active else { return }
        // H3: under the lock the request waits (the keyboard would show above the cover, the microphone behind it).
        guard !(appContainer.ready && appContainer.locked != false) else { return }
        handledSerial = request.serial
        // Cleared outside the publisher's willSet, so that a recreated screen never replays it.
        Task { @MainActor in router.consume() }
        viewModel.dismissDecision()
        pickingBatch = false
        switch request.mode {
        case .voice:
            if !viewModel.listening {
                viewModel.micTapped(recordWhenHeard: !request.fromURL)
            }
        case .text:
            fieldFocused = true
        }
    }

    /// B10: « Je range… » picks a destination; every sentence without a place then goes there until closed.
    @ViewBuilder
    private var batchRow: some View {
        if let batch = viewModel.batch {
            Button {
                viewModel.stopBatch()
            } label: {
                Label(
                    String(format: NSLocalizedString("batch_active", comment: ""), batch.label),
                    systemImage: "xmark.circle.fill"
                )
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .accessibilityHint("batch_stop")
        } else if !viewModel.places.isEmpty {
            Button("batch_start") { pickingBatch = true }
                .buttonStyle(.bordered)
                .controlSize(.large)
                .sheet(isPresented: $pickingBatch) {
                    LocationPickerSheet(
                        title: "batch_picker_title",
                        places: viewModel.places,
                        containers: viewModel.containers
                    ) { ref in
                        viewModel.startBatch(ref)
                        pickingBatch = false
                    }
                }
        }
    }

    private var rationalePresented: Binding<Bool> {
        Binding(
            get: { viewModel.askMicPermission },
            set: { presented in
                if !presented {
                    viewModel.dismissMicRationale()
                }
            }
        )
    }

    /// A multi-line field turns Return into a new line: here it sends the sentence, as before.
    private var typedText: Binding<String> {
        Binding(
            get: { viewModel.input },
            set: { text in
                if text.contains("\n") {
                    viewModel.typed(text.replacingOccurrences(of: "\n", with: ""))
                    viewModel.submit()
                } else {
                    viewModel.typed(text)
                }
            }
        )
    }

    private var field: some View {
        HStack {
            // Grows on several lines instead of clipping its hint or a long sentence (large text sizes, I6).
            TextField("capture_hint", text: typedText, axis: .vertical)
                .lineLimit(1 ... 4)
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .submitLabel(.done)
                .focused($fieldFocused)
                .disabled(viewModel.listening)
                .onSubmit { viewModel.submit() }
            Button {
                viewModel.micTapped()
            } label: {
                Image(systemName: viewModel.listening ? "stop.circle.fill" : "mic.fill")
                    .font(.title2)
                    .frame(minWidth: 44, minHeight: 44)
            }
            .accessibilityLabel(viewModel.listening ? "capture_mic_stop" : "capture_mic")
            .disabled(viewModel.busy && !viewModel.listening)
            Button {
                viewModel.submit()
            } label: {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.title2)
                    .frame(minWidth: 44, minHeight: 44)
            }
            .accessibilityLabel("capture_send")
            .disabled(
                viewModel.input.trimmingCharacters(in: .whitespaces).isEmpty || viewModel.busy || viewModel.listening
            )
        }
    }

    private var suggestionRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack {
                ForEach(viewModel.suggestions, id: \.id) { candidate in
                    Button(candidate.name) { viewModel.pick(candidate) }
                        .buttonStyle(.bordered)
                        .controlSize(.large)
                }
            }
        }
    }

    @ViewBuilder
    private var itemList: some View {
        if viewModel.items.isEmpty {
            Text("capture_empty")
                .font(.callout)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 16)
                .padding(.top, 24)
            Spacer()
        } else {
            List(viewModel.items, id: \.item.id) { view in
                NavigationLink(value: ItemRoute(id: view.item.id)) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(view.item.name)
                            .font(.headline)
                        Text(CaptureTexts.whereText(view))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
            .listStyle(.plain)
        }
    }

    private var pendingPresented: Binding<Bool> {
        Binding(
            get: { viewModel.pending != nil },
            set: { presented in
                if !presented {
                    viewModel.dismissDecision()
                }
            }
        )
    }
}

/// « J'écoute… » while the microphone is open, otherwise why it is not (with the way out).
private struct VoiceStatusView: View {
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

private struct CaptureCardView: View {
    let card: ConfirmationCard
    let onEdit: () -> Void
    let onUndo: () -> Void

    var body: some View {
        HStack {
            Button(action: onEdit) {
                VStack(alignment: .leading) {
                    Text(card.itemNames.joined(separator: ", "))
                        .font(.headline)
                    Text(detail)
                        .font(.subheadline)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint("capture_card_edit")
            Button("action_undo", action: onUndo)
        }
        .padding(12)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .contain)
        .onAppear {
            let spoken = card.itemNames.joined(separator: ", ") + ", " + spokenDetail
            UIAccessibility.post(notification: .announcement, argument: spoken)
        }
    }

    private var detail: String {
        switch card.intent {
        case .take: NSLocalizedString("capture_card_taken", comment: "")
        case .lend: String(format: NSLocalizedString("capture_card_lent", comment: ""), card.person ?? "")
        case .returnFromLend: NSLocalizedString("capture_card_returned_from_lend", comment: "")
        case .return_: placeOr("capture_card_put_back")
        default: placeOr("capture_card_no_place")
        }
    }

    /// The arrow of `capture_card_stored` is for the eye; VoiceOver hears « rangé dans … ».
    private var spokenDetail: String {
        let movement = card.intent == .take || card.intent == .lend || card.intent == .returnFromLend
        if let place = card.placeText, !movement {
            return String(format: NSLocalizedString("capture_card_stored_spoken", comment: ""), place)
        }
        return detail
    }

    private func placeOr(_ fallbackKey: String) -> String {
        if let place = card.placeText {
            return String(format: NSLocalizedString("capture_card_stored", comment: ""), place)
        }
        return NSLocalizedString(fallbackKey, comment: "")
    }
}
