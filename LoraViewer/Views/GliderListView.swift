import SwiftUI

struct GliderListView: View {
    @ObservedObject var viewModel: GliderTrackerViewModel
    @State private var showBoardScan = false

    var body: some View {
        List(viewModel.positions.sorted(by: { $0.index < $1.index })) { glider in
            VStack(alignment: .leading, spacing: 4) {
                HStack {
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
