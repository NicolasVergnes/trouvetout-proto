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

    private let container: AppContainer

    init(container: AppContainer) {
        self.container = container
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
            let scoped = picked.startAccessingSecurityScopedResource()
            defer {
                if scoped { picked.stopAccessingSecurityScopedResource() }
                try? fileManager.removeItem(at: target)
            }
            try fileManager.copyItem(at: picked, to: target)
            let result = try await shared.importService.import(relativePath: relativePath)
            if let done = result as? ImportResultDone {
                let report = done.report
                message = .imported(
                    appended: report.eventsAppended,
                    skipped: report.eventsSkipped,
                    photos: report.photosImported,
                    ignored: report.unknownPayloads + report.invalidLines + report.photosRejected
                )
            } else if let rejected = result as? ImportResultRejected {
                message = .importRefused(reason: rejected.reason)
            }
        }
    }

    func wipeAll() {
        perform { [self] in
            _ = try await container.shared.wipeAllData()
            container.recreateShared()
            message = .wiped
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
            busy = false
        }
    }
}
