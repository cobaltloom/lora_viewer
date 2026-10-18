import SwiftUI

/// Provisional team standings over a competition a representative set up (shared through the track
/// server): each earlier competition day's team scores - corrected where someone entered them, else
/// worked out from this device's lap records - plus today's reference scores, and for each team the
/// lap time that would take it to P1, or one place up, today.
struct TeamStandingsView: View {
    let server: TrackServerConfig?
    @ObservedObject var log: TurnpointPassageLog
    /// The selected task course: today's flights are scored on it, and lap times are over its distance.
    let course: CompetitionTaskCourse?
    /// Gliders the app knows now (id, name), for the setup's glider picker.
    let knownGliders: [KnownGlider]
    @EnvironmentObject private var handicapStore: GliderHandicapStore

    @State private var competition: Competition?
    @State private var errorMessage: String?
    @State private var showEditor = false
    private let api = TrackServerAPI()

    private func handicap(_ id: String?) -> Double {
        id.map(handicapStore.handicap(for:)) ?? GliderHandicapStore.defaultHandicap
    }

    /// Today counts only during the competition.
    private var includesToday: Bool {
        guard let last = competition?.dates(until: Date()).last else { return false }
        return Calendar.current.isDateInToday(last)
    }

    private var flights: [TeamRanking.Flight] {
        let records = log.todaysRecords.filter { $0.belongs(toCourse: course?.name) }
        let gliders = Dictionary(records.map { ($0.id, $0.gliderID) }, uniquingKeysWith: { first, _ in first })
        return LapScore.laps(from: records, handicap: handicap).map { TeamRanking.Flight(lap: $0, gliderID: gliders[$0.id] ?? nil) }
    }

    /// Each team's lap time to P1 and to one place up, in its first glider.
    private func targets(for row: TeamRanking.Row, standings: TeamStandings, flights: [TeamRanking.Flight]) -> [(rank: Int, seconds: Double?)] {
        guard let course, includesToday, row.rank > 1,
              let glider = standings.teams.first(where: { $0.name == row.name })?.gliderIds.first else { return [] }
        let ranks = [1] + (row.rank - 1 > 1 ? [row.rank - 1] : [])
        return ranks.map { target in
            (target, TeamRanking.lapSecondsToReach(standings, flights: flights, teamName: row.name, gliderID: glider,
                                                  handicap: handicap(glider), distanceKm: course.distanceKm, targetRank: target))
        }
    }

