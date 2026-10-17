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

    /// Today's goal and partial-lap rows, pinned above the full history so they don't get buried
    /// among the turnpoint passages - in the chosen order. Partial laps (no lap time) follow the
    /// laps by lap time, and rows saved before scoring go last in those orders.
    private func todaysScoredFlights(scores: [UUID: Double]) -> [TurnpointPassageRecord] {
        let flights = log.todaysRecords.filter(\.isScoredFlight) // newest first
        switch sortOrder {
        case .score:
            return flights.sorted { (scores[$0.id] ?? -1) > (scores[$1.id] ?? -1) }
        case .lapTime:
            return flights.sorted {
                ($0.lapSeconds ?? .infinity, -(scores[$0.id] ?? -1)) < ($1.lapSeconds ?? .infinity, -(scores[$1.id] ?? -1))
            }
        case .time:
            return flights
        }
    }

    private var todaysLaps: [LapScore.Lap] {
        LapScore.laps(from: log.todaysRecords) { id in
            id.map(handicapStore.handicap(for:)) ?? GliderHandicapStore.defaultHandicap
        }
    }

    /// "To beat" rows: the lap time each handicap class needs to top today's best score, over the
    /// day's course distance. Shown once someone has completed a lap; 0.90-1.00 always, the slower
    /// classes only when such a glider has scored today.
    private func toBeatRows(_ laps: [LapScore.Lap]) -> [ToBeatCard.Row] {
        guard let distanceKm = laps.filter({ $0.lapSeconds != nil }).map(\.distanceKm).max() else { return [] }
        let handicaps = Set([0.90, 0.95, 1.00] + laps.map(\.handicap)).sorted()
        return handicaps.compactMap { handicap in
            LapScore.lapSecondsToBeat(laps, distanceKm: distanceKm, handicap: handicap).map {
                ToBeatCard.Row(handicap: handicap, seconds: $0)
            }
        }
    }

    var body: some View {
        let laps = todaysLaps
        let scores = LapScore.scores(laps)
        let finishes = todaysScoredFlights(scores: scores)
        let toBeat = toBeatRows(laps)
        let leader = finishes.first { record in scores[record.id] == scores.values.max() }
        List {
            if log.todaysRecords.isEmpty {
                ContentUnavailableView("本日の旋回点通過はまだありません", systemImage: "airplane")
            } else {
                if !finishes.isEmpty {
                    Section {
                        if !toBeat.isEmpty, let leader, let leaderScore = scores[leader.id] {
                            ToBeatCard(leaderName: leader.gliderName, leaderScore: leaderScore,
                                       leaderLapSeconds: leader.lapSeconds, rows: toBeat)
                                .listRowInsets(EdgeInsets(top: 6, leading: 0, bottom: 6, trailing: 0))
                                .listRowBackground(Color.clear)
                        }
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
                        Text("周回・得点")
                    } footer: {
                        if !scores.isEmpty {
                            Text("参考得点は競技規則53の式(距離・速度・ハンディキャップ)で、この端末が計時した飛行ごとに計算した目安です。周回できなかった飛行は、規則56(1)により細則2の距離表の距離点のみです。ゴール高度が280〜330m未満は50点減点、280m未満は速度点なしです(細則7 別表)。「1位を上回るのに必要な周回時間」は、その時間で周回すると全員の得点を計算し直した結果1位になる時間です(ゴール高度330m以上の場合)。公式成績は各選手のその日最後の飛行をIGCロガーで判定します。ハンディキャップは関東大会の出場機には機種に合わせた値が入っており、それ以外は1.00です。得点の行をタップするか、メンバー一覧で機体ごとに変更できます。")
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

    /// "・ゴール高度不足 -50点" / "・ゴール280m未満のため速度点なし" for a low goal (細則7 別表(4)).
    private static func goalPenaltyNote(_ record: TurnpointPassageRecord) -> String {
        guard record.isFinish else { return "" }
        switch LapScore.goalPenalty(altitudeM: record.altitudeM) {
        case .none: return ""
        case .deduction: return "・ゴール高度330m未満 -50点"
        case .noSpeedPoints: return "・ゴール280m未満のため速度点なし"
        }
    }

    private func row(for record: TurnpointPassageRecord, score: Double?) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                if record.isFinish {
                    Image(systemName: "flag.checkered")
                        .foregroundStyle(.green)
                } else if record.isPartialLap {
                    Image(systemName: "flag")
                        .foregroundStyle(.orange)
                }
                Text(record.gliderName)
                    .bold()
                Spacer()
                Text(record.timestamp, style: .time)
                    .foregroundStyle(.secondary)
            }
            Text(record.isPartialLap ? record.turnpointName : "\(record.turnpointName)・\(record.altitudeM.map { "\(Int($0))m" } ?? "高度不明")")
                .font(.subheadline)
                .foregroundStyle(record.isScoredFlight ? .primary : .secondary)
                .fontWeight(record.isScoredFlight ? .semibold : .regular)
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
                    Text("参考得点 \(score, specifier: "%.1f")点(ハンデ \(handicapStore.handicap(for: gliderID), specifier: "%.2f")\(Self.goalPenaltyNote(record)))")
                        .font(.subheadline.bold())
                        .foregroundStyle(.green)
                }
            }
        }
    }
}

/// The history's to-beat panel, styled after a ski jump broadcast: today's leader, then the lap
/// time each handicap class must beat to take the lead (see `LapScore.lapSecondsToBeat`).
struct ToBeatCard: View {
    struct Row: Identifiable {
        let handicap: Double
        /// `.infinity` when any completed lap would do.
        let seconds: Double
        var id: Double { handicap }
    }

    let leaderName: String
    let leaderScore: Double
    let leaderLapSeconds: Double?
    let rows: [Row]

    private static func types(for handicap: Double) -> String {
        GliderHandicapStore.options.first { $0.value == handicap }?.types ?? ""
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .center) {
                ToBeatTag()
                Spacer()
                VStack(alignment: .trailing, spacing: 1) {
                    Text("1位  \(leaderName)")
                        .font(.caption.weight(.bold))
                        .lineLimit(1)
                    HStack(spacing: 6) {
                        if let leaderLapSeconds {
                            Text(ToBeat.clock(leaderLapSeconds))
                        }
                        Text(String(format: "%.1f点", leaderScore))
                    }
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.white.opacity(0.75))
                }
            }
            ForEach(rows) { row in
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    Text(String(format: "%.2f", row.handicap))
                        .font(.caption.weight(.bold).monospacedDigit())
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .overlay(RoundedRectangle(cornerRadius: 4).stroke(.white.opacity(0.6)))
                    Text(Self.types(for: row.handicap))
                        .font(.caption2)
                        .foregroundStyle(.white.opacity(0.65))
                        .lineLimit(1)
                    Spacer(minLength: 4)
                    Text(row.seconds.isInfinite ? "周回で1位" : ToBeat.clock(row.seconds))
                        .font(.system(.title2, design: .rounded).weight(.bold).monospacedDigit())
                        .foregroundStyle(Color.toBeatAccent)
                }
            }
            Text("この時間より速く周回すると1位(ゴール330m以上で通過した場合)")
                .font(.caption2)
                .foregroundStyle(.white.opacity(0.6))
        }
        .padding(14)
        .background(Color.toBeatPanel, in: RoundedRectangle(cornerRadius: 14))
        .foregroundStyle(.white)
    }
}
