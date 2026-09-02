import SwiftUI
import CoreLocation

struct SettingsView: View {
    let defaultReferenceCoordinate: CLLocationCoordinate2D?

    @EnvironmentObject var settings: APISettings
    @EnvironmentObject private var alertSettings: AlertSettings
    @EnvironmentObject private var competitionGuideline: CompetitionAltitudeGuideline
    @EnvironmentObject private var upperAltitudeGuideline: UpperAltitudeGuideline
    @Environment(\.dismiss) private var dismiss
    @State private var showReferencePointPicker = false
    @State private var showDeleteAllStepsConfirmation = false

    private var referencePointPickerInitialCoordinate: CLLocationCoordinate2D? {
        if alertSettings.customLatitude != 0 || alertSettings.customLongitude != 0 {
            return CLLocationCoordinate2D(latitude: alertSettings.customLatitude, longitude: alertSettings.customLongitude)
        }
        return defaultReferenceCoordinate
    }

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
                    Stepper(value: $alertSettings.minimumFlyingAltitudeM, in: 0...500, step: 10) {
                        Text("高度 \(Int(alertSettings.minimumFlyingAltitudeM)) m 以下は地上とみなす")
                    }
                } header: {
                    Text("地上判定(共通)")
                } footer: {
                    Text("この高度以下は駐機中・着陸後とみなし、距離に関わらず下の2つのアラートどちらも出しません。滑空場の標高より少し高い値にしておくと、実際に飛んでいない機体を誤って警告しにくくなります。")
                }

                Section {
                    Toggle("高度不足アラートを有効にする", isOn: $alertSettings.isEnabled)

                    Picker("計算方法", selection: $alertSettings.mode) {
                        Text("距離ごとの段階").tag(AltitudeCalculationMode.steps)
                        Text("帰投高度とL/D").tag(AltitudeCalculationMode.glideRatio)
                    }
                    .pickerStyle(.segmented)

                    if alertSettings.mode == .steps {
                        ForEach($alertSettings.steps) { $step in
                            VStack(alignment: .leading, spacing: 14) {
                                Stepper(value: $step.distanceKm, in: 0.5...50, step: 0.5) {
                                    Text("基準地点から \(step.distanceKm, specifier: "%.1f") km 以上")
                                }
                                Stepper(value: $step.minimumAltitudeM, in: 50...3000, step: 10) {
                                    Text("高度 \(Int(step.minimumAltitudeM)) m 未満で警告")
                                }
                            }
                            .padding(.vertical, 2)
                        }
                        .onDelete { indices in
                            alertSettings.steps.remove(atOffsets: indices)
                        }

                        Button {
                            alertSettings.addStep()
                        } label: {
                            Label("段階を追加", systemImage: "plus.circle")
                        }

                        if !alertSettings.steps.isEmpty {
                            Button(role: .destructive) {
                                showDeleteAllStepsConfirmation = true
                            } label: {
                                Label("段階をすべて削除", systemImage: "trash")
                            }
                        }
                    } else {
                        Stepper(value: $alertSettings.arrivalAltitudeM, in: 50...3000, step: 10) {
                            Text("基準地点での必要高度 \(Int(alertSettings.arrivalAltitudeM)) m")
                        }
                        Stepper(value: $alertSettings.warningGlideRatio, in: 5...60, step: 1) {
                            Text("警告の滑空比(L/D) \(Int(alertSettings.warningGlideRatio))")
                        }
                        Stepper(value: $alertSettings.cautionGlideRatio, in: 5...60, step: 1) {
                            Text("注意の滑空比(L/D) \(Int(alertSettings.cautionGlideRatio))")
                        }
                    }

                    Toggle("基準地点を自分で指定する", isOn: $alertSettings.useCustomReference)

                    if alertSettings.useCustomReference {
                        LabeledContent("基準地点") {
                            if alertSettings.customLatitude != 0 || alertSettings.customLongitude != 0 {
                                Text(String(format: "%.5f, %.5f", alertSettings.customLatitude, alertSettings.customLongitude))
                                    .foregroundStyle(.secondary)
                            } else {
                                Text("未設定")
                                    .foregroundStyle(.secondary)
                            }
                        }
                        Button {
                            showReferencePointPicker = true
                        } label: {
                            Label("地図で選ぶ", systemImage: "mappin.and.ellipse")
                        }
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
                    Text("高度不足アラート(カスタム設定)")
                } footer: {
                    Text("「距離ごとの段階」は、段階を複数追加して距離ごとに必要な高度を設定する方式です(各機体には、その時点の距離以下となる段階のうち最も距離が大きいものが適用されます)。「帰投高度とL/D」は、基準地点での必要高度に、距離÷滑空比(L/D)を加えた高度を必要高度とする方式で、警告と注意で異なる滑空比を設定し2段階で警告します。警告の滑空比は注意の滑空比より大きい値(より楽観的な数値)にしてください。地図上には、段階方式では各段階の距離を半径とした円が表示されます。あくまで目安であり、実際の判断の根拠にはしないでください。高度は本サイトが提供する値(海抜高)をそのまま使っています。")
                }

                Section {
                    Toggle("有効にする", isOn: $competitionGuideline.isEnabled)
                } header: {
                    Text("競技会ガイドライン(妻沼滑空場)")
                } footer: {
                    Text("日本学生航空連盟(JSAL)妻沼滑空場の公式ガイドライン(Ver.2026-01-26)を使用します。滑空場中心(N36°12'41\", E139°25'08\")から2.5km未満は制限なし、2.5〜3kmでMSL350m以上、以降1kmごとに70mずつ増加し、10km以上でMSL910m以上が必要です。公式資料に基づく固定値のため、数値はここでは変更できません(上のカスタム設定とは別に、両方同時に有効化できます)。")
                }

                Section {
                    Toggle("有効にする", isOn: $upperAltitudeGuideline.isEnabled)

                    Picker("B区域の上限の決め方", selection: $upperAltitudeGuideline.mode) {
                        Text("自動(平日/土日祝)").tag(UpperCeilingMode.auto)
                        Text("競技会中(手動指定)").tag(UpperCeilingMode.competition)
                    }
                    .pickerStyle(.segmented)

                    if upperAltitudeGuideline.mode == .auto {
                        Toggle("今日を祝日として扱う", isOn: $upperAltitudeGuideline.treatTodayAsHoliday)
                    } else {
                        Stepper(value: $upperAltitudeGuideline.competitionCeilingFt, in: 500...10000, step: 100) {
                            Text("競技会中の上限 \(Int(upperAltitudeGuideline.competitionCeilingFt)) ft MSL")
                        }
                    }

                    LabeledContent("A区域の上限") {
                        Text("\(Int(UpperAltitudeGuideline.zoneACeilingFt)) ft MSL")
                            .foregroundStyle(.secondary)
                    }
                    LabeledContent("B区域の上限(本日)") {
                        Text("\(Int(upperAltitudeGuideline.bZoneCeilingFt)) ft MSL")
                            .foregroundStyle(.secondary)
                    }
                } header: {
                    Text("上限高度アラート(妻沼滑空場)")
                } footer: {
                    Text("公式資料のA区域・B区域の境界に基づき、区域内でその上限高度を超えるとアラートを出します。A区域は常に4,500ft MSL。B区域は平日2,500ft、土日祝日3,500ftで、「今日を祝日として扱う」で土日以外の祝日にも対応できます。競技会など別の上限が許可されている期間は「競技会中」を選び、許可された値を入力してください。区域の境界(緯度経度)は公式資料に基づく固定値のため、ここでは変更できません。あくまで目安であり、実際の判断の根拠にはしないでください。")
                }
            }
            .navigationTitle("設定")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("閉じる") { dismiss() }
                }
            }
            .confirmationDialog(
                "段階をすべて削除しますか?",
                isPresented: $showDeleteAllStepsConfirmation,
                titleVisibility: .visible
            ) {
                Button("すべて削除", role: .destructive) {
                    alertSettings.steps.removeAll()
                }
                Button("キャンセル", role: .cancel) {}
            } message: {
                Text("追加した段階がすべて削除されます。この操作は取り消せません。")
            }
            .sheet(isPresented: $showReferencePointPicker) {
                ReferencePointPickerView(
                    latitude: $alertSettings.customLatitude,
                    longitude: $alertSettings.customLongitude,
                    initialCoordinate: referencePointPickerInitialCoordinate
                )
            }
        }
    }
}
