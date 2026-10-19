import SwiftUI
import CoreLocation

/// The settings index: one row per category, each showing its current state on the right so a
/// setting left on (or off) is visible without opening every page.
struct SettingsView: View {
    @EnvironmentObject var settings: APISettings
    @EnvironmentObject private var alertSettings: AlertSettings
    @EnvironmentObject private var competitionGuideline: CompetitionAltitudeGuideline
    @EnvironmentObject private var upperAltitudeGuideline: UpperAltitudeGuideline
    @EnvironmentObject private var proximityAlertSettings: ProximityAlertSettings
    @EnvironmentObject private var subscriptionManager: SubscriptionManager
    @Environment(\.dismiss) private var dismiss
    @AppStorage("showGliderTrails") private var showGliderTrails = true
    @AppStorage("showDistanceReferencePoints") private var showDistanceReferencePoints = true
    @AppStorage("showKK43Area") private var showKK43Area = false

    var body: some View {
        NavigationStack {
            Form {
                if settings.isOGNAvailable {
                    Section {
                        Picker("データ元", selection: $settings.dataSource) {
                            ForEach(DataSource.allCases, id: \.self) { source in
                                Text(source.displayName).tag(source)
                            }
                        }
                        .pickerStyle(.segmented)
                    } header: {
                        Text("位置情報のデータ元")
                    } footer: {
                        Text("LoRa: クラブのLoRa発信機を積んだ機体を表示します。\nFLARM: FLARMやOGNトラッカーを積み、OGN(Open Glider Network)受信機の範囲内(妻沼から約150km)にいる機体を表示します(\(OGNAPI.attribution))。追跡拒否・匿名・ステルス設定の機体と旅客機は表示しません。機体名の非公開を希望している機体は「非公開機」として位置のみ表示します。")
                    }
                }

                Section("地図") {
                    NavigationLink { MapDisplaySettingsPage() } label: {
                        LabeledContent("地図の表示", value: mapDisplaySummary)
                    }
                }

                Section("競技") {
                    NavigationLink { TaskCourseSettingsPage() } label: {
                        LabeledContent("タスクコース・周回時間", value: proSummary(taskCourseSummary))
                    }
                }

                Section {
                    NavigationLink { GroundThresholdSettingsPage() } label: {
                        LabeledContent("地上判定(共通)", value: "\(Int(alertSettings.minimumFlyingAltitudeM))m以下")
                    }
                    NavigationLink { LowAltitudeAlertSettingsPage() } label: {
                        LabeledContent("高度不足(カスタム)", value: proSummary(lowAltitudeSummary))
                    }
                    NavigationLink { CompetitionGuidelineSettingsPage() } label: {
                        LabeledContent("競技会ガイドライン", value: proSummary(onOff(competitionGuideline.isEnabled)))
                    }
                    NavigationLink { UpperAltitudeSettingsPage() } label: {
                        LabeledContent("上限高度", value: proSummary(upperAltitudeSummary))
                    }
                    NavigationLink { ProximityAlertSettingsPage() } label: {
                        LabeledContent("機体接近", value: proSummary(onOff(proximityAlertSettings.isEnabled)))
                    }
                } header: {
                    Text("アラート")
                } footer: {
                    if !subscriptionManager.hasFullAccess {
                        Text("🔒 はプラス機能です。購読すると有効にできます。")
                    }
                }

                Section("その他") {
                    NavigationLink("高度な設定(更新間隔・サーバー)") {
                        AdvancedSettingsView()
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

    private func onOff(_ isOn: Bool) -> String { isOn ? "オン" : "オフ" }

    /// Pro features read as locked without a subscription, whatever their stored setting says,
    /// since they don't apply then.
    private func proSummary(_ summary: String) -> String {
        subscriptionManager.hasFullAccess ? summary : "🔒"
    }

    private var mapDisplaySummary: String {
        var items: [String] = []
        if showGliderTrails { items.append("軌跡") }
        if showDistanceReferencePoints { items.append("目標地点") }
        if showKK43Area, subscriptionManager.hasFullAccess { items.append("KK4-3") }
        return items.isEmpty ? "なし" : items.joined(separator: "・")
    }

    private var taskCourseSummary: String {
        guard competitionGuideline.showTaskCourse else { return "オフ" }
        guard let index = competitionGuideline.selectedCourseIndex,
              CompetitionTaskCourseData.courses.indices.contains(index) else { return "旋回点のみ" }
        return String(CompetitionTaskCourseData.courses[index].name.prefix(1))
    }

    private var lowAltitudeSummary: String {
        guard alertSettings.isEnabled else { return "オフ" }
        return alertSettings.mode == .steps ? "オン(距離段階)" : "オン(L/D)"
    }

    private var upperAltitudeSummary: String {
        guard upperAltitudeGuideline.isEnabled else { return "オフ" }
        return upperAltitudeGuideline.mode == .auto ? "オン(自動)" : "オン(競技会 \(Int(upperAltitudeGuideline.competitionCeilingFt))ft)"
    }
}

// MARK: - Pages

/// The "購読して有効化" row shown at the top of each pro page without a subscription.
private struct SubscribeRow: View {
    @EnvironmentObject private var subscriptionManager: SubscriptionManager
    @State private var showPaywall = false

    var body: some View {
        if !subscriptionManager.hasFullAccess {
            Button {
                showPaywall = true
            } label: {
                Label("購読して有効化", systemImage: "lock.fill")
            }
            .sheet(isPresented: $showPaywall) {
                PaywallView()
            }
        }
    }
}

private struct MapDisplaySettingsPage: View {
    @EnvironmentObject private var subscriptionManager: SubscriptionManager
    @AppStorage("showGliderTrails") private var showGliderTrails = true
    @AppStorage("showDistanceReferencePoints") private var showDistanceReferencePoints = true
    @AppStorage("showKK43Area") private var showKK43Area = false

    var body: some View {
        Form {
            Section {
                Toggle("軌跡を表示", isOn: $showGliderTrails)
            } header: {
                Text("軌跡")
            } footer: {
                Text("機体の飛行軌跡を地図に表示します。航跡サーバーが使える場合は、アプリを開く前に飛んだ部分も表示します。着陸すると軌跡は消去されます。")
            }

            Section {
                Toggle("地上目標地点を表示", isOn: $showDistanceReferencePoints)
            } header: {
                Text("地上目標地点")
            } footer: {
                Text("日本学生航空連盟(JSAL)妻沼滑空場の公式資料(図3、Ver.2026-01-26)掲載の19地点(妻沼滑空場中心点から3/5/7/9kmの目安目標)と、それに加えたローカルの目印を、実在の場所の座標で地図上に表示します。上限高度アラートとは独立した機能で、あくまで目安であり、実際の判断の根拠にはしないでください。")
            }

            Section {
                SubscribeRow()
                Toggle("KK4-3を表示", isOn: $showKK43Area)
                    .disabled(!subscriptionManager.hasFullAccess)
            } header: {
                Text("民間訓練試験空域(KK4-3)")
            } footer: {
                Text("国土交通省AIP(ENR 5.3-15)掲載の民間訓練試験空域KK4-3(地表〜2,000ft)を地図上に表示します。B区域の一部と重なる空域です。境界は新幹線・高速道路等の実際の経路をもとに再現していますが、一部区間は直線で近似した参考表示であり、実際の判断の根拠にはしないでください。")
            }
        }
        .navigationTitle("地図の表示")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct TaskCourseSettingsPage: View {
    @EnvironmentObject private var competitionGuideline: CompetitionAltitudeGuideline
    @EnvironmentObject private var subscriptionManager: SubscriptionManager

    var body: some View {
        Form {
            Section {
                SubscribeRow()
                Group {
                    Toggle("旋回点・タスクコースを表示", isOn: $competitionGuideline.showTaskCourse)

                    if competitionGuideline.showTaskCourse {
                        Picker("コース", selection: Binding(
                            get: { competitionGuideline.selectedCourseIndex ?? -1 },
                            set: { competitionGuideline.selectedCourseIndex = $0 == -1 ? nil : $0 }
                        )) {
                            Text("旋回点のみ").tag(-1)
                            ForEach(Array(CompetitionTaskCourseData.courses.enumerated()), id: \.offset) { index, course in
                                Text("\(course.name)(\(course.distanceKm, specifier: "%.1f")km)").tag(index)
                            }
                        }
                        .pickerStyle(.inline)
                        .labelsHidden()
                    }
                }
                .disabled(!subscriptionManager.hasFullAccess)
            } header: {
                Text("タスクコース")
            } footer: {
                Text("その日のコースを選ぶと、旋回点の通過通知・通過履歴に加えて、各機体の周回時間(スタートライン通過〜全旋回点〜ゴールライン通過)を計算し、詳細カード・マーカー・通過履歴に表示します。ゴールしたときは通知します。関東学生グライダー競技会の競技規則(Ver.3-2026-09-28)・細則(Ver.2026-09-28)に基づく参考値で、公式の判定(各機のGPSロガー)とは数秒〜十数秒ずれることがあります。旋回点・コースは公式資料に掲載された固定の座標です。")
            }
        }
        .navigationTitle("タスクコース・周回時間")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct GroundThresholdSettingsPage: View {
    @EnvironmentObject private var alertSettings: AlertSettings

    var body: some View {
        Form {
            Section {
                Stepper(value: $alertSettings.minimumFlyingAltitudeM, in: 0...500, step: 10) {
                    Text("高度 \(Int(alertSettings.minimumFlyingAltitudeM)) m 以下は地上とみなす")
                }
            } footer: {
                Text("この高度以下は駐機中・着陸後とみなし、距離に関わらず高度不足アラート・競技会ガイドラインのアラートを出しません。軌跡もこの高度以下で消去されます。滑空場の標高より少し高い値にしておくと、実際に飛んでいない機体を誤って警告しにくくなります。")
            }
        }
        .navigationTitle("地上判定(共通)")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct LowAltitudeAlertSettingsPage: View {
    @EnvironmentObject private var alertSettings: AlertSettings
    @EnvironmentObject private var subscriptionManager: SubscriptionManager
    @State private var showDeleteAllStepsConfirmation = false

    var body: some View {
        Form {
            Section {
                SubscribeRow()
                Group {
                    Toggle("高度不足アラートを有効にする", isOn: $alertSettings.isEnabled)

                    Picker("基準地点", selection: $alertSettings.referenceField) {
                        ForEach(AlertReferenceField.allCases, id: \.self) { field in
                            Text(field.displayName).tag(field)
                        }
                    }
                    .pickerStyle(.segmented)

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
                }
                .disabled(!subscriptionManager.hasFullAccess)
            } footer: {
                Text("妻沼滑空場中心から15km以内の機体だけが対象です。「距離ごとの段階」は、段階を複数追加して距離ごとに必要な高度を設定する方式です(各機体には、その時点の距離以下となる段階のうち最も距離が大きいものが適用されます)。「帰投高度とL/D」は、基準地点での必要高度に、距離÷滑空比(L/D)を加えた高度を必要高度とする方式で、警告と注意で異なる滑空比を設定し2段階で警告します。警告の滑空比は注意の滑空比より大きい値(より楽観的な数値)にしてください。地図上には、段階方式では各段階の距離を半径とした円が表示されます。基準地点は、競技会ガイドラインと同じ第1滑空場基準か、第2滑空場基準かを選べます。あくまで目安であり、実際の判断の根拠にはしないでください。高度は本サイトが提供する値(海抜高)をそのまま使っています。")
            }
        }
        .navigationTitle("高度不足アラート")
        .navigationBarTitleDisplayMode(.inline)
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
    }
}

private struct CompetitionGuidelineSettingsPage: View {
    @EnvironmentObject private var competitionGuideline: CompetitionAltitudeGuideline
    @EnvironmentObject private var subscriptionManager: SubscriptionManager

    var body: some View {
        Form {
            Section {
                SubscribeRow()
                Toggle("有効にする", isOn: $competitionGuideline.isEnabled)
                    .disabled(!subscriptionManager.hasFullAccess)
            } footer: {
                Text("妻沼滑空場中心から15km以内の機体だけが対象です。日本学生航空連盟(JSAL)妻沼滑空場の公式ガイドライン(Ver.2026-01-26)を使用します。滑空場中心(N36°12'41\", E139°25'08\")から2.5km未満は制限なし、2.5〜3kmでMSL350m以上、以降1kmごとに70mずつ増加し、10km以上でMSL910m以上が必要です。公式資料に基づく固定値のため、数値はここでは変更できません(高度不足アラートとは別に、両方同時に有効化できます)。旋回点・タスクコースの表示は「タスクコース・周回時間」で設定します。")
            }
        }
        .navigationTitle("競技会ガイドライン")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct UpperAltitudeSettingsPage: View {
    @EnvironmentObject private var upperAltitudeGuideline: UpperAltitudeGuideline
    @EnvironmentObject private var subscriptionManager: SubscriptionManager

    var body: some View {
        Form {
            Section {
                SubscribeRow()
                Group {
                    Toggle("有効にする", isOn: $upperAltitudeGuideline.isEnabled)

                    Picker("B区域の上限の決め方", selection: $upperAltitudeGuideline.mode) {
                        Text("自動(平日/土日)").tag(UpperCeilingMode.auto)
                        Text("競技会中(手動指定)").tag(UpperCeilingMode.competition)
                    }
                    .pickerStyle(.segmented)

                    if upperAltitudeGuideline.mode == .competition {
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
                }
                .disabled(!subscriptionManager.hasFullAccess)
            } footer: {
                Text("妻沼滑空場中心から15km以内の機体だけが対象です。公式資料のA区域・B区域の境界に基づき、区域内でその上限高度を超えるとアラートを出します。A区域は常に4,500ft MSL。B区域は土日3,500ft、それ以外(祝日を含む)は2,500ftです。競技会など別の上限が許可されている期間は「競技会中」を選び、許可された値を入力してください。区域の境界(緯度経度)は公式資料に基づく固定値のため、ここでは変更できません。")
            }
        }
        .navigationTitle("上限高度アラート")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct ProximityAlertSettingsPage: View {
    @EnvironmentObject private var proximityAlertSettings: ProximityAlertSettings
    @EnvironmentObject private var subscriptionManager: SubscriptionManager

    var body: some View {
        Form {
            Section {
                SubscribeRow()
                Group {
                    Toggle("有効にする", isOn: $proximityAlertSettings.isEnabled)

                    Stepper(value: $proximityAlertSettings.cautionDistanceM, in: 50...2000, step: 50) {
                        Text("注意(地図表示のみ): 水平距離 \(Int(proximityAlertSettings.cautionDistanceM)) m 以内")
                    }
                    Stepper(value: $proximityAlertSettings.warningDistanceM, in: 30...1000, step: 10) {
                        Text("警告(通知): 水平距離 \(Int(proximityAlertSettings.warningDistanceM)) m 以内")
                    }
                    Stepper(value: $proximityAlertSettings.maxAltitudeDifferenceM, in: 10...500, step: 10) {
                        Text("高度差 \(Int(proximityAlertSettings.maxAltitudeDifferenceM)) m 以内のみ対象")
                    }
                    Stepper(value: $proximityAlertSettings.patternExclusionRadiusKm, in: 0.5...5, step: 0.5) {
                        Text("場周除外: 基準地点から \(proximityAlertSettings.patternExclusionRadiusKm, specifier: "%.1f") km 以内")
                    }
                    Stepper(value: $proximityAlertSettings.patternExclusionCeilingM, in: 50...3000, step: 50) {
                        Text("場周除外: 高度 \(Int(proximityAlertSettings.patternExclusionCeilingM)) m 以下")
                    }
                }
                .disabled(!subscriptionManager.hasFullAccess)
            } footer: {
                Text("妻沼滑空場中心から15km以内の機体だけが対象です。水平距離が近く、高度差も小さい機体同士を検知します。地図上の色分け(注意)は距離だけで表示しますが、プッシュ通知(警告)は距離が縮まり続けている場合のみ送ります。サーマルで複数機が近接して旋回するのは通常のことなので、離れつつある/一定の距離を保っている場合は通知しません。「場周除外」は、基準地点付近かつ低高度(場周経路)にいる機体同士を注意表示のみにとどめ、通知を出さないようにする設定です。着陸のたびに追従・近接するのは正常な状態のため、そこを検知対象から外します。位置情報は数秒〜数十秒間隔のポーリングによるものであり、リアルタイムのGPSではないため、あくまで参考情報です。実際の見張り・衝突回避の代わりにはなりません。")
            }
        }
        .navigationTitle("機体接近アラート")
        .navigationBarTitleDisplayMode(.inline)
    }
}
