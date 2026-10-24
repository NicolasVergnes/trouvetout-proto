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

    func testFactoryBuildsFromConfig() {
        let config = SharedConfig(
            dataDirectory: FileManager.default.temporaryDirectory.path,
            deviceInfo: IosDeviceInfo(),
            logger: NoopLogger.shared
        )
        let factory = SharedFactory(config: config)
        XCTAssertEqual(factory.appInfo.NAME, "TrouveTout")
        factory.close()
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
