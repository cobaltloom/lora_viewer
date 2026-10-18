import SwiftUI

/// Provisional team standings: the official totals a representative entered (shared through the
/// track server) plus today's reference scores of each team's gliders, and for each team the lap time
/// that would take it to P1, or one place up, today.
struct TeamStandingsView: View {
    let server: TrackServerConfig?
    @ObservedObject var log: TurnpointPassageLog
    /// The selected task course: today's flights are scored on it, and lap times are over its distance.
    let course: CompetitionTaskCourse?
    /// Gliders the app knows now (id, name), for the editor's glider picker.
    let knownGliders: [(id: String, name: String)]
    @EnvironmentObject private var handicapStore: GliderHandicapStore

    @State private var standings: TeamStandings?
    @State private var errorMessage: String?
    @State private var showEditor = false
    private let api = TrackServerAPI()

    /// Once the official totals include today, today's flights mustn't be added again.
    private var includesToday: Bool {
        standings?.throughDate != Self.dateFormatter.string(from: Date())
    }

    static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter
    }()

    private var flights: [TeamRanking.Flight] {
        let records = log.todaysRecords.filter { $0.belongs(toCourse: course?.name) }
        let gliders = Dictionary(records.map { ($0.id, $0.gliderID) }, uniquingKeysWith: { first, _ in first })
        let laps = LapScore.laps(from: records) { id in
            id.map(handicapStore.handicap(for:)) ?? GliderHandicapStore.defaultHandicap
        }
        return laps.map { TeamRanking.Flight(lap: $0, gliderID: gliders[$0.id] ?? nil) }
    }

    /// Each team's lap time to P1 and to one place up, in its first glider.
    private func targets(for row: TeamRanking.Row, standings: TeamStandings, flights: [TeamRanking.Flight]) -> [(rank: Int, seconds: Double?)] {
        guard let course, includesToday, row.rank > 1,
              let glider = standings.teams.first(where: { $0.name == row.name })?.gliderIds.first else { return [] }
        let ranks = [1] + (row.rank - 1 > 1 ? [row.rank - 1] : [])
        return ranks.map { target in
            (target, TeamRanking.lapSecondsToReach(standings, flights: flights, teamName: row.name, gliderID: glider,
                                                  handicap: handicapStore.handicap(for: glider),
                                                  distanceKm: course.distanceKm, targetRank: target))
        }
    }

    var body: some View {
        List {
            if let standings {
                let flights = self.flights
                let rows = TeamRanking.rows(standings, flights: flights, includeToday: includesToday)
                Section {
                    ForEach(rows) { row in
                        rowView(row, targets: targets(for: row, standings: standings, flights: flights))
                    }
                } header: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(standings.competition.isEmpty ? "競技会" : standings.competition)
                            .font(.headline)
                            .foregroundStyle(.primary)
                        let through = [standings.throughDay.map { "第\($0)日" }, standings.throughDate].compactMap { $0 }.joined(separator: " ")
                        Text("公式: \(through.isEmpty ? "-" : through)終了時点" + (includesToday ? " + 本日の暫定(参考得点の上位3本)" : "(本日分を含む)"))
                        if includesToday, course == nil {
                            Text("タスクコースを選ぶと、順位を上げるのに必要な周回時間を表示します。")
                        }
                    }
                    .textCase(nil)
                } footer: {
                    Text("本日の暫定は、この端末が計時した飛行の参考得点(各チームの上位3本)を、選手が別々として合計した目安です。全国選出点は各チームの本日最高得点を加えています。公式成績とは異なることがあります。")
                }
            } else {
                ContentUnavailableView {
                    Label("団体成績がありません", systemImage: "trophy")
                } description: {
                    Text(errorMessage ?? "代表者がまだ団体成績を入力していません。右上の「入力」から、合言葉を知っている代表者が入力できます。")
                }
            }
        }
        .navigationTitle("団体順位")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("入力") { showEditor = true }
            }
        }
        .sheet(isPresented: $showEditor) {
            NavigationStack {
                StandingsEditorView(server: server, initial: standings, knownGliders: knownGliders) { saved in
                    standings = saved
                }
            }
        }
        // Fetched on opening and every minute while open: the representative may update it any time.
        .task {
            while !Task.isCancelled {
                await refresh()
                try? await Task.sleep(nanoseconds: 60_000_000_000)
            }
        }
    }

    private func refresh() async {
        guard let server else {
            errorMessage = "トラックサーバーが設定されていません"
            return
        }
        do {
            standings = try await api.fetchStandings(server: server)
            errorMessage = nil
        } catch {
            errorMessage = "団体成績を取得できませんでした"
        }
    }

    private func rowView(_ row: TeamRanking.Row, targets: [(rank: Int, seconds: Double?)]) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text("\(row.rank)")
                    .font(.title2.bold().monospacedDigit())
                    .frame(width: 32, alignment: .leading)
                VStack(alignment: .leading, spacing: 1) {
                    Text(row.name).font(.headline)
                    Text(includesToday ? "公式 \(Int(row.official)) + 本日 \(Int(row.today))(\(row.flightsToday)本)" : "公式")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text("全国選出点 \(Int(row.nationalTotal))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Text("\(Int(row.total))")
                    .font(.title2.bold().monospacedDigit())
            }
            if !targets.isEmpty {
                HStack(spacing: 10) {
                    ToBeatTag(text: "TARGET")
                    ForEach(targets.indices, id: \.self) { index in
                        let target = targets[index]
                        Text("\(target.rank == 1 ? "P1" : "\(target.rank)位") \(Self.label(target.seconds))")
                            .font(.subheadline.bold().monospacedDigit())
                            .foregroundStyle(target.seconds == nil ? Color.secondary : Color.accentColor)
                    }
                }
                .padding(.leading, 32)
            }
        }
        .padding(.vertical, 2)
    }

    private static func label(_ seconds: Double?) -> String {
        guard let seconds else { return "届かない" }
        return seconds.isInfinite ? "周回で到達" : ToBeat.clock(seconds)
    }
}

