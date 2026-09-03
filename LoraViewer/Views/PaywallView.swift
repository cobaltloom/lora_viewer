import SwiftUI
import StoreKit

/// Shown instead of the map whenever there's no active subscription.
/// Apple requires the subscription's price/duration/renewal terms and
/// links to the terms of use and privacy policy to be visible here.
struct PaywallView: View {
    @EnvironmentObject private var subscriptionManager: SubscriptionManager
    @State private var isPurchasing = false

    private let termsOfUseURL = URL(string: "https://www.apple.com/legal/internet-services/itunes/dev/stdeula/")!
    private let privacyPolicyURL = URL(string: "https://cobaltloom.github.io/lora_viewer/privacy-policy.html")!

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                Image(systemName: "airplane.circle.fill")
                    .font(.system(size: 64))
                    .foregroundStyle(.blue)
                    .padding(.top, 40)

                Text("LoRa妻沼")
                    .font(.largeTitle.bold())

                Text("妻沼滑空場のグライダー位置・高度をリアルタイムに表示します。ご利用には購読が必要です。")
                    .font(.body)
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
                    Task { await subscriptionManager.restorePurchases() }
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
