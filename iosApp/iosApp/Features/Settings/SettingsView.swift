import Shared
import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
    @StateObject private var viewModel: SettingsViewModel
    @State private var confirmWipe = false
    @State private var importing = false
    @AppStorage(UiPrefs.simpleModeKey) private var simpleMode = false

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
            BackupSectionView(container: viewModel.container)
            Section {
                NavigationLink("purchase_see_options", value: PurchaseRoute())
            } header: {
                Text("settings_purchase_section")
            }
            // Lot 6: what runs the search, and the licences the bundled model and runtime require (Apache 2.0).
            Section {
                Text("settings_search_text")
                    .font(.footnote)
                Text("settings_search_licences")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Text("settings_licences_sqlcipher")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } header: {
                Text("settings_search_section")
            }
            Section {
                Toggle(isOn: $simpleMode) {
                    VStack(alignment: .leading) {
                        Text("settings_simple_mode")
                        Text("settings_simple_mode_hint").font(.footnote).foregroundStyle(.secondary)
                    }
                }
            } header: {
                Text("settings_display_section")
            }
            privacySection
            photoSection
            if viewModel.busy {
                ProgressView("settings_busy")
                    .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle("settings_title")
        .onAppear {
            viewModel.refreshPhotoModel()
            viewModel.refreshPrivacy()
        }
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

    /// « Vie privée » (H3): lock the app and after how long. The keyboard switch of Android has no iOS equivalent.
    @ViewBuilder
    private var privacySection: some View {
        if let privacy = viewModel.privacy {
            Section {
                Toggle(
                    isOn: Binding(get: { privacy.lockEnabled }, set: { viewModel.setLockEnabled($0) })
                ) {
                    VStack(alignment: .leading) {
                        Text("settings_lock")
                        Text(lockHint)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                .disabled(!DeviceOwner.available && !privacy.lockEnabled)
                if privacy.lockEnabled {
                    Picker(
                        "settings_lock_delay",
                        selection: Binding(get: { privacy.lockDelay }, set: { viewModel.setLockDelay($0) })
                    ) {
                        Text("settings_lock_delay_now").tag(LockDelay.immediate)
                        Text("settings_lock_delay_minute").tag(LockDelay.oneMinute)
                        Text("settings_lock_delay_five").tag(LockDelay.fiveMinutes)
                    }
                }
                Text("settings_keyboard_ios")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } header: {
                Text("settings_privacy_section")
            }
        }
    }

    private var lockHint: LocalizedStringKey {
        DeviceOwner.available ? "settings_lock_hint" : "settings_lock_unavailable"
    }

    /// « Photos » (B8, C10): the switches on a phone that can run the photo model, otherwise why not.
    @ViewBuilder
    private var photoSection: some View {
        if let model = viewModel.photoModel {
            Section {
                if !model.bundled {
                    Text("settings_photos_absent").font(.footnote)
                } else if !model.deviceCapable {
                    Text("settings_photos_memory").font(.footnote)
                } else {
                    Text("settings_photos_text").font(.footnote)
                    if model.stoppedWhileLoading {
                        Text("settings_photos_stopped").font(.footnote).foregroundStyle(.red)
                    }
                    if model.loadFailed {
                        Text("settings_photos_failed").font(.footnote).foregroundStyle(.red)
                    }
                    Toggle(isOn: Binding(get: { !model.forceTextOnly }, set: { viewModel.setNamePhotos($0) })) {
                        VStack(alignment: .leading) {
                            Text("settings_photos_model")
                            Text("settings_photos_model_hint").font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                    Toggle(
                        isOn: Binding(
                            get: { model.photoSearch && model.imagesActive },
                            set: { viewModel.setPhotoSearch($0) }
                        )
                    ) {
                        VStack(alignment: .leading) {
                            Text("settings_photos_search")
                            if !model.imagesActive {
                                Text("settings_photos_search_hint").font(.footnote).foregroundStyle(.secondary)
                            }
                        }
                    }
                    .disabled(!model.imagesActive)
                }
            } header: {
                Text("settings_photos_section")
            }
        }
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
                Int(appended),
                Int(skipped),
                Int(photos),
                Int(ignored)
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
