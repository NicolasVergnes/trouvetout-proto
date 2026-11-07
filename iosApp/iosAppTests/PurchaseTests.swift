@testable import iosApp
import Shared
import StoreKit
import StoreKitTest
import XCTest

/// J4, J5 with StoreKit's local test store (no Apple account): both products, a subscription that ends, the Keychain
/// date of the trial.
@MainActor
final class PurchaseTests: XCTestCase {
    private var session: SKTestSession?
    private let gateway = StoreKitPurchaseGateway()

    override func setUp() async throws {
        let session = try SKTestSession(configurationFileNamed: "TrouveTout")
        session.resetToDefaultState()
        session.disableDialogs = true
        session.clearTransactions()
        self.session = session
    }

    override func tearDown() async throws {
        session?.clearTransactions()
        session = nil
    }

    /// The test store applies a refund or an expiry a moment later.
    private func entitledOnceSettled(expected: Bool) async -> Bool? {
        var answer = await gateway.entitled()
        for _ in 0 ..< 50 where answer != expected {
            try? await Task.sleep(nanoseconds: 200_000_000)
            answer = await gateway.entitled()
        }
        return answer
    }

    func testBothProductsAreOfferedWithTheStorePrice() async {
        let offers = await gateway.offers()
        XCTAssertEqual(offers.map(\.kind), [.lifetime, .yearly])
        XCTAssertTrue(offers.allSatisfy { !$0.price.isEmpty })
    }

    func testNothingIsOwnedBeforeAPurchase() async {
        let owned = await gateway.entitled()
        XCTAssertEqual(owned, false)
    }

    func testTheOneTimeUnlockIsOwnedAndARefundTakesItBack() async throws {
        let outcome = await gateway.purchase(.lifetime)
        XCTAssertEqual(outcome, .completed)
        let owned = await gateway.entitled()
        XCTAssertEqual(owned, true)

        let latest = await Transaction.latest(for: ProductKind.lifetime.rawValue)
        guard case let .verified(transaction) = latest else {
            return XCTFail("no verified transaction")
        }
        try session?.refundTransaction(identifier: UInt(transaction.id))

        let afterTheRefund = await entitledOnceSettled(expected: false)
        XCTAssertEqual(afterTheRefund, false)
    }

    func testASubscriptionThatEndsLocksAgain() async throws {
        let outcome = await gateway.purchase(.yearly)
        XCTAssertEqual(outcome, .completed)
        let owned = await gateway.entitled()
        XCTAssertEqual(owned, true)

        try session?.expireSubscription(productIdentifier: ProductKind.yearly.rawValue)

        let afterTheEnd = await entitledOnceSettled(expected: false)
        XCTAssertEqual(afterTheEnd, false)
    }

    func testTheCoreFollowsWhatTheStoreSays() async throws {
        let container = AppContainer()
        for _ in 0 ..< 100 where !container.ready {
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        XCTAssertTrue(container.ready)

        await container.syncPurchases()
        let before = try await container.shared.licensing.status()
        XCTAssertFalse(before.purchased)

        _ = await gateway.purchase(.lifetime)
        await container.syncPurchases()
        let after = try await container.shared.licensing.status()
        XCTAssertTrue(after.purchased)
        XCTAssertTrue(after.canAddItems)

        session?.clearTransactions()
        _ = await entitledOnceSettled(expected: false)
        await container.syncPurchases()
        let cleared = try await container.shared.licensing.status()
        XCTAssertFalse(cleared.purchased, "the store's answer stands, a refund as much as a purchase")
    }

    func testTheTrialDateIsKeptInItsOwnKeychainItemAndReplaced() {
        let item = KeychainDatabaseKey(service: "app.trouvetout.proto.tests", account: "trial-test")
        item.remove()
        defer {
            item.remove()
        }
        let store = KeychainLicenseStore(item: item)
        XCTAssertNil(store.trialStartMillis())
        store.setTrialStartMillis(millis: 1_800_000_000_000)
        XCTAssertEqual(store.trialStartMillis()?.int64Value, 1_800_000_000_000)
        store.setTrialStartMillis(millis: 1_790_000_000_000)
        XCTAssertEqual(store.trialStartMillis()?.int64Value, 1_790_000_000_000, "an earlier date replaces it")
    }
}
