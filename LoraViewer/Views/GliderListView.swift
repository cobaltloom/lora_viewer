import SwiftUI

struct GliderListView: View {
    @ObservedObject var viewModel: GliderTrackerViewModel
    @EnvironmentObject private var favoritesStore: FavoritesStore
    @EnvironmentObject private var nicknameStore: NicknameStore
    @EnvironmentObject private var handicapStore: GliderHandicapStore
    @State private var showBoardScan = false

    private var sortedPositions: [GliderPosition] {
        viewModel.positions.sorted { lhs, rhs in
            let lhsFavorite = favoritesStore.isFavorite(lhs.imei)
            let rhsFavorite = favoritesStore.isFavorite(rhs.imei)
            if lhsFavorite != rhsFavorite {
                return lhsFavorite && !rhsFavorite
            }
            return lhs.index < rhs.index
        }
    }

    var body: some View {
        List {
            Section {
                Text("★でお気に入りに登録した機体が1機以上あると、高度不足アラート(カスタム設定)と競技会ガイドラインのアラートはお気に入りの機体だけが対象になります。お気に入りが0機のときは全機が対象です。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section {
                Picker("ニックネームの同期", selection: $nicknameStore.syncMode) {
                    Text("他の人と同期").tag(NicknameSyncMode.synced)
                    Text("手動入力").tag(NicknameSyncMode.manual)
                }
                .pickerStyle(.segmented)
            } footer: {
                Text(nicknameStore.syncMode == .synced
                     ? "ニックネームは他の利用者と共有されます。誰でも編集できるため、いたずらで書き換えられた場合は「手動入力」に切り替えてください。"
                     : "この端末だけのニックネームになります。他の利用者の変更は反映されず、この端末での変更も共有されません。")
            }

            Section {
                ForEach(sortedPositions) { glider in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            if !glider.isAnonymous {
                                FavoriteButton(imei: glider.imei)
                            }
                            EditableGliderName(imei: glider.imei, baseName: viewModel.nameFor(glider), isEditable: !glider.isAnonymous)
                                .font(.headline)
                            Spacer()
                            if glider.isDisconnected {
                                Text("切断")
                                    .font(.caption)
                                    .foregroundStyle(.red)
                            } else {
                                Text(glider.source.label)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        HStack(spacing: 12) {
                            if let alt = glider.alt {
                                Text("高度 \(Int(alt)) m")
                            }
                            if let date = glider.positionDateTimeUTC {
                                Text(date, style: .time)
                            }
                            Spacer()
                            if !glider.isAnonymous {
                                handicapMenu(for: glider.imei)
                            }
                        }
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 4)
                }
            }
        }
        .safeAreaInset(edge: .bottom) { AdBanner() }
        .navigationTitle("メンバー一覧")
        .refreshable {
            await viewModel.refreshOnce()
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showBoardScan = true
                } label: {
                    Label("名簿から登録", systemImage: "text.viewfinder")
                }
            }
        }
        .sheet(isPresented: $showBoardScan) {
            BoardScanView(viewModel: viewModel)
        }
    }

    /// The glider's competition handicap, used for the reference scores of its completed laps.
    private func handicapMenu(for id: String) -> some View {
        Menu {
            Picker("ハンディキャップ", selection: Binding(
                get: { handicapStore.handicap(for: id) },
                set: { handicapStore.setHandicap($0, for: id) }
            )) {
                ForEach(GliderHandicapStore.options) { option in
                    Text(String(format: "%.2f", option.value) + "  " + option.types).tag(option.value)
                }
            }
        } label: {
            Text(String(format: "ハンデ %.2f", handicapStore.handicap(for: id)))
                .font(.caption)
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .overlay(Capsule().stroke(.secondary.opacity(0.5)))
        }
    }
}
