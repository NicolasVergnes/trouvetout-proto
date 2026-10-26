import Foundation

/// Private directories of the app. Everything the core writes is excluded from iCloud/iTunes backups:
/// the only backup channel is the app's own encrypted one (A9).
enum AppDirectories {
    enum Failure: Error {
        case noApplicationSupportDirectory
        case backupExclusionNotApplied
        /// The directory is fine but the core could not open its store (full disk, corrupt file).
        case storeUnavailable
    }

    /// The data directory and, when the backup exclusion could not be created or verified, the reason.
    /// The directory is still the real one under Application Support (never a purgeable temporary folder,
    /// the journal is the source of truth): with a failure set, the data layer must refuse to open the
    /// store and the UI must say so (wired from lot 1).
    struct Prepared {
        let url: URL
        let failure: Error?
    }

    static func prepareDataDirectory() -> Prepared {
        let url: URL
        do {
            url = try createDirectory()
        } catch {
            return Prepared(url: FileManager.default.temporaryDirectory, failure: error)
        }
        do {
            try excludeFromBackup(url)
        } catch {
            return Prepared(url: url, failure: error)
        }
        return Prepared(url: url, failure: nil)
    }

    /// Throwing convenience for callers that cannot work without a verified directory (tests, tools).
    static func dataDirectory() throws -> URL {
        let prepared = prepareDataDirectory()
        if let failure = prepared.failure {
            throw failure
        }
        return prepared.url
    }

    private static func createDirectory() throws -> URL {
        let candidates = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)
        guard let support = candidates.first else {
            throw Failure.noApplicationSupportDirectory
        }
        let bundleId = Bundle.main.bundleIdentifier ?? "app.trouvetout.proto"
        let dir = support.appendingPathComponent(bundleId, isDirectory: true)
        try FileManager.default.createDirectory(
            at: dir,
            withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        )
        return dir
    }

    /// Sets the exclusion flag and reads it back: an unverified flag would let the database reach iCloud.
    private static func excludeFromBackup(_ url: URL) throws {
        var dir = url
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try dir.setResourceValues(values)
        let applied = try dir.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup ?? false
        guard applied else {
            throw Failure.backupExclusionNotApplied
        }
    }
}
