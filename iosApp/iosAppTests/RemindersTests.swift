@testable import iosApp
import PDFKit
import Shared
import UserNotifications
import XCTest

/// A notification center that only records what it is asked (E2, H4, H6).
private final class FakeCenter: NotificationCenterClient {
    var requests: [String: UNNotificationRequest] = [:]
    var shown: [(identifier: String, named: Bool)] = []
    var removedDelivered: [String] = []
    var cleared = false

    func pendingIdentifiers() async -> [String] { Array(requests.keys) }
    func delivered() async -> [(identifier: String, named: Bool)] { shown }
    func add(_ request: UNNotificationRequest) async throws { requests[request.identifier] = request }
    func removePending(_ identifiers: [String]) { identifiers.forEach { requests[$0] = nil } }
    func removeDelivered(_ identifiers: [String]) { removedDelivered += identifiers }

    func removeAll() {
        requests = [:]
        shown = []
        cleared = true
    }

    func status() async -> UNAuthorizationStatus { .notDetermined }
    func requestAuthorization() async -> Bool { true }
}

@MainActor
final class RemindersTests: XCTestCase {
    private let keys = MemoryKeychainItem.keys()
    private var directory: URL!
    private var shared: SharedFactory!

    override func setUp() async throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("tt-\(UUID().uuidString)")
        let database = directory.appendingPathComponent("db")
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
        shared = SharedFactory(config: config)
        let ready = try await shared.warmUp()
        XCTAssertTrue(ready.boolValue)
    }

    override func tearDown() async throws {
        shared.close()
        try? FileManager.default.removeItem(at: directory)
    }

    private func item(_ name: String, sensitive: Bool = false) async throws -> String {
        let draft = ItemDraft(name: name, description: nil, tags: [], quantity: nil, unit: nil, sensitive: sensitive)
        let done = try XCTUnwrap(
            try await shared.itemCommands.create(draft: draft, location: nil, source: .text, rawInput: nil)
                as? CommandResultDone
        )
        return try XCTUnwrap((done.events.first?.payload as? EventPayloadItemCreated)?.itemId)
    }

    private func remind(_ itemId: String, in seconds: TimeInterval) async throws -> String {
        let millis = Int64((Date().timeIntervalSince1970 + seconds) * 1000)
        let result = try await shared.attachmentCommands.setReminder(
            itemId: itemId,
            fireAt: InstantsKt.instantOf(epochMillis: millis),
            kind: .date,
            source: .text,
            rawInput: nil
        )
        let done = try XCTUnwrap(result as? CommandResultDone)
        return try XCTUnwrap((done.events.first?.payload as? EventPayloadReminderSet)?.reminderId)
    }

    func testRemindersAreScheduledWithTheNameOnlyAndGenericWhenSensitive() async throws {
        let passport = try await item("passeport")
        let papers = try await item("papiers", sensitive: true)
        let first = try await remind(passport, in: 3600)
        let second = try await remind(papers, in: 7200)
        let center = FakeCenter()
        let scheduler = ReminderScheduler(center: center)
        await scheduler.reconcile(try await shared.reminders.schedule())

        let named = try XCTUnwrap(center.requests[ReminderScheduler.prefix + first])
        XCTAssertTrue(named.content.body.contains("passeport"))
        XCTAssertEqual(named.content.categoryIdentifier, ReminderScheduler.categoryIdentifier)
        let masked = try XCTUnwrap(center.requests[ReminderScheduler.prefix + second])
        XCTAssertEqual(masked.content.body, NSLocalizedString("notif_generic", comment: ""))
        XCTAssertFalse(masked.content.body.contains("papiers"), "a sensitive item is never named")
        XCTAssertEqual(masked.content.userInfo[ReminderScheduler.namedKey] as? Bool, false)
        XCTAssertEqual(masked.content.title, "", "no title, no place")
    }

    func testABinnedItemIsUnscheduledAndANamedNotificationOfASensitiveItemWithdrawn() async throws {
        let tent = try await item("tente")
        let drill = try await item("perceuse")
        let tentReminder = try await remind(tent, in: 3600)
        let drillReminder = try await remind(drill, in: 3600)
        let center = FakeCenter()
        let scheduler = ReminderScheduler(center: center)
        await scheduler.reconcile(try await shared.reminders.schedule())
        XCTAssertEqual(center.requests.count, 2)

        _ = try await shared.itemCommands.delete(itemId: tent, source: .text)
        let patch = ItemPatch(
            name: nil,
            description: nil,
            clearDescription: false,
            tags: nil,
            quantity: nil,
            clearQuantity: false,
            unit: nil,
            clearUnit: false,
            sensitive: KotlinBoolean(value: true),
            pinned: nil
        )
        _ = try await shared.itemCommands.update(itemId: drill, patch: patch, source: .text)
        center.shown = [(ReminderScheduler.prefix + drillReminder, true)]
        await scheduler.reconcile(try await shared.reminders.schedule())
        XCTAssertNil(center.requests[ReminderScheduler.prefix + tentReminder], "the bin: no notification")
        XCTAssertEqual(center.removedDelivered, [ReminderScheduler.prefix + drillReminder])
        XCTAssertEqual(
            center.requests[ReminderScheduler.prefix + drillReminder]?.content.body,
            NSLocalizedString("notif_generic", comment: "")
        )

        scheduler.clear()
        XCTAssertTrue(center.cleared, "H6: every notification goes with the data")
    }

    func testAtMostFiftyAreScheduledAndPastOnesNever() async throws {
        let tent = try await item("tente")
        for index in 0 ..< 55 {
            _ = try await remind(tent, in: 3600 + Double(index) * 60)
        }
        let past = try await remind(tent, in: -60)
        let center = FakeCenter()
        await ReminderScheduler(center: center).reconcile(try await shared.reminders.schedule())
        XCTAssertEqual(center.requests.count, 50)
        XCTAssertNil(center.requests[ReminderScheduler.prefix + past])
    }

    func testThePrintedInventoryIsAPdfWithTheItemsC16() async throws {
        let created = try await shared.placeCommands.create(
            name: "Cave",
            parentId: nil,
            kind: .root,
            aliases: [],
            source: .text,
            rawInput: nil
        )
        let cave = try XCTUnwrap(
            ((created as? CommandResultDone)?.events.first?.payload as? EventPayloadPlaceCreated)?.placeId
        )
        let draft = ItemDraft(name: "perceuse", description: nil, tags: [], quantity: nil, unit: nil, sensitive: false)
        _ = try await shared.itemCommands.create(
            draft: draft,
            location: LocationRefInPlace(placeId: cave),
            source: .text,
            rawInput: nil
        )
        let inventory = try await shared.browse.placeInventory(placeId: cave, includeSensitive: false)
        let printed = try XCTUnwrap(inventory)
        let pdf = try XCTUnwrap(PDFDocument(data: InventoryPrint.pdf(printed)))
        XCTAssertGreaterThanOrEqual(pdf.pageCount, 1)
        let text = pdf.string ?? ""
        XCTAssertTrue(text.contains("Cave"))
        XCTAssertTrue(text.contains("perceuse"))
    }
}