/// Where a representative (who knows the editor key set on the track server) enters the
/// competition's teams, their gliders and the official totals so far; saving shares them with
/// every app.
struct StandingsEditorView: View {
    let server: TrackServerConfig?
    let initial: TeamStandings?
    let knownGliders: [(id: String, name: String)]
    let onSaved: (TeamStandings) -> Void

    /// One team being edited: text fields as typed, turned into numbers on saving.
    private struct TeamDraft: Identifiable {
        let id = UUID()
        var name: String
        var gliderIds: [String]
        var total: String
        var national: String
    }

    @Environment(\.dismiss) private var dismiss
    @AppStorage("standingsEditorKey") private var editorKey = ""
    @State private var competition = ""
    @State private var throughDay = ""
    @State private var throughDate = Calendar.current.date(byAdding: .day, value: -1, to: Date()) ?? Date()
    @State private var teams: [TeamDraft] = []
    @State private var message: String?
    @State private var saving = false
    @State private var loaded = false
    private let api = TrackServerAPI()

    private static func field(_ value: Double?) -> String {
        guard let value else { return "" }
        return value == value.rounded() ? String(Int(value)) : String(value)
    }

    private func name(of id: String) -> String {
        knownGliders.first { $0.id == id }.map { "\($0.name) (\(id))" } ?? id
    }

