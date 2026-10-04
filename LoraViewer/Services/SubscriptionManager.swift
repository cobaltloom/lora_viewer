import Foundation
import StoreKit
import FirebaseFirestore

/// Tracks whether the user has an active subscription, and drives the
/// purchase/restore flow. Subscriber-only features check `hasFullAccess`,
/// which is also true during a club-wide free period (e.g. a competition)
/// announced through Firestore.
@MainActor
final class SubscriptionManager: ObservableObject {
    static let monthlyProductID = "com.cobaltloom.loraviewer.monthly"

    @Published private(set) var isSubscribed = false
    /// Free period read from `meta/serverConfig` (`freeAccessFrom` /
    /// `freeAccessUntil`, both Firestore timestamps; `freeAccessFrom` is
    /// optional). Lets the club open every feature for a competition without
    /// an App Store release; deleting `freeAccessUntil` ends it immediately.
    @Published private(set) var freeAccessFrom: Date?
    @Published private(set) var freeAccessUntil: Date?
    @Published private(set) var product: Product?
    @Published private(set) var isLoading = true
    @Published var errorMessage: String?

    private var updatesTask: Task<Void, Never>?
    private var freePeriodBoundaryTask: Task<Void, Never>?
    private lazy var db = Firestore.firestore()
    private var remoteConfigListener: ListenerRegistration?

    /// Evaluated against the current time on every read rather than cached,
    /// so the map's periodic refresh picks up the period ending even if the
    /// boundary task below was delayed by the app being suspended.
    var isFreePeriodActive: Bool {
        guard let freeAccessUntil else { return false }
        let now = Date()
        if let freeAccessFrom, now < freeAccessFrom { return false }
        return now < freeAccessUntil
    }

    var hasFullAccess: Bool {
        isSubscribed || isFreePeriodActive
    }

    init() {
        updatesTask = Task { [weak self] in
            // Transaction.updates delivers renewals, cancellations, and
            // purchases made outside this launch (e.g. on another device),
            // so entitlement status stays correct without polling.
            for await update in StoreKit.Transaction.updates {
                await self?.handle(update)
            }
        }
        Task {
            await loadProduct()
            await refreshEntitlement()
        }
        listenForFreePeriod()
    }

    deinit {
        updatesTask?.cancel()
        freePeriodBoundaryTask?.cancel()
        remoteConfigListener?.remove()
    }

    private func listenForFreePeriod() {
        remoteConfigListener = db.collection("meta").document("serverConfig")
            .addSnapshotListener { [weak self] snapshot, _ in
                // On a listener error keep whatever period was last known
                // rather than cutting a free period short mid-competition.
                guard let self, let snapshot else { return }
                let data = snapshot.data()
                self.freeAccessFrom = (data?["freeAccessFrom"] as? Timestamp)?.dateValue()
                self.freeAccessUntil = (data?["freeAccessUntil"] as? Timestamp)?.dateValue()
                self.scheduleFreePeriodBoundaryRefresh()
            }
    }

    /// `isFreePeriodActive` depends on the clock, so nudge SwiftUI at the next
    /// start/end boundary — otherwise views wouldn't re-evaluate until
    /// something else changed.
    private func scheduleFreePeriodBoundaryRefresh() {
        freePeriodBoundaryTask?.cancel()
        let now = Date()
        guard let next = [freeAccessFrom, freeAccessUntil].compactMap({ $0 }).filter({ $0 > now }).min() else { return }
        freePeriodBoundaryTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(next.timeIntervalSince(now) + 1))
            guard !Task.isCancelled, let self else { return }
            self.objectWillChange.send()
            self.scheduleFreePeriodBoundaryRefresh()
        }
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
        for await entitlement in StoreKit.Transaction.currentEntitlements {
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
    private func handle(_ verification: VerificationResult<StoreKit.Transaction>) async {
        guard case .verified(let transaction) = verification else { return }
        await transaction.finish()
        await refreshEntitlement()
    }
}
