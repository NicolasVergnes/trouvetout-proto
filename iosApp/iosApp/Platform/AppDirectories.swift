import Foundation

/// Private directories of the app. Everything the core writes is excluded from iCloud/iTunes backups:
/// the only backup channel is the app's own encrypted one (A9).
enum AppDirectories {
    enum Failure: Error {
        case noApplicationSupportDirectory
        case backupExclusionNotApplied
    }

    /// Creates the data directory if needed and returns it, after verifying that the backup exclusion
    /// flag is really set (an unverified flag would let the database reach iCloud backups).
    static func dataDirectory() throws -> URL {
        let candidates = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)
        guard let support = candidates.first else {
            throw Failure.noApplicationSupportDirectory
        }
        let bundleId = Bundle.main.bundleIdentifier ?? "app.trouvetout.proto"
        var dir = support.appendingPathComponent(bundleId, isDirectory: true)
        try FileManager.default.createDirectory(
            at: dir,
            withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        )
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try dir.setResourceValues(values)
        let applied = try dir.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup ?? false
        guard applied else {
            throw Failure.backupExclusionNotApplied
        }
        return dir
    }
}
