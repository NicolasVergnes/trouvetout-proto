@testable import iosApp
import Shared
import XCTest

final class SharedBridgeTests: XCTestCase {
    func testCoreExposesNeutralIdentity() {
        XCTAssertEqual(AppInfo.shared.NAME, "TrouveTout")
        XCTAssertEqual(AppInfo.shared.URL_SCHEME, "trouvetout-proto")
    }

    func testDeviceInfoReportsMemory() {
        let info = IosDeviceInfo()
        XCTAssertGreaterThan(info.totalRamBytes, 0)
        XCTAssertEqual(info.osName, "iOS")
    }

    func testDataDirectoryIsExcludedFromBackup() throws {
        let dir = try AppDirectories.dataDirectory()
        let values = try dir.resourceValues(forKeys: [.isExcludedFromBackupKey])
        XCTAssertEqual(values.isExcludedFromBackup, true)
    }

    // MARK: - Core factory

    private func temporaryDirectory() -> URL {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("tt-\(UUID().uuidString)", isDirectory: true)
        let database = directory.appendingPathComponent("db", isDirectory: true)
        try? FileManager.default.createDirectory(at: database, withIntermediateDirectories: true)
        return directory
    }

    private func factory(in directory: URL, storageReady: Bool = true) -> SharedFactory {
        let config = SharedConfig(
            dataDirectory: directory.path,
            deviceInfo: IosDeviceInfo(),
            driverFactory: IosDriverFactory(
                directory: directory.appendingPathComponent("db").path,
                fileName: "test.db"
            ),
            imageCodec: UIImageCodec(),
            logger: NoopLogger.shared,
            storageReady: storageReady,
            appVersion: "test"
        )
        return SharedFactory(config: config)
    }

    func testFactoryOpensTheDatabaseInTheGivenDirectoryAndKeepsTheDeviceId() async throws {
        let directory = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }

        let first = factory(in: directory)
        let ready = try await first.warmUp()
        XCTAssertTrue(ready.boolValue)
        let deviceId = first.deviceId
        XCTAssertEqual(deviceId.count, 26)
        XCTAssertTrue(FileManager.default.fileExists(atPath: directory.appendingPathComponent("db/test.db").path))
        first.close()

        let second = factory(in: directory)
        XCTAssertEqual(second.deviceId, deviceId, "the installation identity survives a reopen (ADR-005)")
        second.close()
    }

    func testFactoryRefusesToOpenTheStoreWhenStorageIsNotReady() async throws {
        let directory = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }

        let factory = factory(in: directory, storageReady: false)
        let ready = try await factory.warmUp()

        XCTAssertFalse(ready.boolValue)
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.appendingPathComponent("db/test.db").path))
        factory.close()
    }

    func testExportWipeImportRoundTrip() async throws {
        let directory = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }

        let first = factory(in: directory)
        let draft = ItemDraft(name: "item-a", description: nil, tags: [], quantity: nil, unit: nil, sensitive: false)
        let created = try await first.itemCommands.create(draft: draft, location: nil, source: .text, rawInput: nil)
        XCTAssertTrue(created is CommandResultDone)
        let export = try await first.exportService.export()
        XCTAssertEqual(export.counts.items, 1)
        let archive = try Data(contentsOf: URL(fileURLWithPath: export.absolutePath))

        let removed = try await first.wipeAllData()
        XCTAssertTrue(removed.contains("db"))
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.appendingPathComponent("db/test.db").path))

        let second = factory(in: directory)
        let imports = directory.appendingPathComponent(SharedFactory.companion.IMPORT_DIRECTORY, isDirectory: true)
        try FileManager.default.createDirectory(at: imports, withIntermediateDirectories: true)
        try archive.write(to: imports.appendingPathComponent("a.zip"))
        let importPath = "\(SharedFactory.companion.IMPORT_DIRECTORY)/a.zip"
        let result = try await second.importService.import(relativePath: importPath)
        let done = try XCTUnwrap(result as? ImportResultDone)
        XCTAssertEqual(done.report.eventsAppended, Int32(export.counts.events))
        let again = try await second.exportService.export()
        XCTAssertEqual(again.counts.items, 1)
        XCTAssertEqual(again.counts.events, export.counts.events)
        second.close()
    }

    // MARK: - Flow bridge

    func testStreamDeliversEveryValueThenCompletes() async throws {
        let scope = FlowFixtures.shared.backgroundScope()
        defer { FlowFixtures.shared.cancelScope(scope: scope) }
        var received: [Int] = []
        for try await value in stream(FlowFixtures.shared.counting(count: 3, scope: scope)) {
            received.append(value.intValue)
        }
        XCTAssertEqual(received, [1, 2, 3])
    }

    func testStreamPropagatesKotlinFailure() async {
        let scope = FlowFixtures.shared.backgroundScope()
        defer { FlowFixtures.shared.cancelScope(scope: scope) }
        do {
            for try await _ in stream(FlowFixtures.shared.failing(message: "boom", scope: scope)) {
                XCTFail("No value expected")
            }
            XCTFail("An error was expected")
        } catch {
            XCTAssertEqual(error.localizedDescription, "boom")
        }
    }

    func testLeavingTheLoopCancelsTheKotlinCollection() async throws {
        let scope = FlowFixtures.shared.backgroundScope()
        defer { FlowFixtures.shared.cancelScope(scope: scope) }
        var count = 0
        for try await _ in stream(FlowFixtures.shared.endless(intervalMillis: 5, scope: scope)) {
            count += 1
            if count == 3 {
                break
            }
        }
        XCTAssertEqual(count, 3)
        // Cancellation is asynchronous on the Kotlin side: wait (bounded) until the collector is gone.
        for _ in 0 ..< 100 where FlowFixtures.shared.activeEndlessCollectors() != 0 {
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        XCTAssertEqual(FlowFixtures.shared.activeEndlessCollectors(), 0)
    }

    func testCancellingTheScopeFinishesTheStream() async throws {
        let scope = FlowFixtures.shared.backgroundScope()
        let task = Task {
            var count = 0
            for try await _ in stream(FlowFixtures.shared.endless(intervalMillis: 5, scope: scope)) {
                count += 1
                if count == 2 {
                    FlowFixtures.shared.cancelScope(scope: scope)
                }
            }
            return count
        }
        let count = try await task.value
        XCTAssertGreaterThanOrEqual(count, 2)
    }
}
