import SwiftUI

struct GliderListView: View {
    @ObservedObject var viewModel: GliderTrackerViewModel
    @EnvironmentObject private var favoritesStore: FavoritesStore
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
        List(sortedPositions) { glider in
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
