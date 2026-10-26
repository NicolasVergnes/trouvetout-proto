import Shared
import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
    @StateObject private var viewModel: SettingsViewModel
    @State private var confirmWipe = false
    @State private var importing = false

    init(container: AppContainer) {
        _viewModel = StateObject(wrappedValue: SettingsViewModel(container: container))
    }

    var body: some View {
        Form {
            Section {
                Text("settings_export_hint")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                if let url = viewModel.exportURL {
                    ShareLink(item: url) {
                        Label("settings_export_share", systemImage: "square.and.arrow.up")
                    }
                }
                Button("settings_export") { viewModel.export() }
                    .disabled(viewModel.busy)
                Text("settings_import_hint")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Button("settings_import") { importing = true }
                    .disabled(viewModel.busy)
            } header: {
                Text("settings_data_section")
            }
            Section {
                Text("settings_wipe_hint")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Button("settings_wipe", role: .destructive) { confirmWipe = true }
                    .disabled(viewModel.busy)
            }
            if viewModel.busy {
                ProgressView()
                    .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle("settings_title")
        .fileImporter(isPresented: $importing, allowedContentTypes: [.zip, .data]) { result in
            if case let .success(url) = result {
                viewModel.importArchive(from: url)
            }
        }
        .confirmationDialog("settings_wipe_confirm_title", isPresented: $confirmWipe, titleVisibility: .visible) {
            Button("settings_wipe_confirm_button", role: .destructive) { viewModel.wipeAll() }
            Button("action_cancel", role: .cancel) {}
        } message: {
            Text("settings_wipe_confirm_text")
        }
        .alert(messageTitle, isPresented: messagePresented) {
            Button("action_ok") { viewModel.message = nil }
        } message: {
            Text(messageText)
        }
    }

    private var messagePresented: Binding<Bool> {
        Binding(get: { viewModel.message != nil }, set: { if !$0 { viewModel.message = nil } })
    }

    private var messageTitle: LocalizedStringKey {
        switch viewModel.message {
        case .exported: "settings_export_done_title"
        case .imported: "settings_import_done_title"
        case .importRefused: "settings_import_refused_title"
        case .wiped: "settings_wipe_done_title"
        case .failed, .none: "settings_failed_title"
        }
    }

    private var messageText: String {
        switch viewModel.message {
        case let .exported(events, photos):
            String(format: localized("settings_export_done_text"), Int(events), Int(photos))
        case let .imported(appended, skipped, photos, ignored):
            String(
                format: localized("settings_import_done_text"),
                Int(appended), Int(skipped), Int(photos), Int(ignored)
            )
        case let .importRefused(reason):
            localized(refusalKey(reason))
        case .wiped:
            localized("settings_wipe_done_text")
        case .failed, .none:
            localized("settings_failed_text")
        }
    }

    private func refusalKey(_ reason: ImportRejection) -> String {
        switch reason {
        case .formatTooNew: "settings_import_refused_newer"
        case .fileNotFound, .notAnArchive: "settings_import_refused_not_archive"
        default: "settings_import_refused_invalid"
        }
    }

    private func localized(_ key: String) -> String {
        NSLocalizedString(key, comment: "")
    }
}
