import SwiftUI
import CoreLocation

struct SettingsView: View {
    let defaultReferenceCoordinate: CLLocationCoordinate2D?

    @EnvironmentObject var settings: APISettings
    @EnvironmentObject private var alertSettings: AlertSettings
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

                Section {
                    Toggle("高度不足アラートを有効にする", isOn: $alertSettings.isEnabled)

                    Stepper(value: $alertSettings.distanceThresholdKm, in: 0.5...50, step: 0.5) {
                        Text("基準地点から \(alertSettings.distanceThresholdKm, specifier: "%.1f") km 以上")
                    }
                    Stepper(value: $alertSettings.minimumAltitudeM, in: 50...3000, step: 10) {
                        Text("高度 \(Int(alertSettings.minimumAltitudeM)) m 未満で警告")
                    }

                    Toggle("基準地点を自分で指定する", isOn: $alertSettings.useCustomReference)

                    if alertSettings.useCustomReference {
                        TextField("緯度", value: $alertSettings.customLatitude, format: .number)
                            .keyboardType(.numbersAndPunctuation)
                        TextField("経度", value: $alertSettings.customLongitude, format: .number)
                            .keyboardType(.numbersAndPunctuation)
                    } else if let defaultReferenceCoordinate {
                        LabeledContent("基準地点(サイトの初期座標)") {
                            Text(String(format: "%.5f, %.5f", defaultReferenceCoordinate.latitude, defaultReferenceCoordinate.longitude))
                                .foregroundStyle(.secondary)
                        }
                    } else {
                        Text("基準地点がまだ取得できていません。しばらく待ってから確認するか、下のトグルで自分で座標を指定してください。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } header: {
                    Text("高度不足アラート")
                } footer: {
                    Text("グライダーはエンジンがないため、基準地点から一定距離以上離れているのに高度が低いと戻れない可能性があります。ここで設定した条件に当てはまる機体は、地図上で赤く警告表示されます。あくまで目安であり、実際の判断の根拠にはしないでください。高度は本サイトが提供する値(海抜高)をそのまま使っています。")
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
