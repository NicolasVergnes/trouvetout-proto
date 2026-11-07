import Shared
import SwiftUI

enum BackupMessage: Equatable {
    case written
    case restored(appended: Int32, skipped: Int32, photos: Int32, ignored: Int32)
    case refused
    case badKey
    case notABackup
    case cannotOpen
    case failed

    var text: String {
        switch self {
        case .written: NSLocalizedString("backup_done", comment: "")
        case let .restored(appended, skipped, photos, ignored):
            String(
                format: NSLocalizedString("settings_import_done_text", comment: ""),
                Int(appended),
                Int(skipped),
                Int(photos),
                Int(ignored)
            )
        case .refused: NSLocalizedString("settings_import_refused_invalid", comment: "")
        case .badKey: NSLocalizedString("backup_bad_key", comment: "")
        case .notABackup: NSLocalizedString("backup_not_a_backup", comment: "")
        case .cannotOpen: NSLocalizedString("backup_cannot_open", comment: "")
        case .failed: NSLocalizedString("settings_failed_text", comment: "")
        }
    }
}

/// What the section presents, one at a time.
enum BackupSheet: Identifiable {
    case folderPicker
    case newKey(String)
    case restoreKey

    var id: String {
        switch self {
        case .folderPicker: "picker"
        case .newKey: "key"
        case .restoreKey: "restore"
        }
    }
}

/// A9: the steps of the settings section; the rules are in the core's `BackupService`.
@MainActor
final class BackupViewModel: ObservableObject {
    @Published private(set) var state: BackupState?
    @Published private(set) var busy = false
    @Published var message: BackupMessage?
    @Published var sheet: BackupSheet?
    /// Turning on: the folder chosen, until the key is confirmed.
    private var newLocation: String?
    /// The picker's answer, handled once its sheet is gone.
    var pickedFolder: URL?
    /// Restoring: the file chosen, until the user gives its key.
    var restoreFrom: URL?

    private let container: AppContainer

    init(container: AppContainer) {
        self.container = container
    }

    func refresh() async {
        guard container.ready else { return }
        state = try? await container.shared.backup.state()
    }

    func confirmsKeyEnd(_ key: String, typed: String) -> Bool {
        container.shared.backup.confirmsKeyEnd(recoveryKey: key, typed: typed)
    }

    /// The picker's sheet is gone. A lost folder is replaced with the same key; otherwise a key is drawn, and nothing
    /// is kept until it is confirmed.
    func folderPickerClosed() {
        guard let folder = pickedFolder else { return }
        pickedFolder = nil
        guard let location = try? BackupDestination.location(inFolder: folder) else {
            message = .failed
            return
        }
        if state?.problem == .destinationLost {
            perform { [self] in
                try await container.shared.backup.relocate(location: location)
                await write()
            }
            return
        }
        guard let key = try? container.shared.backup.newRecoveryKey() else {
            message = .failed
            return
        }
        newLocation = location
        sheet = .newKey(key)
    }

    func cancelNewKey() {
        newLocation = nil
        sheet = nil
    }

    /// The user retyped the end of the key: backups start, the first one now.
    func confirmNewKey(_ key: String) {
        guard !busy, let location = newLocation else { return }
        cancelNewKey()
        perform { [self] in
            let backup = container.shared.backup
            guard try await backup.enable(recoveryKey: key, location: location).boolValue else {
                message = .failed
                return
            }
            await write()
        }
    }

    func backUpNow() {
        perform { [self] in await write() }
    }

    func disable() {
        perform { [self] in try await container.shared.backup.disable() }
    }

    func restorePicked(_ url: URL) {
        restoreFrom = url
        sheet = .restoreKey
    }

    /// Copies the file (bounded, coordinated) into the private import directory: the core never reads foreign URLs.
    func restore(recoveryKey: String) {
        guard let picked = restoreFrom else { return }
        restoreFrom = nil
        sheet = nil
        perform { [self] in
            let shared = container.shared
            let relativePath = "\(SharedFactory.companion.IMPORT_DIRECTORY)/\(UUID().uuidString.lowercased()).ttbk"
            let target = URL(fileURLWithPath: shared.config.dataDirectory).appendingPathComponent(relativePath)
            try await Task.detached(priority: .userInitiated) {
                try BackupDestination.copyBounded(
                    picked,
                    to: target,
                    limit: BackupService.companion.MAX_BACKUP_BYTES
                )
            }.value
            let result = try await shared.backup.restore(relativePath: relativePath, recoveryKey: recoveryKey)
            message = Self.message(of: result)
        }
    }

    /// Only what really happened: the section says the rest, from the state of the backup.
    private func write() async {
        if await container.runBackup() == .written {
            message = .written
        }
    }

    private static func message(of result: BackupRestoreResult) -> BackupMessage {
        switch result {
        case let imported as BackupRestoreResultImported:
            let report = imported.report
            return .restored(
                appended: report.eventsAppended,
                skipped: report.eventsSkipped,
                photos: report.photosImported,
                ignored: report.dropped
            )
        case is BackupRestoreResultRefused: return .refused
        case is BackupRestoreResultBadKey: return .badKey
        case is BackupRestoreResultNotABackup: return .notABackup
        default: return .cannotOpen
        }
    }

    private func perform(_ work: @escaping @MainActor () async throws -> Void) {
        guard !busy else { return }
        busy = true
        message = nil
        Task {
            do {
                try await work()
            } catch {
                message = .failed
            }
            await refresh()
            busy = false
        }
    }
}

