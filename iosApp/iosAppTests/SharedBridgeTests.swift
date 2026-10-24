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
}
