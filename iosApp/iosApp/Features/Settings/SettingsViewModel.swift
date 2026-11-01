import Foundation
import Shared

/// Outcome of the last settings action, rendered by the view.
enum SettingsMessage: Equatable {
    case exported(events: Int64, photos: Int64)
    case imported(appended: Int32, skipped: Int32, photos: Int32, ignored: Int32)
    case importRefused(reason: ImportRejection)
    case wiped
    case failed
}

/// Settings actions of lot 2: export (A7), import (A8), total wipe (H6). No business rule here.
@MainActor
final class SettingsViewModel: ObservableObject {
    @Published private(set) var busy = false
    @Published var message: SettingsMessage?
    /// A fresh export to hand to the share sheet.
    @Published var exportURL: URL?
    /// The photo side of the model (B8, C10, ADR-022): what this phone can do and the user's two settings.
    @Published private(set) var photoModel: PhotoModelState?

    private let container: AppContainer

    init(container: AppContainer) {
        self.container = container
    }

    /// Reads the current photo settings (the core's state flow), when the screen appears and after a change.
    func refreshPhotoModel() {
        guard container.ready else { return }
        photoModel = container.shared.modelSettings.state.value as? PhotoModelState
    }

    /// « Name objects from their photo » off = text model only: less memory, photos still attached.
    func setNamePhotos(_ on: Bool) {
        Task { [self] in
            try? await container.shared.modelSettings.setForceTextOnly(value: !on)
            refreshPhotoModel()
        }
    }

    func setPhotoSearch(_ on: Bool) {
        Task { [self] in
            try? await container.shared.modelSettings.setPhotoSearch(value: on)
            refreshPhotoModel()
        }
    }

    func export() {
        perform { [self] in
            let result = try await container.shared.exportService.export()
            exportURL = URL(fileURLWithPath: result.absolutePath)
            message = .exported(events: result.counts.events, photos: result.counts.photos)
        }
    }

    /// Copies the picked document into the private import directory (the core never reads foreign URLs),
    /// hands it to the core, then removes the copy whatever the outcome.
    func importArchive(from picked: URL) {
        perform { [self] in
            let shared = container.shared
            let relativePath = "\(SharedFactory.companion.IMPORT_DIRECTORY)/\(UUID().uuidString.lowercased()).zip"
            let target = URL(fileURLWithPath: shared.config.dataDirectory).appendingPathComponent(relativePath)
            let fileManager = FileManager.default
            try fileManager.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
            defer {
                try? fileManager.removeItem(at: target)
            }
            try await copyBounded(picked, to: target)
            let result = try await shared.importService.import(relativePath: relativePath)
            if let done = result as? ImportResultDone {
                let report = done.report
                message = .imported(
                    appended: report.eventsAppended,
                    skipped: report.eventsSkipped,
                    photos: report.photosImported,
                    ignored: report.dropped
                )
            } else if let rejected = result as? ImportResultRejected {
                message = .importRefused(reason: rejected.reason)
            }
        }
    }

    func wipeAll() {
        perform { [self] in
            defer {
                // Whatever happened, the spent factory is replaced, the preferences and the stale share link dropped.
                UiPrefs.clear()
                container.recreateShared()
                exportURL = nil
            }
            _ = try await container.shared.wipeAllData()
            message = .wiped
        }
    }

    /// Copies a picked document into the private container off the main actor, refusing oversized files; the
    /// security scope is held only for the copy.
    private nonisolated func copyBounded(_ picked: URL, to target: URL) async throws {
        try await Task.detached(priority: .userInitiated) {
            let scoped = picked.startAccessingSecurityScopedResource()
            defer {
                if scoped {
                    picked.stopAccessingSecurityScopedResource()
                }
            }
            let size = try picked.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            guard Int64(size) <= ExportFormatKt.MAX_ARCHIVE_BYTES else {
                throw CocoaError(.fileReadTooLarge)
            }
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
            busy = false
        }
    }
}
