import Foundation

/// Private directories of the app. Everything the core writes is excluded from iCloud/iTunes backups:
/// the only backup channel is the app's own encrypted one (A9).
enum AppDirectories {
    static func dataDirectory() -> URL {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let bundleId = Bundle.main.bundleIdentifier ?? "app.trouvetout.proto"
        let dir = support.appendingPathComponent(bundleId, isDirectory: true)
        ensureExcludedFromBackup(dir)
        return dir
    }

    private static func ensureExcludedFromBackup(_ dir: URL) {
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            var url = dir
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try url.setResourceValues(values)
        } catch {
            // Never log paths or contents; a failure here only affects the backup flag.
            assertionFailure("Could not prepare the data directory")
        }
    }
}
