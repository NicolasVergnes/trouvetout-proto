import Shared
import SwiftUI

/// The purchase screen as a value route.
struct PurchaseRoute: Hashable {}

enum PurchaseMessage {
    case pending
    case nothingToRestore
    case restored
    case unavailable

    var key: LocalizedStringKey {
        switch self {
        case .pending: "purchase_pending"
        case .nothingToRestore: "purchase_nothing_to_restore"
        case .restored: "purchase_restored"
        case .unavailable: "purchase_unavailable"
        }
    }
}

/// J4, J5: the licence as the core reads it and the store's offers. No rule here.
@MainActor
final class PurchaseViewModel: ObservableObject {
    @Published private(set) var status: LicenseStatus?
    @Published private(set) var offers: [StoreOffer] = []
    /// Prices are still being asked of the store.
    @Published private(set) var loading = true
    @Published private(set) var busy = false
    @Published var message: PurchaseMessage?

    private let container: AppContainer

    init(container: AppContainer) {
        self.container = container
    }

    func load() async {
        await readStatus()
        offers = await container.purchases.offers()
        loading = false
    }

    func buy(_ kind: ProductKind) {
        perform { [self] in
            // The store's sheet covers the app for a moment: not a departure for the lock.
            container.shared.appLock.expectReturn()
            switch await container.purchases.purchase(kind) {
            case .completed:
                await container.syncPurchases()
            case .pending:
                message = .pending
            case .cancelled:
                break
            case .unavailable:
                message = .unavailable
            }
        }
    }

    func restore() {
        perform { [self] in
            container.shared.appLock.expectReturn()
            switch await container.purchases.restore() {
            case .none:
                message = .unavailable
            case .some(true):
                message = .restored
            case .some(false):
                message = .nothingToRestore
            }
            await container.syncPurchases()
        }
    }

    private func readStatus() async {
        guard container.ready else { return }
        status = try? await container.shared.licensing.status()
    }

    private func perform(_ work: @escaping @MainActor () async -> Void) {
        guard !busy else { return }
        busy = true
        message = nil
        Task {
            await work()
            await readStatus()
            busy = false
        }
    }
}

/// U11: clear and never in the way; everything else in the app goes on working whatever is chosen here.
struct PurchaseView: View {
    @StateObject private var viewModel: PurchaseViewModel
    @Environment(\.dismiss) private var dismiss

    init(container: AppContainer) {
        _viewModel = StateObject(wrappedValue: PurchaseViewModel(container: container))
    }

    var body: some View {
        Form {
            if let status = viewModel.status {
                Section {
                    statusText(status)
                }
            }
            if viewModel.status?.purchased != true {
                Section {
                    offers
                }
            }
            Section {
                Button("purchase_restore") { viewModel.restore() }
                    .disabled(viewModel.busy)
                    .accessibilityIdentifier("purchase_restore")
                Button("purchase_later") { dismiss() }
                    .accessibilityIdentifier("purchase_later")
            }
            if viewModel.busy {
                ProgressView("settings_busy")
                    .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle("purchase_title")
        .task { await viewModel.load() }
        .alert(viewModel.message?.key ?? "", isPresented: messagePresented) {
            Button("action_ok") { viewModel.message = nil }
        }
    }

    @ViewBuilder
    private func statusText(_ status: LicenseStatus) -> some View {
        if status.purchased {
            Text("purchase_done_text")
        } else if status.canAddItems {
            Text(String(format: NSLocalizedString("purchase_running_text", comment: ""), endDate(status)))
        } else {
            Text("purchase_ended_title")
                .font(.headline)
                .accessibilityAddTraits(.isHeader)
            Text("purchase_ended_text")
        }
    }

    @ViewBuilder
    private var offers: some View {
        ForEach(viewModel.offers) { offer in
            Button(offerText(offer)) { viewModel.buy(offer.kind) }
                .disabled(viewModel.busy)
                .accessibilityIdentifier(offer.id)
        }
        if viewModel.loading {
            ProgressView()
        } else if viewModel.offers.isEmpty {
            Text("purchase_unavailable")
                .font(.callout)
        }
    }

    private func offerText(_ offer: StoreOffer) -> String {
        let key = offer.kind == .lifetime ? "purchase_lifetime" : "purchase_yearly"
        return String(format: NSLocalizedString(key, comment: ""), offer.price)
    }

    private func endDate(_ status: LicenseStatus) -> String {
        Date(timeIntervalSince1970: TimeInterval(status.trialEndMillis) / 1000)
            .formatted(date: .long, time: .omitted)
    }

    private var messagePresented: Binding<Bool> {
        Binding(
            get: { viewModel.message != nil },
            set: { presented in
                if !presented {
                    viewModel.message = nil
                }
            }
        )
    }
}
