import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var settings: APISettings
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("ベースURL", text: $settings.baseURLString)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                    SecureField("シークレットキー (任意)", text: $settings.secretKey)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } header: {
                    Text("サーバー")
                } footer: {
                    Text("シークレットキーは通常は空欄のままで問題ありません。動作しない場合のみ、MacのSafariで「開発」>「Webインスペクタを表示」の「ネットワーク」タブから mapapi.php へのリクエストを確認し、URL中に key= があればその値を入力してください。")
                }
                Section {
                    Stepper(value: $settings.refreshIntervalSeconds, in: 3...60, step: 1) {
                        Text("\(Int(settings.refreshIntervalSeconds)) 秒ごとに更新")
                    }
                } header: {
                    Text("更新間隔")
                }
            }
            .navigationTitle("設定")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("閉じる") { dismiss() }
                }
            }
        }
    }
}
