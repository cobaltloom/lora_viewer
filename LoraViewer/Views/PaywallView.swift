import SwiftUI
import StoreKit

/// The "広告なし" subscription, from Settings. Every feature is free; this only turns the ads off.
/// Apple requires the subscription's price/duration/renewal terms and links to the terms of use
/// and privacy policy to be visible here.
struct PaywallView: View {
    @EnvironmentObject private var subscriptionManager: SubscriptionManager
    @Environment(\.dismiss) private var dismiss
    @State private var isPurchasing = false

    private let termsOfUseURL = URL(string: "https://www.apple.com/legal/internet-services/itunes/dev/stdeula/")!
    private let privacyPolicyURL = URL(string: "https://cobaltloom.github.io/lora_viewer/privacy-policy.html")!

    private let subscriberFeatures = [
        "旋回点通過履歴・団体順位・設定などの画面から広告がなくなります",
        "アプリの開発とサーバーの運営を応援できます",
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    Image(systemName: "nosign")
                        .font(.system(size: 64))
                        .foregroundStyle(.blue)
                        .padding(.top, 16)

                    Text("広告なし")
                        .font(.title.bold())
                        .multilineTextAlignment(.center)

                    VStack(alignment: .leading, spacing: 10) {
                        ForEach(subscriberFeatures, id: \.self) { feature in
                            Label(feature, systemImage: "checkmark.circle.fill")
                                .foregroundStyle(.primary)
                                .symbolRenderingMode(.hierarchical)
                                .imageScale(.medium)
                        }
                    }
                    .font(.subheadline)
                    .padding()
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
                    .padding(.horizontal)

                    Text("すべての機能は購読なしで無料でお使いいただけます。地図の画面には広告を表示しません。")
                        .font(.caption)
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal)

                    if let product = subscriptionManager.product {
                        VStack(spacing: 8) {
                            Text(product.displayPrice + " / 月")
                                .font(.title2.bold())
                            if let introOffer = product.subscription?.introductoryOffer, introOffer.paymentMode == .freeTrial {
                                Text("初回登録から\(introOffer.period.value)\(unitLabel(introOffer.period.unit))無料")
                                    .font(.subheadline)
                                    .foregroundStyle(.green)
                            }
                            Text("自動更新。いつでもApp Store の「サブスクリプション」設定から解約できます。")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal)
                        }
                        .padding()
                        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
                        .padding(.horizontal)

                        Button {
                            isPurchasing = true
                            Task {
                                await subscriptionManager.purchase()
                                isPurchasing = false
                                if subscriptionManager.isSubscribed { dismiss() }
                            }
                        } label: {
                            if isPurchasing {
                                ProgressView()
                                    .frame(maxWidth: .infinity)
                            } else {
                                Text("購読する")
                                    .frame(maxWidth: .infinity)
                            }
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.large)
                        .padding(.horizontal)
                        .disabled(isPurchasing)
                    } else if subscriptionManager.isLoading {
                        ProgressView("読み込み中…")
                            .padding()
                    } else {
                        Text("商品情報を読み込めませんでした。通信環境を確認してください。")
                            .foregroundStyle(.secondary)
                            .padding()
                    }

                    if let errorMessage = subscriptionManager.errorMessage {
                        Text(errorMessage)
                            .font(.caption)
                            .foregroundStyle(.red)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal)
                    }

                    Button("購入を復元") {
                        Task {
                            await subscriptionManager.restorePurchases()
                            if subscriptionManager.isSubscribed { dismiss() }
                        }
                    }
                    .font(.subheadline)

                    HStack(spacing: 16) {
                        Link("利用規約", destination: termsOfUseURL)
                        Link("プライバシーポリシー", destination: privacyPolicyURL)
                    }
                    .font(.caption)
                    .padding(.bottom, 24)
                }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("閉じる") { dismiss() }
                }
            }
        }
        .task {
            await subscriptionManager.loadProduct()
        }
    }

    private func unitLabel(_ unit: Product.SubscriptionPeriod.Unit) -> String {
        switch unit {
        case .day: return "日間"
        case .week: return "週間"
        case .month: return "ヶ月"
        case .year: return "年"
        @unknown default: return ""
        }
    }
}