    var body: some View {
        Form {
            if let message {
                Section { Text(message).foregroundStyle(Color.accentColor) }
            }
            Section {
                SecureField("合言葉(代表者用)", text: $editorKey)
                TextField("競技会名", text: $competition)
                TextField("第◯日終了時点(数字)", text: $throughDay)
                    .keyboardType(.numberPad)
                DatePicker("その日の日付", selection: $throughDate, displayedComponents: .date)
            } footer: {
                Text("日付が今日なら、本日分は公式に含まれているものとして暫定得点を足しません。")
            }
            Section {
                Button("チームを追加") { teams.append(TeamDraft(name: "", gliderIds: [], total: "", national: "")) }
                Button("今の機体から作成") {
                    let assigned = Set(teams.flatMap(\.gliderIds))
                    for glider in knownGliders where !assigned.contains(glider.id) {
                        teams.append(TeamDraft(name: glider.name, gliderIds: [glider.id], total: "", national: ""))
                    }
                }
            } footer: {
                Text("機体は、地図に今出ている機体(LoRa・FLARMそれぞれ)から選びます。LoRaとFLARMの両方を使う場合は、同じチームに両方の機体を選んでください。")
            }
            ForEach($teams) { $team in
                Section {
                    TextField("チーム名", text: $team.name)
                    HStack {
                        TextField("団体 累計", text: $team.total).keyboardType(.decimalPad)
                        TextField("全国選出点 累計", text: $team.national).keyboardType(.decimalPad)
                    }
                    Menu {
                        ForEach(Array(Set(knownGliders.map { $0.id } + team.gliderIds)).sorted(), id: \.self) { id in
                            Button {
                                if let index = team.gliderIds.firstIndex(of: id) {
                                    team.gliderIds.remove(at: index)
                                } else {
                                    team.gliderIds.append(id)
                                }
                            } label: {
                                if team.gliderIds.contains(id) {
                                    Label(name(of: id), systemImage: "checkmark")
                                } else {
                                    Text(name(of: id))
                                }
                            }
                        }
                    } label: {
                        Text("機体: " + (team.gliderIds.isEmpty ? "なし(タップして選ぶ)" : team.gliderIds.map(name(of:)).joined(separator: "、")))
                            .font(.subheadline)
                    }
                    Button("このチームを削除", role: .destructive) {
                        teams.removeAll { $0.id == team.id }
                    }
                }
            }
        }
        .navigationTitle("団体成績の入力")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button("閉じる") { dismiss() }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button("保存") { Task { await save() } }
                    .disabled(saving)
            }
        }
        .onAppear {
            guard !loaded else { return }
            loaded = true
            guard let initial else { return }
            competition = initial.competition
            throughDay = initial.throughDay.map(String.init) ?? ""
            if let date = initial.throughDate.flatMap(TeamStandingsView.dateFormatter.date(from:)) {
                throughDate = date
            }
            teams = initial.teams.map {
                TeamDraft(name: $0.name, gliderIds: $0.gliderIds, total: Self.field($0.total), national: Self.field($0.national))
            }
        }
    }

    private func save() async {
        guard let server else {
            message = "トラックサーバーが設定されていません"
            return
        }
        guard !teams.contains(where: { $0.name.trimmingCharacters(in: .whitespaces).isEmpty }) else {
            message = "チーム名が空のチームがあります"
            return
        }
        let standings = TeamStandings(
            competition: competition.trimmingCharacters(in: .whitespaces),
            throughDay: Int(throughDay.trimmingCharacters(in: .whitespaces)),
            throughDate: TeamStandingsView.dateFormatter.string(from: throughDate),
            teams: teams.map {
                TeamStandings.Team(name: $0.name.trimmingCharacters(in: .whitespaces), gliderIds: $0.gliderIds,
                                   total: Double($0.total.trimmingCharacters(in: .whitespaces)),
                                   national: Double($0.national.trimmingCharacters(in: .whitespaces)))
            }
        )
        saving = true
        defer { saving = false }
        do {
            let saved = try await api.saveStandings(server: server, editorKey: editorKey, standings: standings)
            onSaved(saved)
            message = "保存しました。全員の団体順位に反映されます。"
        } catch {
            message = error.localizedDescription
        }
    }
}
