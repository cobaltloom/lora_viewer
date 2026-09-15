import SwiftUI

/// Shows a glider's server label ("7.") plus its user-assigned nickname, if
/// any, and lets the user tap to edit that nickname. Both viewing and
/// editing nicknames are subscriber-only — without one, this shows just the
/// server label, and tapping shows the paywall instead of the edit prompt.
struct EditableGliderName: View {
    let imei: String
    let baseName: String

    @EnvironmentObject private var nicknameStore: NicknameStore
    @EnvironmentObject private var subscriptionManager: SubscriptionManager
    @State private var isEditing = false
    @State private var showPaywall = false
    @State private var draft = ""

    private var displayName: String {
        guard subscriptionManager.isSubscribed else { return baseName }
        return nicknameStore.displayName(baseName: baseName, imei: imei)
    }

    var body: some View {
        Button {
            if subscriptionManager.isSubscribed {
                draft = nicknameStore.nickname(forIMEI: imei) ?? ""
                isEditing = true
            } else {
                showPaywall = true
            }
        } label: {
            HStack(spacing: 4) {
                Text(displayName)
                Image(systemName: "pencil")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .buttonStyle(.plain)
        .alert("名前を編集", isPresented: $isEditing) {
            TextField("例: 学連21", text: $draft)
            Button("保存") {
                nicknameStore.setNickname(draft, forIMEI: imei)
            }
            Button("キャンセル", role: .cancel) {}
        } message: {
            Text("「\(baseName)」の後ろに表示する名前を入力してください。空欄にすると削除されます。")
        }
        .sheet(isPresented: $showPaywall) {
            PaywallView()
        }
    }
}