    var body: some View {
        List {
            if let competition, !competition.teams.isEmpty {
                let standings = CompetitionScoring.standingsBeforeToday(competition, today: Date(), recordsOn: { log.records(on: $0) },
                                                                        handicap: handicap)
                let flights = self.flights
                let rows = TeamRanking.rows(standings, flights: flights, includeToday: includesToday)
                Section {
                    ForEach(rows) { row in
                        rowView(row, targets: targets(for: row, standings: standings, flights: flights))
                    }
                } header: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(competition.name.isEmpty ? "競技会" : competition.name)
                            .font(.headline)
                            .foregroundStyle(.primary)
                        let dayCount = competition.dates(until: Date()).count
                        Text("\(competition.startDate ?? "")〜\(competition.endDate ?? "")" +
                             (includesToday ? "(第\(dayCount)日・本日の暫定を含む)" : "(期間外: 本日分は含みません)"))
                        if includesToday, course == nil {
                            Text("タスクコースを選ぶと、順位を上げるのに必要な周回時間を表示します。")
                        }
                    }
                    .textCase(nil)
                } footer: {
                    Text("各日の得点は、その日の参考得点のチーム上位3本(全国選出点は最高点)を、選手が別々として合計した目安です。過去の日は、この端末の記録から計算するか、「設定」で修正・確定された値を使います。公式成績とは異なることがあります。")
                }
            } else {
                ContentUnavailableView {
                    Label("競技会が設定されていません", systemImage: "trophy")
                } description: {
                    Text(errorMessage ?? "右上の「設定」から、大会期間とチームを設定してください。")
                }
            }
        }
        .navigationTitle("団体順位")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("設定") { showEditor = true }
            }
        }
        .sheet(isPresented: $showEditor) {
            NavigationStack {
                CompetitionEditorView(server: server, initial: competition, knownGliders: knownGliders, log: log,
                                      todayCourseName: course?.name) { saved in
                    competition = saved
                }
                .environmentObject(handicapStore)
            }
        }
        // Fetched on opening and every minute while open: the setup may be updated any time.
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
            competition = try await api.fetchCompetition(server: server)
            errorMessage = nil
        } catch {
            errorMessage = "競技会の設定を取得できませんでした"
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
                    Text(includesToday ? "前日まで \(Int(row.official)) + 本日 \(Int(row.today))(\(row.flightsToday)本)" : "累計")
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

/// A glider the app knows now, for picking a team's gliders.
struct KnownGlider {
    let id: String
    let name: String
}

/// Where a representative sets up the competition - name, period, teams and their gliders - and
/// corrects or confirms any day's team scores (penalties, or scores that differ from the app's own);
/// saving shares it with every app.
struct CompetitionEditorView: View {
    let server: TrackServerConfig?
    let initial: Competition?
    let knownGliders: [KnownGlider]
    @ObservedObject var log: TurnpointPassageLog
    /// The selected task course, used for today's own scores.
    let todayCourseName: String?
    let onSaved: (Competition) -> Void
    @EnvironmentObject private var handicapStore: GliderHandicapStore

    /// One team being edited.
    private struct TeamDraft: Identifiable {
        let id = UUID()
        var name: String
        var gliderIds: [String]
    }

    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var startDate = Calendar.current.startOfDay(for: Date())
    @State private var endDate = Calendar.current.date(byAdding: .day, value: 4, to: Calendar.current.startOfDay(for: Date())) ?? Date()
    @State private var teams: [TeamDraft] = []
    /// Date key → team name → (団体, 全国選出点) as typed; blank = the app's own score.
    @State private var corrections: [String: [String: (total: String, national: String)]] = [:]
    @State private var selectedDay: Date?
    @State private var message: String?
    @State private var saving = false
    @State private var loaded = false
    private let api = TrackServerAPI()

    private static func field(_ value: Double?) -> String {
        guard let value else { return "" }
        return value == value.rounded() ? String(Int(value)) : String(value)
    }

    private func gliderName(_ id: String) -> String {
        knownGliders.first { $0.id == id }.map { "\($0.name) (\(id))" } ?? id
    }

    private var draft: Competition {
        var days: [String: [String: Competition.DayScore]] = [:]
        for (date, byTeam) in corrections {
            var scores: [String: Competition.DayScore] = [:]
            for (team, value) in byTeam {
                let score = Competition.DayScore(total: Double(value.total.trimmingCharacters(in: .whitespaces)),
                                                 national: Double(value.national.trimmingCharacters(in: .whitespaces)))
                if score.total != nil || score.national != nil { scores[team] = score }
            }
            if !scores.isEmpty { days[date] = scores }
        }
        return Competition(
            name: name.trimmingCharacters(in: .whitespaces),
            startDate: Competition.dateFormatter.string(from: startDate),
            endDate: Competition.dateFormatter.string(from: endDate),
            teams: teams.filter { !$0.name.trimmingCharacters(in: .whitespaces).isEmpty }
                .map { Competition.Team(name: $0.name.trimmingCharacters(in: .whitespaces), gliderIds: $0.gliderIds) },
            days: days
        )
    }

    private var teamNames: [String] {
        teams.map { $0.name.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    }

    /// The app's own scores for the selected day.
    private func autoScores(for day: Date) -> [String: Competition.DayScore] {
        let records = log.records(on: day)
        let course = Calendar.current.isDateInToday(day) ? todayCourseName : CompetitionScoring.dayCourse(records)
        return CompetitionScoring.autoDayScores(draft, dayRecords: records, courseName: course) { id in
            id.map(handicapStore.handicap(for:)) ?? GliderHandicapStore.defaultHandicap
        }
    }

    private func binding(day: String, team: String, national: Bool) -> Binding<String> {
        Binding(
            get: {
                let value = corrections[day]?[team]
                return (national ? value?.national : value?.total) ?? ""
            },
            set: { newValue in
                var byTeam = corrections[day] ?? [:]
                var value = byTeam[team] ?? (total: "", national: "")
                if national { value.national = newValue } else { value.total = newValue }
                byTeam[team] = value
                corrections[day] = byTeam
            }
        )
    }

    var body: some View {
        Form {
            if let message {
                Section { Text(message).foregroundStyle(Color.accentColor) }
            }
            Section {
                TextField("競技会名", text: $name)
                DatePicker("開始日", selection: $startDate, displayedComponents: .date)
                DatePicker("終了日", selection: $endDate, in: startDate..., displayedComponents: .date)
            } footer: {
                Text("期間内の毎日、各チームの機体の参考得点(上位3本)を自動で積み上げます。競技のない日は0点です。")
            }
            Section {
                Button("チームを追加") { teams.append(TeamDraft(name: "", gliderIds: [])) }
                Button("今の機体から作成") {
                    let assigned = Set(teams.flatMap(\.gliderIds))
                    for glider in knownGliders where !assigned.contains(glider.id) {
                        teams.append(TeamDraft(name: glider.name, gliderIds: [glider.id]))
                    }
                }
            } header: {
                Text("チーム")
            } footer: {
                Text("機体は、地図に今出ている機体(LoRa・FLARMそれぞれ)から選びます。LoRaとFLARMの両方を使う場合は、同じチームに両方の機体を選んでください。")
            }
            ForEach($teams) { $team in
                Section {
                    TextField("チーム名", text: $team.name)
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
                                    Label(gliderName(id), systemImage: "checkmark")
                                } else {
                                    Text(gliderName(id))
                                }
                            }
                        }
                    } label: {
                        Text("機体: " + (team.gliderIds.isEmpty ? "なし(タップして選ぶ)" : team.gliderIds.map(gliderName).joined(separator: "、")))
                            .font(.subheadline)
                    }
                    Button("このチームを削除", role: .destructive) {
                        teams.removeAll { $0.id == team.id }
                    }
                }
            }
            daysSection
        }
        .navigationTitle("競技会の設定")
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
        .onAppear(perform: load)
    }

    @ViewBuilder private var daysSection: some View {
        let days = draft.dates(until: Date())
        Section {
            if days.isEmpty {
                Text("期間を設定すると、日ごとの得点を確認・修正できます。")
            } else {
                Picker("日付", selection: Binding(get: { selectedDay ?? days.last }, set: { selectedDay = $0 })) {
                    ForEach(days.indices, id: \.self) { index in
                        Text("第\(index + 1)日 \(Competition.dateFormatter.string(from: days[index]))").tag(Optional(days[index]))
                    }
                }
                if let day = selectedDay ?? days.last {
                    let key = Competition.dateFormatter.string(from: day)
                    let auto = autoScores(for: day)
                    Button("この日の自動値で確定") {
                        var byTeam = corrections[key] ?? [:]
                        for team in teamNames {
                            let existing = byTeam[team]
                            let total = existing?.total ?? ""
                            let national = existing?.national ?? ""
                            byTeam[team] = (total: total.isEmpty ? Self.field(auto[team]?.total) : total,
                                            national: national.isEmpty ? Self.field(auto[team]?.national) : national)
                        }
                        corrections[key] = byTeam
                    }
                    ForEach(teamNames, id: \.self) { team in
                        HStack {
                            Text(team)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            TextField(Self.field(auto[team]?.total).isEmpty ? "0" : Self.field(auto[team]?.total),
                                      text: binding(day: key, team: team, national: false))
                                .keyboardType(.decimalPad)
                                .frame(width: 70)
                            TextField(Self.field(auto[team]?.national).isEmpty ? "0" : Self.field(auto[team]?.national),
                                      text: binding(day: key, team: team, national: true))
                                .keyboardType(.decimalPad)
                                .frame(width: 70)
                        }
                    }
                }
            }
        } header: {
            Text("日ごとの得点の修正(団体・全国)")
        } footer: {
            Text("空欄はこの端末の記録から計算した値(灰色)を使います。ペナルティなどで公式と違うときだけ入力してください。「この日の自動値で確定」で、全員の端末を同じ値にそろえられます。")
        }
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        guard let initial else { return }
        name = initial.name
        if let date = initial.startDate.flatMap(Competition.dateFormatter.date(from:)) { startDate = date }
        if let date = initial.endDate.flatMap(Competition.dateFormatter.date(from:)) { endDate = date }
        teams = initial.teams.map { TeamDraft(name: $0.name, gliderIds: $0.gliderIds) }
        corrections = initial.days.mapValues { byTeam in
            byTeam.mapValues { (total: Self.field($0.total), national: Self.field($0.national)) }
        }
    }

    private func save() async {
        guard let server else {
            message = "トラックサーバーが設定されていません"
            return
        }
        saving = true
        defer { saving = false }
        do {
            let saved = try await api.saveCompetition(server: server, editorKey: "", competition: draft)
            onSaved(saved)
            message = "保存しました。全員の団体順位に反映されます。"
        } catch {
            message = error.localizedDescription
        }
    }
}