/// « Sauvegarde chiffrée » (A9) in the settings. The presentations are carried by the first row: on a `Section` of a
/// `Form`, SwiftUI could apply them to each row.
struct BackupSectionView: View {
    @StateObject private var viewModel: BackupViewModel
    @State private var choosingBackup = false

    init(container: AppContainer) {
        _viewModel = StateObject(wrappedValue: BackupViewModel(container: container))
    }

    var body: some View {
        Section {
            hint
            if let state = viewModel.state, state.location != nil {
                turnedOn(state)
            } else {
                Button("backup_enable") { viewModel.sheet = .folderPicker }
                    .disabled(viewModel.busy)
            }
            Button("backup_restore") { choosingBackup = true }
                .disabled(viewModel.busy)
            if viewModel.busy {
                ProgressView("settings_busy")
                    .frame(maxWidth: .infinity)
            }
        } header: {
            Text("settings_backup_section")
        }
    }

    private var hint: some View {
        Text("backup_hint")
            .font(.footnote)
            .task { await viewModel.refresh() }
            .sheet(item: $viewModel.sheet, onDismiss: { viewModel.folderPickerClosed() }, content: sheet)
            .fileImporter(isPresented: $choosingBackup, allowedContentTypes: [.data]) { result in
                if case let .success(url) = result {
                    viewModel.restorePicked(url)
                }
            }
            .alert(viewModel.message?.text ?? "", isPresented: messagePresented) {
                Button("action_ok") { viewModel.message = nil }
            }
    }

    @ViewBuilder
    private func turnedOn(_ state: BackupState) -> some View {
        Text(lastBackup(state))
            .font(.footnote)
            .foregroundStyle(.secondary)
        if let problem = state.problem {
            Text(LocalizedStringKey(problemKey(problem)))
                .font(.footnote)
                .foregroundStyle(.red)
        }
        if state.problem == .destinationLost {
            Button("backup_choose_other") { viewModel.sheet = .folderPicker }
                .disabled(viewModel.busy)
        } else {
            Button("backup_run_now") { viewModel.backUpNow() }
                .disabled(viewModel.busy)
        }
        Text("backup_off_hint")
            .font(.footnote)
            .foregroundStyle(.secondary)
        Button("backup_disable") { viewModel.disable() }
            .disabled(viewModel.busy)
    }

    @ViewBuilder
    private func sheet(_ sheet: BackupSheet) -> some View {
        switch sheet {
        case .folderPicker:
            BackupFolderPicker { url in
                viewModel.pickedFolder = url
                viewModel.sheet = nil
            }
        case let .newKey(key):
            NewKeySheet(
                key: key,
                confirms: { viewModel.confirmsKeyEnd(key, typed: $0) },
                onConfirm: { viewModel.confirmNewKey(key) },
                onCancel: { viewModel.cancelNewKey() }
            )
        case .restoreKey:
            RestoreKeySheet(
                onRestore: { viewModel.restore(recoveryKey: $0) },
                onCancel: { viewModel.sheet = nil }
            )
        }
    }

    private func problemKey(_ problem: BackupProblem) -> String {
        switch problem {
        case .tooLarge: "backup_too_large"
        case .destinationLost: "backup_lost"
        default: "backup_failed"
        }
    }

    private func lastBackup(_ state: BackupState) -> String {
        guard let millis = state.lastSuccessMillis?.int64Value else {
            return NSLocalizedString("backup_never", comment: "")
        }
        let date = Date(timeIntervalSince1970: TimeInterval(millis) / 1000)
        return String(
            format: NSLocalizedString("backup_last", comment: ""),
            date.formatted(date: .long, time: .shortened)
        )
    }

    private var messagePresented: Binding<Bool> {
        Binding(
            get: { viewModel.message != nil },
            set: { presented in
                if !presented {
                    viewModel.message = nil
                }
            }
        )
    }
}

/// Shown once and asked back: nobody can open the backup without it. Never in the clipboard; hidden as soon as the
/// scene is no longer active, so that the app switcher's snapshot does not keep it, lock on or off. A screenshot
/// remains possible on iOS (ADR-025). The end typed again makes sure it was read.
private struct NewKeySheet: View {
    let key: String
    let confirms: (String) -> Bool
    let onConfirm: () -> Void
    let onCancel: () -> Void
    @State private var typed = ""
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack {
            Form {
                Text("backup_key_text")
                if scenePhase == .active {
                    Text(verbatim: key)
                        .font(.system(.title3, design: .monospaced))
                        .speechSpellsOutCharacters()
                    VStack(alignment: .leading) {
                        Text("backup_key_confirm_label")
                            .font(.footnote)
                        TextField("", text: $typed)
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                            .accessibilityLabel(Text("backup_key_confirm_label"))
                    }
                }
                Button("backup_key_confirm", action: onConfirm)
                    .disabled(!confirms(typed))
            }
            .navigationTitle("backup_key_title")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("action_cancel", action: onCancel)
                }
            }
        }
        .interactiveDismissDisabled()
    }
}

private struct RestoreKeySheet: View {
    let onRestore: (String) -> Void
    let onCancel: () -> Void
    @State private var typed = ""
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack {
            Form {
                Text("backup_restore_text")
                if scenePhase == .active {
                    VStack(alignment: .leading) {
                        Text("backup_restore_field")
                            .font(.footnote)
                        TextField("", text: $typed)
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                            .accessibilityLabel(Text("backup_restore_field"))
                    }
                }
                Button("backup_restore_action") { onRestore(typed) }
                    .disabled(typed.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .navigationTitle("backup_restore")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("action_cancel", action: onCancel)
                }
            }
        }
    }
}
