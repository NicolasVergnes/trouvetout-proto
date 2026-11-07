import Foundation
import SwiftUI
import UIKit

/// The file the user chose for the backups (A9), in iCloud Drive or elsewhere: the app has no network, the file
/// provider sends it. It is kept as a bookmark, which is what lasts across launches.
enum BackupDestination {
    static func bookmark(of url: URL) throws -> String {
        let scoped = url.startAccessingSecurityScopedResource()
        defer {
            if scoped {
                url.stopAccessingSecurityScopedResource()
            }
        }
        return try url.bookmarkData().base64EncodedString()
    }

    /// Copies [source] over the file, under the provider's coordination. False when access is gone, the file is gone
    /// or the provider refused: said in the settings, and tried again at the next return.
    static func write(from source: URL, to location: String) -> Bool {
        guard
            let bookmark = Data(base64Encoded: location),
            let target = resolve(bookmark)
        else {
            return false
        }
        let scoped = target.startAccessingSecurityScopedResource()
        defer {
            if scoped {
                target.stopAccessingSecurityScopedResource()
            }
        }
        var coordinationError: NSError?
        var copied = false
        NSFileCoordinator().coordinate(
            writingItemAt: target,
            options: .forReplacing,
            error: &coordinationError
        ) { url in
            copied = (try? overwrite(url, with: source)) != nil
        }
        return coordinationError == nil && copied
    }

    private static func resolve(_ bookmark: Data) -> URL? {
        var stale = false
        return try? URL(resolvingBookmarkData: bookmark, bookmarkDataIsStale: &stale)
    }

    private static func overwrite(_ target: URL, with source: URL) throws {
        let input = try FileHandle(forReadingFrom: source)
        defer {
            try? input.close()
        }
        let output = try FileHandle(forWritingTo: target)
        defer {
            try? output.close()
        }
        while let chunk = try input.read(upToCount: copyChunkBytes), !chunk.isEmpty {
            try output.write(contentsOf: chunk)
        }
        try output.truncate(atOffset: output.offset())
        try output.synchronize()
    }

    private static let copyChunkBytes = 1 << 20
}

/// The system's picker, to create the backup file where the user wants it.
struct BackupFilePicker: UIViewControllerRepresentable {
    let fileName: String
    let onPicked: (URL?) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onPicked: onPicked)
    }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let empty = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)
        FileManager.default.createFile(atPath: empty.path, contents: Data())
        let picker = UIDocumentPickerViewController(forExporting: [empty], asCopy: true)
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
