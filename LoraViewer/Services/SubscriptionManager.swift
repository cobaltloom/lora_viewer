import Foundation
import StoreKit

/// Tracks whether the user has an active subscription, and drives the
/// purchase/restore flow. The whole app is gated behind this: without an
/// active subscription, `PaywallView` is shown instead of the map.
@MainActor
final class SubscriptionManager: ObservableObject {
    static let monthlyProductID = "com.cobaltloom.loraviewer.monthly"

    @Published private(set) var isSubscribed = false
    @Published private(set) var product: Product?
    @Published private(set) var isLoading = true
    @Published var errorMessage: String?

    private var updatesTask: Task<Void, Never>?

    init() {
        updatesTask = Task { [weak self] in
            // Transaction.updates delivers renewals, cancellations, and
            // purchases made outside this launch (e.g. on another device),
            // so entitlement status stays correct without polling.
            for await update in Transaction.updates {
                await self?.handle(update)
            }
        }
        Task {
            await loadProduct()
            await refreshEntitlement()
        }
    }

    deinit {
        updatesTask?.cancel()
    }

    func loadProduct() async {
        isLoading = true
        defer { isLoading = false }
        do {
            let products = try await Product.products(for: [Self.monthlyProductID])
            product = products.first
        } catch {
            errorMessage = "商品情報の取得に失敗しました。通信環境を確認して、もう一度お試しください。"
        }
    }

    /// `Transaction.currentEntitlements` only yields entitlements that are
    /// still active, so a lapsed/cancelled subscription simply won't appear
    /// — meaning "found nothing" has to be treated as "not subscribed"
    /// rather than leaving the previous status in place.
    func refreshEntitlement() async {
        var found = false
        for await entitlement in Transaction.currentEntitlements {
            guard case .verified(let transaction) = entitlement,
                  transaction.productID == Self.monthlyProductID,
                  transaction.revocationDate == nil else { continue }
            found = true
        }
        isSubscribed = found
    }

    func purchase() async {
        guard let product else { return }
        errorMessage = nil
        do {
            let result = try await product.purchase()
            switch result {
            case .success(let verification):
                await handle(verification)
            case .userCancelled:
                break
            case .pending:
                errorMessage = "購入手続きが保留中です(承認待ちなど)。完了までしばらくお待ちください。"
            @unknown default:
                break
            }
        } catch {
            errorMessage = "購入処理に失敗しました。もう一度お試しください。"
        }
    }

    func restorePurchases() async {
        errorMessage = nil
        do {
            try await AppStore.sync()
            await refreshEntitlement()
            if !isSubscribed {
                errorMessage = "有効な購読が見つかりませんでした。"
            }
        } catch {
            errorMessage = "購入の復元に失敗しました。もう一度お試しください。"
        }
    }

    /// Finishes the transaction, then recomputes `isSubscribed` from
    /// `currentEntitlements` rather than inferring it from this one
    /// transaction — that keeps a single source of truth for what "active"
    /// means instead of duplicating that logic here.
    private func handle(_ verification: VerificationResult<Transaction>) async {
        guard case .verified(let transaction) = verification else { return }
        await transaction.finish()
        await refreshEntitlement()
    }
}
