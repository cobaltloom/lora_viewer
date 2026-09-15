import SwiftUI

/// サーバー接続・更新間隔など、通常は変更不要な設定をまとめたサブ画面。
struct AdvancedSettingsView: View {
    @EnvironmentObject var settings: APISettings

    var body: some View {
        Form {
            Section {
                TextField("ベースURL", text: $settings.baseURLString)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                if settings.isBaseURLCustomized {
                    Button("既定のURLに戻す") { settings.resetBaseURLToServerDefault() }
                }
                SecureField("シークレットキー (任意)", text: $settings.secretKey)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            } header: {
                Text("サーバー")
            } footer: {
                Text("ベースURLは通常は変更不要です。運営側でURLが変更された場合は自動的に反映されます。自分のアカウント用に別のURLを使う場合のみ入力してください(手動で入力すると自動反映は止まります)。シークレットキーは通常は空欄のままで問題ありません。動作しない場合のみ、MacのSafariで「開発」>「Webインスペクタを表示」の「ネットワーク」タブから mapapi.php へのリクエストを確認し、URL中に key= があればその値を入力してください。")
            }

            Section {
                Stepper(value: $settings.refreshIntervalSeconds, in: 3...60, step: 1) {
                    Text("\(Int(settings.refreshIntervalSeconds)) 秒ごとに更新")
                }
            } header: {
                Text("更新間隔")
            } footer: {
                Text("接続先のサーバーに負荷をかけるため、短くしすぎないでください。機体側の送信間隔もこれより速くはならないため、短くしても位置情報が特に速く更新されるわけではありません。")
            }
        }
        .navigationTitle("高度な設定")
        .navigationBarTitleDisplayMode(.inline)
    }
}
