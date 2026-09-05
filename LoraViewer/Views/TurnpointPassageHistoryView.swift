import SwiftUI

/// Shows today's turnpoint-passage events (see `TurnpointPassageLog`) as a
/// backup to the push notification, in case it was missed.
struct TurnpointPassageHistoryView: View {
    @ObservedObject var log: TurnpointPassageLog

    var body: some View {
        List {
            if log.todaysRecords.isEmpty {
                ContentUnavailableView("本日の旋回点通過はまだありません", systemImage: "airplane")
            } else {
                ForEach(log.todaysRecords) { record in
                    VStack(alignment: .leading, spacing: 2) {
                        HStack {
                            Text(record.gliderName)
                                .bold()
                            Spacer()
                            Text(record.timestamp, style: .time)
                                .foregroundStyle(.secondary)
                        }
                        Text("\(record.turnpointName)・\(record.altitudeM.map { "\(Int($0))m" } ?? "高度不明")")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .navigationTitle("旋回点通過履歴")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if !log.todaysRecords.isEmpty {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("消去", role: .destructive) {
                        log.clearToday()
                    }
                }
            }
        }
    }
}
