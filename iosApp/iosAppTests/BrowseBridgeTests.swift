@testable import iosApp
import Shared
import XCTest

/// The places read model through the Swift side of the bridge (C7, D2, D6) and the merge exclusion rule.
final class BrowseBridgeTests: XCTestCase {
    private var directory: URL!
    private var factory: SharedFactory!

    override func setUp() async throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("tt-browse-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let config = SharedConfig(
            dataDirectory: directory.path,
            deviceInfo: IosDeviceInfo(),
            driverFactory: IosDriverFactory(
                directory: directory.appendingPathComponent("db").path,
                fileName: "test.db"
            ),
            imageCodec: UIImageCodec(),
            logger: NoopLogger.shared,
            storageReady: true,
            appVersion: "test",
            captureLang: .fr
        )
        factory = SharedFactory(config: config)
        _ = try await factory.warmUp()
    }

    override func tearDown() {
        factory.close()
        try? FileManager.default.removeItem(at: directory)
    }

    func testTemplatesExistInBothLanguages() {
        XCTAssertFalse(PlaceTemplates.shared.forLang(lang: .fr).isEmpty)
        XCTAssertFalse(PlaceTemplates.shared.forLang(lang: .en).isEmpty)
    }

    func testTemplateCreatesAPlaceWithItsRoomsAndDescendantsAreExcludedFromMerge() async throws {
        let template = try XCTUnwrap(PlaceTemplates.shared.forLang(lang: .fr).first)
        let created = try await factory.placeCommands.createFromTemplate(template: template, source: .text)
        XCTAssertTrue(created is CommandResultDone)

        let scope = factory.uiScope
        var places: [Place] = []
        let watcher = FlowWatcher<NSArray>(flow: factory.placeRepository.observeActive(), scope: scope)
        for try await value in stream(watcher) {
            places = (value as? [Place]) ?? []
            break
        }
        let root = try XCTUnwrap(places.first { $0.parentId == nil })
        XCTAssertEqual(root.name, template.name)
        XCTAssertEqual(places.count, 1 + template.children.count)

        let excluded = descendants(of: root.id, in: places)
        XCTAssertEqual(excluded.count, places.count, "the root and every room are excluded as merge targets")
        if let room = places.first(where: { $0.parentId != nil }) {
            XCTAssertEqual(descendants(of: room.id, in: places), [room.id])
        }
    }
}
