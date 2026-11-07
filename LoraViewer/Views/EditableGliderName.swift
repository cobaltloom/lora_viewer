import SwiftUI

/// Shows a glider's server label ("7.") plus its user-assigned nickname, if
/// any, and lets the user tap to edit that nickname.
struct EditableGliderName: View {
    let imei: String
    let baseName: String
    /// false for anonymous OGN aircraft: just the base name, no nickname and no editing.
    var isEditable = true

    @EnvironmentObject private var nicknameStore: NicknameStore
    @State private var isEditing = false
    @State private var draft = ""

    private var displayName: String {
        nicknameStore.displayName(baseName: baseName, imei: imei)
    }

    var body: some View {
        if isEditable {
            editableName
        } else {
            Text(baseName)
        }
    }

    private var editableName: some View {
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
            TextField("例: 学連21", text: $draft)
            Button("保存") {
                nicknameStore.setNickname(draft, forIMEI: imei)
            }
            Button("キャンセル", role: .cancel) {}
        } message: {
            Text("「\(baseName)」の後ろに表示する名前を入力してください。空欄にすると削除されます。")
        }
    }
}
