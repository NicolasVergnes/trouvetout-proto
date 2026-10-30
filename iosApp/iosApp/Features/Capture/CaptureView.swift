import Shared
import SwiftUI

/// Home screen since lot 3: one field to say where things go, a confirmation card, the objects below.
struct CaptureView: View {
    @StateObject private var viewModel: CaptureViewModel
    @EnvironmentObject private var router: CaptureRouter
    @Environment(\.scenePhase) private var scenePhase
    @FocusState private var fieldFocused: Bool
    @State private var pickingBatch = false
    /// Requests are replayed to every new subscriber: each serial is handled once.
    @State private var handledSerial = 0

    init(container: AppContainer) {
        _viewModel = StateObject(wrappedValue: CaptureViewModel(container: container))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Group {
                field
                VoiceStatusView(
                    listening: viewModel.listening,
                    notice: viewModel.voiceNotice,
                    onOpenSettings: openAppSettings
                )
                batchRow
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
                    Text(problemKey(problem))
                        .font(.callout)
                        .foregroundStyle(.red)
                        .accessibilityAddTraits(.updatesFrequently)
                }
            }
            .padding(.horizontal, 16)
            itemList
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
        .onDisappear { viewModel.stopListening() }
    }

    /// A capture asked by a widget, a URL or the App Intent, once the app is really in front (the audio session
    /// cannot start in the background); the questions and pickers in the way are closed first.
    private func handle(_ request: CaptureRouter.Request?) {
        guard let request, request.serial > handledSerial, scenePhase == .active else { return }
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

    private func openAppSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }

    private var typedText: Binding<String> {
        Binding(get: { viewModel.input }, set: { viewModel.typed($0) })
    }

    private var field: some View {
        HStack {
            TextField("capture_hint", text: typedText)
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
        let query = viewModel.searchQuery
        let shown: [ItemView] =
            if let query {
                viewModel.items.filter { $0.item.name.localizedCaseInsensitiveContains(query) }
            } else {
                viewModel.items
            }
        if let query {
            HStack {
                Text(String(format: NSLocalizedString("capture_search_title", comment: ""), query))
                    .font(.subheadline.bold())
                Spacer()
                Button("action_close") { viewModel.dismissMessages() }
            }
            .padding(.horizontal, 16)
        }
        if shown.isEmpty {
            emptyText(searching: query != nil)
            Spacer()
        } else {
            List(shown, id: \.item.id) { view in
                NavigationLink(value: ItemRoute(id: view.item.id)) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(view.item.name)
                            .font(.headline)
                        Text(whereText(view))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
            .listStyle(.plain)
        }
    }

    private func emptyText(searching: Bool) -> some View {
        Group {
            if searching {
                Text("capture_search_empty")
            } else {
                Text("capture_empty")
            }
        }
        .font(.callout)
        .foregroundStyle(.secondary)
        .padding(.horizontal, 16)
        .padding(.top, 24)
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

    private func whereText(_ view: ItemView) -> String {
        switch view.location.status {
        case .taken: NSLocalizedString("state_taken", comment: "")
        case .lent: String(format: NSLocalizedString("state_lent", comment: ""), view.location.lentTo ?? "")
        default:
            if view.path.isUnknown {
                NSLocalizedString("state_unknown_place", comment: "")
            } else {
                view.path.labels.joined(separator: " › ")
            }
        }
    }

    private func problemKey(_ problem: CaptureProblem) -> LocalizedStringKey {
        switch problem {
        case .nothingUnderstood: "capture_problem_nothing"
        case .placeUndecided: "capture_problem_place"
        case .itemNotFound: "capture_problem_item"
        case .personMissing: "capture_problem_person"
        case .inputTooLong: "capture_problem_too_long"
        default: "capture_problem_rejected"
        }
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
