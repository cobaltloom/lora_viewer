import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var settings: APISettings
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("サーバー") {
                    TextField("ベースURL", text: $settings.baseURLString)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                    SecureField("シークレットキー (key)", text: $settings.secretKey)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } footer: {
                    Text("MacのSafariでサイトを開き、「開発」>「Webインスペクタを表示」の「ネットワーク」タブで mapapi.php へのリクエストを確認し、URL中の key= の値を入力してください。")
                }
                Section("更新間隔") {
                    Stepper(value: $settings.refreshIntervalSeconds, in: 3...60, step: 1) {
                        Text("\(Int(settings.refreshIntervalSeconds)) 秒ごとに更新")
                    }
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
