import SwiftUI

/// Shows a glider's server label ("7.") plus its user-assigned nickname, if
/// any, and lets the user tap to edit that nickname.
struct EditableGliderName: View {
    let imei: String
    let baseName: String

    @EnvironmentObject private var nicknameStore: NicknameStore
    @State private var isEditing = false
    @State private var draft = ""

    private var displayName: String {
        if let nickname = nicknameStore.nickname(forIMEI: imei) {
            return "\(baseName) \(nickname)"
        }
        return baseName
    }

    var body: some View {
        Button {
            draft = nicknameStore.nickname(forIMEI: imei) ?? ""
            isEditing = true
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
            TextField("例: 太郎号", text: $draft)
            Button("保存") {
                nicknameStore.setNickname(draft, forIMEI: imei)
            }
            Button("キャンセル", role: .cancel) {}
        } message: {
            Text("「\(baseName)」の後ろに表示する名前を入力してください。空欄にすると削除されます。")
        }
    }
}
