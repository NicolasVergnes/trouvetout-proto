import Foundation
import StoreKit

/// The two products of ADR-014; the identifiers are permanent once declared, and free of any brand (ADR-010).
enum ProductKind: String, CaseIterable {
    case lifetime = "unlock_lifetime"
    case yearly = "subscription_yearly"
}

/// A product as the store offers it to this account: the price is the store's, already formatted.
struct StoreOffer: Identifiable, Equatable {
    let kind: ProductKind
    let price: String

    var id: String {
        kind.rawValue
    }
}

enum PurchaseOutcome {
    case completed
    /// Waiting for someone's approval (Ask to Buy) or for the payment: nothing is granted yet.
    case pending
    case cancelled
    case unavailable
}

/// StoreKit 2 (J4, J5): the only type that talks to the store. Transactions are signed by Apple and checked by
/// StoreKit on the phone (`VerificationResult`): an unverified one grants nothing. No server (ADR-007).
struct StoreKitPurchaseGateway {
    /// Whether a verified purchase or a running subscription exists. StoreKit answers from the phone, offline too.
    func entitled() async -> Bool {
        for await result in Transaction.currentEntitlements {
            if case let .verified(transaction) = result, isActive(transaction) {
                return true
            }
        }
        return false
    }

    /// The date Apple signed for the first download of the app (J1): a reinstall does not change it. Nil offline on
    /// a phone that never asked, or when the signature does not check out; the Keychain date then stands.
    func firstDownloadMillis() async -> Int64? {
        guard let result = try? await AppTransaction.shared, case let .verified(app) = result else {
            return nil
        }
        return Int64(app.originalPurchaseDate.timeIntervalSince1970 * 1000)
    }

    /// Empty when the store cannot say.
    func offers() async -> [StoreOffer] {
        let products = await (try? Product.products(for: ProductKind.allCases.map(\.rawValue))) ?? []
        return ProductKind.allCases.compactMap { kind in
            products.first { $0.id == kind.rawValue }.map { StoreOffer(kind: kind, price: $0.displayPrice) }
        }
    }

    func purchase(_ kind: ProductKind) async -> PurchaseOutcome {
        let products = await (try? Product.products(for: [kind.rawValue])) ?? []
        guard let product = products.first, let result = try? await product.purchase() else {
            return .unavailable
        }
        switch result {
        case let .success(.verified(transaction)):
            await transaction.finish()
            return .completed
        case .success(.unverified):
            return .unavailable
        case .pending:
            return .pending
        case .userCancelled:
            return .cancelled
        @unknown default:
            return .unavailable
        }
    }

    /// J5: asks the store for what this account bought, on this phone or another; may ask to sign in. Nil when it
    /// could not answer or the user declined.
    func restore() async -> Bool? {
        do {
            try await AppStore.sync()
        } catch {
            return nil
        }
        return await entitled()
    }

    /// Purchases that arrive outside a flow of this app: a renewal, a refund, an approval. Listens for the process.
    func listen(onChange: @escaping @Sendable () async -> Void) -> Task<Void, Never> {
        Task.detached(priority: .utility) {
            for await update in Transaction.updates {
                if case let .verified(transaction) = update {
                    await transaction.finish()
                }
                await onChange()
            }
        }
    }

    private func isActive(_ transaction: Transaction) -> Bool {
        guard ProductKind(rawValue: transaction.productID) != nil, transaction.revocationDate == nil else {
            return false
        }
        return transaction.expirationDate.map { $0 > Date() } ?? true
    }
}
