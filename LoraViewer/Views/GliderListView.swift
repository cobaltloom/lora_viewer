import SwiftUI

struct GliderListView: View {
    @ObservedObject var viewModel: GliderTrackerViewModel
    @EnvironmentObject private var favoritesStore: FavoritesStore
    @EnvironmentObject private var nicknameStore: NicknameStore
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

            ForEach(sortedPositions) { glider in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        FavoriteButton(imei: glider.imei)
                        EditableGliderName(imei: glider.imei, baseName: viewModel.nameFor(index: glider.index))
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
                    }
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                }
                .padding(.vertical, 4)
            }
        }
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
}
