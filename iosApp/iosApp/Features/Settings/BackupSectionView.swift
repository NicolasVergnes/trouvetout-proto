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

/// A9: the steps of the settings section; the rules are in the core's `BackupService`.
@MainActor
final class BackupViewModel: ObservableObject {
    @Published private(set) var state: BackupState?
    @Published private(set) var busy = false
    @Published var message: BackupMessage?
    /// Turning on: the key to show once and the file chosen, until the user confirms.
    @Published var newKey: String?
    private var newLocation: String?
    /// Restoring: the file chosen, until the user gives its key.
    @Published var restoreFrom: URL?

    private let container: AppContainer

    init(container: AppContainer) {
        self.container = container
    }

    func refresh() async {
        guard container.ready else { return }
        state = try? await container.shared.backup.state()
    }

    /// The picker created the file: the key is drawn, nothing is kept until it is confirmed.
    func fileChosen(_ url: URL) {
        guard let location = try? BackupDestination.bookmark(of: url) else {
            message = .failed
            return
        }
        newLocation = location
        newKey = container.shared.backup.newRecoveryKey()
    }

    func cancelNewKey() {
        newKey = nil
        newLocation = nil
    }

    /// The user retyped the end of the key: backups start, the first one now.
    func confirmNewKey() {
        guard let key = newKey, let location = newLocation else { return }
        cancelNewKey()
        perform { [self] in
            let backup = container.shared.backup
            guard try await backup.enable(recoveryKey: key, location: location).boolValue else {
                message = .failed
                return
            }
            try await write()
        }
    }

    func backUpNow() {
        perform { [self] in try await write() }
    }

    func disable() {
        perform { [self] in try await container.shared.backup.disable() }
    }

    /// Copies the file (bounded) into the private import directory: the core never reads foreign URLs.
    func restore(recoveryKey: String) {
        guard let picked = restoreFrom else { return }
        restoreFrom = nil
        perform { [self] in
            let shared = container.shared
            let relativePath = "\(SharedFactory.companion.IMPORT_DIRECTORY)/\(UUID().uuidString.lowercased()).ttbk"
            let target = URL(fileURLWithPath: shared.config.dataDirectory).appendingPathComponent(relativePath)
            try await Self.copyBounded(picked, to: target)
            let result = try await shared.backup.restore(relativePath: relativePath, recoveryKey: recoveryKey)
            message = Self.message(of: result)
        }
    }

    private func write() async throws {
        await container.runBackup()
        if try await container.shared.backup.state().problem == nil {
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

    private nonisolated static func copyBounded(_ picked: URL, to target: URL) async throws {
        try await Task.detached(priority: .userInitiated) {
            let scoped = picked.startAccessingSecurityScopedResource()
            defer {
                if scoped {
                    picked.stopAccessingSecurityScopedResource()
                }
            }
            let size = try picked.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            guard Int64(size) <= BackupService.companion.MAX_BACKUP_BYTES else {
                throw CocoaError(.fileReadTooLarge)
            }
            try FileManager.default.createDirectory(
                at: target.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try FileManager.default.copyItem(at: picked, to: target)
        }.value
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

/// « Sauvegarde chiffrée » (A9) in the settings.
struct BackupSectionView: View {
    @StateObject private var viewModel: BackupViewModel
    @State private var choosingFile = false
    @State private var choosingBackup = false

    init(container: AppContainer) {
        _viewModel = StateObject(wrappedValue: BackupViewModel(container: container))
    }

    var body: some View {
        Section {
            Text("backup_hint")
                .font(.footnote)
            if let state = viewModel.state, state.location != nil {
                Text(lastBackup(state))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                if let problem = state.problem {
                    Text(LocalizedStringKey(problem == .tooLarge ? "backup_too_large" : "backup_failed"))
                        .font(.footnote)
                        .foregroundStyle(.red)
                }
                Button("backup_run_now") { viewModel.backUpNow() }
                    .disabled(viewModel.busy)
                Button("backup_disable") { viewModel.disable() }
                    .disabled(viewModel.busy)
            } else {
                Button("backup_enable") { choosingFile = true }
                    .disabled(viewModel.busy)
            }
            Button("backup_restore") { choosingBackup = true }
                .disabled(viewModel.busy)
        } header: {
            Text("settings_backup_section")
        }
        .task { await viewModel.refresh() }
        .sheet(isPresented: $choosingFile) {
            BackupFilePicker(fileName: BackupService.companion.SUGGESTED_FILE_NAME) { url in
                choosingFile = false
                if let url {
                    viewModel.fileChosen(url)
                }
            }
        }
        .fileImporter(isPresented: $choosingBackup, allowedContentTypes: [.data]) { result in
            if case let .success(url) = result {
                viewModel.restoreFrom = url
            }
        }
        .sheet(isPresented: newKeyPresented) {
            if let key = viewModel.newKey {
                NewKeySheet(
                    key: key,
                    onConfirm: { viewModel.confirmNewKey() },
                    onCancel: { viewModel.cancelNewKey() }
                )
            }
        }
        .sheet(isPresented: restorePresented) {
            RestoreKeySheet(
                onRestore: { viewModel.restore(recoveryKey: $0) },
                onCancel: { viewModel.restoreFrom = nil }
            )
        }
        .alert(viewModel.message?.text ?? "", isPresented: messagePresented) {
            Button("action_ok") { viewModel.message = nil }
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

    private var newKeyPresented: Binding<Bool> {
        Binding(get: { viewModel.newKey != nil }, set: { _ in })
    }

    private var restorePresented: Binding<Bool> {
        Binding(
            get: { viewModel.restoreFrom != nil },
            set: { presented in
                if !presented {
                    viewModel.restoreFrom = nil
                }
            }
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

/// Shown once and asked back: nobody can open the backup without it. Never in the clipboard; the end typed again makes
/// sure it was read.
private struct NewKeySheet: View {
    let key: String
    let onConfirm: () -> Void
    let onCancel: () -> Void
    @State private var typed = ""

    private var lastGroup: String {
        key.components(separatedBy: "-").last ?? ""
    }

    var body: some View {
        NavigationStack {
            Form {
                Text("backup_key_text")
                Text(verbatim: key)
                    .font(.system(.title3, design: .monospaced))
                    .privacySensitive()
                TextField("backup_key_confirm_label", text: $typed)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                Button("backup_key_confirm", action: onConfirm)
                    .disabled(typed.trimmingCharacters(in: .whitespaces).caseInsensitiveCompare(lastGroup) != .orderedSame)
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

    var body: some View {
        NavigationStack {
            Form {
                Text("backup_restore_text")
                TextField("backup_restore_field", text: $typed)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .privacySensitive()
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
