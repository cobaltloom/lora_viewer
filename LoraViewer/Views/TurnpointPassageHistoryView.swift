import SwiftUI

/// Shows today's turnpoint-passage events (see `TurnpointPassageLog`) as a
/// backup to the push notification, in case it was missed.
struct TurnpointPassageHistoryView: View {
    @ObservedObject var log: TurnpointPassageLog

    /// Today's goal rows (lap completed, with time and speed), pinned above the full history so they
    /// don't get buried among the turnpoint passages.
    private var todaysFinishes: [TurnpointPassageRecord] {
        log.todaysRecords.filter(\.isFinish)
    }

    var body: some View {
        List {
            if log.todaysRecords.isEmpty {
                ContentUnavailableView("本日の旋回点通過はまだありません", systemImage: "airplane")
            } else {
                if !todaysFinishes.isEmpty {
                    Section("周回達成") {
                        ForEach(todaysFinishes) { record in
                            row(for: record)
                        }
                    }
                }
                Section {
                    ForEach(log.todaysRecords) { record in
                        row(for: record)
                    }
                } header: {
                    if !todaysFinishes.isEmpty {
                        Text("すべての履歴")
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

    private func row(for record: TurnpointPassageRecord) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                if record.isFinish {
                    Image(systemName: "flag.checkered")
                        .foregroundStyle(.green)
                }
                Text(record.gliderName)
                    .bold()
                Spacer()
                Text(record.timestamp, style: .time)
                    .foregroundStyle(.secondary)
            }
            Text("\(record.turnpointName)・\(record.altitudeM.map { "\(Int($0))m" } ?? "高度不明")")
                .font(.subheadline)
                .foregroundStyle(record.isFinish ? .primary : .secondary)
                .fontWeight(record.isFinish ? .semibold : .regular)
        }
    }
}
