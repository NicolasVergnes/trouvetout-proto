import Foundation
@testable import iosApp
import Shared
import XCTest

/// A Keychain item kept in memory, for the tests that only need an encrypted database.
final class MemoryKeychainItem: NSObject, KeychainItem {
    private var stored: Data?

    static func keys() -> IosDatabaseKeys {
        IosDatabaseKeys(item: MemoryKeychainItem())
    }

    func read() -> KeychainRead {
        KeychainRead(data: stored, failed: false)
    }

    func write(data: Data) -> Bool {
        guard stored == nil else { return false }
        stored = data
        return true
    }

    func remove() {
        stored = nil
    }
}

/// H2 on iOS (ADR-023): the app links SQLCipher, the key lives in the Keychain, a plain database left by lots 1
/// to 7 is encrypted once without loss, and the total wipe deletes the key.
final class DatabaseEncryptionTests: XCTestCase {
    private var directory: URL!
    private var item: KeychainDatabaseKey!
    private var keys: IosDatabaseKeys!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        item = KeychainDatabaseKey(service: "app.trouvetout.proto.tests", account: UUID().uuidString)
        keys = IosDatabaseKeys(item: item)
    }

    override func tearDownWithError() throws {
        item.remove()
        try? FileManager.default.removeItem(at: directory)
    }

    private var path: String {
        directory.appendingPathComponent("test.db").path
    }

    private func literal() throws -> String {
        DatabaseEncryption.shared.keyLiteral(key: keys.obtain())
    }

    func testAPlainDatabaseIsEncryptedOnceWithoutLoss() throws {
        DatabaseFixtures.shared.writePlain(path: path, rows: 1000)
        XCTAssertTrue(DatabaseFixtures.shared.isPlain(path: path))

        let driver = IosDriverFactory(directory: directory.path, keys: keys, fileName: "test.db").create()
        driver.close()

        XCTAssertFalse(DatabaseFixtures.shared.isPlain(path: path), "no plain SQLite header any more")
        let key = try literal()
        XCTAssertEqual(DatabaseFixtures.shared.probeCount(path: path, keyLiteral: key), 1000)
        XCTAssertEqual(DatabaseFixtures.shared.probeCount(path: path, keyLiteral: nil), -1, "unreadable without key")
        XCTAssertFalse(FileManager.default.fileExists(atPath: path + ".enc"))
        XCTAssertNotNil(item.stored, "the key is in the Keychain")
    }

    func testTheWipeDeletesTheKeyAndALostKeyIsSaid() async throws {
        let first = makeShared()
        let opened = try await first.warmUp()
        XCTAssertTrue(opened.boolValue)
        XCTAssertTrue(keys.exists())

        // The key disappears while the encrypted file stays (Keychain reset): the store refuses to open.
        item.remove()
        let second = makeShared()
        let reopened = try await second.warmUp()
        XCTAssertFalse(reopened.boolValue)
        XCTAssertEqual(second.storeFailure, .keyLost)
        XCTAssertNil(item.stored, "no new key that would hide the loss")

        // H6 still works on a store that cannot open, and leaves nothing behind.
        _ = try await second.wipeAllData()
        XCTAssertFalse(keys.exists())
        let third = makeShared()
        let fresh = try await third.warmUp()
        XCTAssertTrue(fresh.boolValue, "a new key and an empty database after the wipe")
        _ = try await third.wipeAllData()
        XCTAssertNil(item.stored, "the wipe deletes the Keychain item")
    }

    private func makeShared() -> SharedFactory {
        SharedFactory(
            config: SharedConfig(
                dataDirectory: directory.path,
                deviceInfo: IosDeviceInfo(),
                driverFactory: IosDriverFactory(
                    directory: directory.appendingPathComponent("db").path,
                    keys: keys,
                    fileName: "trouvetout.db"
                ),
                imageCodec: UIImageCodec(),
                logger: NoopLogger.shared,
                storageReady: true,
                appVersion: "test",
                captureLang: .fr,
                databaseKeys: keys
            )
        )
    }
}
