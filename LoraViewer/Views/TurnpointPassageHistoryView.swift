import SwiftUI

/// Shows today's turnpoint-passage events (see `TurnpointPassageLog`) as a
/// backup to the push notification, in case it was missed.
struct TurnpointPassageHistoryView: View {
    @ObservedObject var log: TurnpointPassageLog
    @EnvironmentObject private var handicapStore: GliderHandicapStore
    @AppStorage("finishSortOrder") private var sortOrder: FinishSortOrder = .score

    enum FinishSortOrder: String, CaseIterable, Identifiable {
        case score, lapTime, time
        var id: String { rawValue }
        var label: String {
            switch self {
            case .score: return "得点順"
            case .lapTime: return "所要時間順"
            case .time: return "時刻順"
            }
        }
    }

    /// Today's goal rows (lap completed, with time and speed), pinned above the full history so they
    /// don't get buried among the turnpoint passages - in the chosen order. Rows without a score or
    /// lap time (saved before scoring) go last in those orders.
    private func todaysFinishes(scores: [UUID: Double]) -> [TurnpointPassageRecord] {
        let finishes = log.todaysRecords.filter(\.isFinish) // newest first
        switch sortOrder {
        case .score:
            return finishes.sorted { (scores[$0.id] ?? -1) > (scores[$1.id] ?? -1) }
        case .lapTime:
            return finishes.sorted { ($0.lapSeconds ?? .infinity) < ($1.lapSeconds ?? .infinity) }
        case .time:
            return finishes
        }
    }

    /// Reference scores for today's goal rows that carry a lap time (see `LapScore`).
    private var todaysScores: [UUID: Double] {
        LapScore.scores(log.todaysRecords.compactMap { record in
            guard record.isFinish, let lapSeconds = record.lapSeconds, let distanceKm = record.courseDistanceKm else { return nil }
            let handicap = record.gliderID.map(handicapStore.handicap(for:)) ?? GliderHandicapStore.defaultHandicap
            return LapScore.Lap(id: record.id, distanceKm: distanceKm, lapSeconds: lapSeconds, handicap: handicap)
        })
    }

    var body: some View {
        let scores = todaysScores
        let finishes = todaysFinishes(scores: scores)
        List {
            if log.todaysRecords.isEmpty {
                ContentUnavailableView("本日の旋回点通過はまだありません", systemImage: "airplane")
            } else {
                if !finishes.isEmpty {
                    Section {
                        Picker("並び順", selection: $sortOrder) {
                            ForEach(FinishSortOrder.allCases) { order in
                                Text(order.label).tag(order)
                            }
                        }
                        .pickerStyle(.segmented)
                        ForEach(finishes) { record in
                            row(for: record, score: scores[record.id])
                        }
                    } header: {
                        Text("周回達成")
                    } footer: {
                        if !scores.isEmpty {
                            Text("参考得点は競技規則53の式(距離・速度・ハンディキャップ)で、この端末が計時した周回ごとに計算した目安です。公式成績は各選手のその日最後の飛行をIGCロガーで判定します。ハンディキャップは関東大会の出場機には機種に合わせた値が入っており、それ以外は1.00です。得点の行をタップするか、メンバー一覧で機体ごとに変更できます。")
                        }
                    }
                }
                Section {
                    ForEach(log.todaysRecords) { record in
                        row(for: record, score: scores[record.id])
                    }
                } header: {
                    if !finishes.isEmpty {
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

    private func row(for record: TurnpointPassageRecord, score: Double?) -> some View {
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
            if let score, let gliderID = record.gliderID {
                // Tappable, so a glider that has left the member list can still get its handicap.
                Menu {
                    Picker("ハンディキャップ", selection: Binding(
                        get: { handicapStore.handicap(for: gliderID) },
                        set: { handicapStore.setHandicap($0, for: gliderID) }
                    )) {
                        ForEach(GliderHandicapStore.options) { option in
                            Text(String(format: "%.2f", option.value) + "  " + option.types).tag(option.value)
                        }
                    }
                } label: {
                    Text("参考得点 \(score, specifier: "%.1f")点(ハンデ \(handicapStore.handicap(for: gliderID), specifier: "%.2f"))")
                        .font(.subheadline.bold())
                        .foregroundStyle(.green)
                }
            }
        }
    }
}
