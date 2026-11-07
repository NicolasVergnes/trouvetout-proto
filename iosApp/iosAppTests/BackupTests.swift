@testable import iosApp
import Shared
import XCTest

/// A9 on iOS: CryptoKit against the same published vector as the JVM test (the rest of the format is common code, so
/// a backup from one platform opens on the other), the round trip through the core, the copy to a chosen file.
final class BackupTests: XCTestCase {
    private let cipher = CryptoKitBackupCipher()
    private let keys = MemoryKeychainItem.keys()

    private func data(_ hex: String) -> Data {
        var bytes = Data()
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            bytes.append(UInt8(hex[index ..< next], radix: 16) ?? 0)
            index = next
        }
        return bytes
    }

    /// McGrew and Viega, test case 16 (AES-256, with additional data).
    func testCryptoKitMatchesThePublishedVector() {
        let key = data("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
        let nonce = data("cafebabefacedbaddecaf888")
        let aad = data("feedfacedeadbeeffeedfacedeadbeefabaddad2")
        let plain = data(
            "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72" +
                "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39"
        )
        let sealed = data(
            "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa" +
                "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662" +
                "76fc6ece0f4e1768cddf8853bb2d551b"
        )
        XCTAssertEqual(cipher.seal(key: key, nonce: nonce, aad: aad, plainData: plain), sealed)
        XCTAssertEqual(cipher.open(key: key, nonce: nonce, aad: aad, sealedData: sealed), plain)
        XCTAssertNil(cipher.open(key: key, nonce: nonce, aad: Data(), sealedData: sealed), "other additional data")
        XCTAssertNil(cipher.open(key: key, nonce: nonce, aad: aad, sealedData: Data([1, 2])), "shorter than a tag")
        XCTAssertEqual(cipher.randomBytes(count: 32)?.count, 32)
    }

    private func factory() throws -> SharedFactory {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("tt-\(UUID().uuidString)", isDirectory: true)
        let database = directory.appendingPathComponent("db", isDirectory: true)
        try FileManager.default.createDirectory(at: database, withIntermediateDirectories: true)
        let config = SharedConfig(
            dataDirectory: directory.path,
            deviceInfo: IosDeviceInfo(),
            driverFactory: IosDriverFactory(directory: database.path, keys: keys, fileName: "test.db"),
            imageCodec: UIImageCodec(),
            logger: NoopLogger.shared,
            storageReady: true,
            appVersion: "test",
            captureLang: .fr,
            databaseKeys: keys
        )
        config.backupCrypto = IosBackupCrypto(native: cipher)
        return SharedFactory(config: config)
    }

    func testABackupRestoresAndAWrongKeyDoesNot() async throws {
        let source = try factory()
        let draft = ItemDraft(name: "passeport", description: nil, tags: [], quantity: nil, unit: nil, sensitive: false)
        _ = try await source.itemCommands.create(draft: draft, location: nil, source: .text, rawInput: nil)
        let key = source.backup.drawRecoveryKey()
        let enabled = try await source.backup.enable(recoveryKey: key, location: "bookmark")
        XCTAssertTrue(enabled.boolValue)
        let prepared = try await source.backup.prepare()
        let ready = try XCTUnwrap(prepared as? PreparedBackupReady)
        let bytes = try Data(contentsOf: URL(fileURLWithPath: ready.absolutePath))
        XCTAssertNil(bytes.range(of: Data("passeport".utf8)), "nothing readable")
        try await source.backup.finished(prepared: ready, succeeded: true)
        XCTAssertFalse(FileManager.default.fileExists(atPath: ready.absolutePath), "the private copy is gone")

        let target = try factory()
        let wrong = try await restore(bytes, into: target, key: target.backup.drawRecoveryKey())
        XCTAssertTrue(wrong is BackupRestoreResultCannotOpen)
        let right = try await restore(bytes, into: target, key: key)
        XCTAssertTrue(right is BackupRestoreResultImported)
    }

    private func restore(_ bytes: Data, into target: SharedFactory, key: String) async throws -> BackupRestoreResult {
        let relative = "imports/\(UUID().uuidString).ttbk"
        let url = URL(fileURLWithPath: target.config.dataDirectory).appendingPathComponent(relative)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try bytes.write(to: url)
        return try await target.backup.restore(relativePath: relative, recoveryKey: key)
    }

    private func temporaryFolder() throws -> URL {
        let folder = FileManager.default.temporaryDirectory
            .appendingPathComponent("tt-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }

    /// `startAccessing…` answers false outside a picker, which the real location requires: the app's own folder
    /// stands for the chosen one through a bookmark made by hand.
    private func location(of folder: URL, name: String) throws -> String {
        let bookmark = try folder.bookmarkData().base64EncodedString()
        return "\(name)\n\(bookmark)"
    }

    func testTheCopyReplacesTheFileInOneStepAndLeavesNothingBeside() throws {
        let folder = try temporaryFolder()
        let source = folder.appendingPathComponent("source")
        let chosen = folder.appendingPathComponent("chosen.ttbk")
        try Data("new".utf8).write(to: source)
        try Data("an older and longer backup".utf8).write(to: chosen)
        let location = try location(of: folder, name: "chosen.ttbk")

        XCTAssertTrue(BackupDestination.reachable(location))
        XCTAssertTrue(BackupDestination.write(from: source, to: location))

        XCTAssertEqual(try Data(contentsOf: chosen), Data("new".utf8))
        XCTAssertFalse(FileManager.default.fileExists(atPath: chosen.path + ".tmp"))
    }

    func testAFolderOutOfReachIsSaid() throws {
        let folder = try temporaryFolder()
        let location = try location(of: folder, name: "chosen.ttbk")
        try FileManager.default.removeItem(at: folder)

        XCTAssertFalse(BackupDestination.reachable(location))
        XCTAssertFalse(BackupDestination.reachable("not a location"))
    }

    func testARestoredFileIsCopiedUpToTheLimitOnly() throws {
        let folder = try temporaryFolder()
        let picked = folder.appendingPathComponent("picked")
        let target = folder.appendingPathComponent("imports/copy")
        try Data(repeating: 7, count: 100).write(to: picked)

        XCTAssertThrowsError(try BackupDestination.copyBounded(picked, to: target, limit: 99))
        XCTAssertFalse(FileManager.default.fileExists(atPath: target.path), "nothing partial stays")

        try BackupDestination.copyBounded(picked, to: target, limit: 100)
        XCTAssertEqual(try Data(contentsOf: target).count, 100)
    }
}
