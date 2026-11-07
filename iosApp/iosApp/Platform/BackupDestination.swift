import Foundation
import Shared
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// The folder the user chose for the backups (A9), in iCloud Drive or elsewhere: the app has no network, the file
/// provider sends the file. A folder, because the system gives lasting access to it and a new file can then replace
/// the old one in one step: a copy cut half way leaves the previous backup intact. The location the core keeps is the
/// file's name and a bookmark of the folder, which is what lasts across launches.
enum BackupDestination {
    enum Failure: Error {
        case noAccess
        case tooLarge
    }

    private static let separator: Character = "\n"
    private static let copyChunkBytes = 1 << 20

    /// A location for a new backup in `folder`: a name of its own, so that it never overwrites an older backup, which
    /// may be the only copy (a new phone that has not restored yet would replace it with an empty one).
    static func location(inFolder folder: URL, now: Date = Date()) throws -> String {
        guard folder.startAccessingSecurityScopedResource() else {
            throw Failure.noAccess
        }
        defer {
            folder.stopAccessingSecurityScopedResource()
        }
        let stamp = Int64(now.timeIntervalSince1970)
        return "backup-\(stamp).ttbk\(separator)\(try folder.bookmarkData().base64EncodedString())"
    }

    /// Whether the folder can still be reached; before spending an export on it.
    static func reachable(_ location: String) -> Bool {
        guard let (_, folder) = parse(location) else {
            return false
        }
        let scoped = folder.startAccessingSecurityScopedResource()
        defer {
            if scoped {
                folder.stopAccessingSecurityScopedResource()
            }
        }
        return FileManager.default.fileExists(atPath: folder.path)
    }

    /// Writes beside the target, then replaces it under the provider's coordination. False when access is gone or the
    /// provider refused: said in the settings, and tried again later.
    static func write(from source: URL, to location: String) -> Bool {
        guard let (name, folder) = parse(location) else {
            return false
        }
        let scoped = folder.startAccessingSecurityScopedResource()
        defer {
            if scoped {
                folder.stopAccessingSecurityScopedResource()
            }
        }
        var coordinationError: NSError?
        var copied = false
        NSFileCoordinator().coordinate(
            writingItemAt: folder.appendingPathComponent(name),
            options: .forReplacing,
            error: &coordinationError
        ) { target in
            copied = (try? replace(target, with: source)) != nil
        }
        return coordinationError == nil && copied
    }

    /// Copies a picked file into the private directory under the provider's coordination (on a new phone it may not
    /// be downloaded yet), stopping at `limit`: what the provider says of its size is no guarantee.
    static func copyBounded(_ picked: URL, to target: URL, limit: Int64) throws {
        let scoped = picked.startAccessingSecurityScopedResource()
        defer {
            if scoped {
                picked.stopAccessingSecurityScopedResource()
            }
        }
        try FileManager.default.createDirectory(
            at: target.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        var coordinationError: NSError?
        var copyError: Error?
        NSFileCoordinator().coordinate(readingItemAt: picked, options: [], error: &coordinationError) { url in
            do {
                try copy(url, to: target, limit: limit)
            } catch {
                copyError = error
            }
        }
        if let failure = coordinationError ?? copyError {
            try? FileManager.default.removeItem(at: target)
            throw failure
        }
    }

    private static func parse(_ location: String) -> (name: String, folder: URL)? {
        let parts = location.split(separator: separator, maxSplits: 1)
        guard parts.count == 2, let bookmark = Data(base64Encoded: String(parts[1])) else {
            return nil
        }
        var stale = false
        guard let folder = try? URL(resolvingBookmarkData: bookmark, bookmarkDataIsStale: &stale) else {
            return nil
        }
        return (String(parts[0]), folder)
    }

    private static func replace(_ target: URL, with source: URL) throws {
        let fileManager = FileManager.default
        let beside = target.deletingLastPathComponent().appendingPathComponent(target.lastPathComponent + ".tmp")
        try? fileManager.removeItem(at: beside)
        defer {
            try? fileManager.removeItem(at: beside)
        }
        try copy(source, to: beside, limit: .max)
        if fileManager.fileExists(atPath: target.path) {
            _ = try fileManager.replaceItemAt(target, withItemAt: beside)
        } else {
            try fileManager.moveItem(at: beside, to: target)
        }
    }

    private static func copy(_ source: URL, to target: URL, limit: Int64) throws {
        let input = try FileHandle(forReadingFrom: source)
        defer {
            try? input.close()
        }
        guard FileManager.default.createFile(atPath: target.path, contents: nil) else {
            throw CocoaError(.fileWriteUnknown)
        }
        let output = try FileHandle(forWritingTo: target)
        defer {
            try? output.close()
        }
        var total: Int64 = 0
        var done = false
        while !done {
            // Up to a thousand blocks in one task: each one drains its own pool.
            try autoreleasepool {
                guard let chunk = try input.read(upToCount: copyChunkBytes), !chunk.isEmpty else {
                    done = true
                    return
                }
                total += Int64(chunk.count)
                guard total <= limit else {
                    throw Failure.tooLarge
                }
                try output.write(contentsOf: chunk)
            }
        }
        try output.synchronize()
    }
}

/// The system's picker, to choose the folder of the backups.
struct BackupFolderPicker: UIViewControllerRepresentable {
    let onPicked: (URL?) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onPicked: onPicked)
    }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.folder])
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_: UIDocumentPickerViewController, context _: Context) {}

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        private let onPicked: (URL?) -> Void

        init(onPicked: @escaping (URL?) -> Void) {
            self.onPicked = onPicked
        }

        func documentPicker(_: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            onPicked(urls.first)
        }

        func documentPickerWasCancelled(_: UIDocumentPickerViewController) {
            onPicked(nil)
        }
    }
}
