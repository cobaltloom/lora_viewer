import SwiftUI

/// Provisional team standings over a competition set up in the app (shared through the track server):
/// every glider seen on the current source is a team, scored each competition day from the laps the
/// track server timed (so days when no app was open count too) - corrected where someone entered it -
/// plus today's, and for each team the lap time that would take it to P1, or one place up, today.
struct TeamStandingsView: View {
    let server: TrackServerConfig?
    @ObservedObject var viewModel: GliderTrackerViewModel
    /// The selected task course, used for today when no laps tell the day's course yet.
    let selectedCourse: CompetitionTaskCourse?
    @EnvironmentObject private var handicapStore: GliderHandicapStore

    @State private var competition: Competition?
    @State private var results: [String: DayResults] = [:]
    @State private var errorMessage: String?
    @State private var showEditor = false
    private let api = TrackServerAPI()

    private func handicap(_ id: String) -> Double {
        competition?.handicaps[id] ?? handicapStore.handicap(for: id)
    }

    /// Today counts only during the competition.
    private var includesToday: Bool {
        guard let last = competition?.dates(until: Date()).last else { return false }
        return Calendar.current.isDateInToday(last)
    }

    /// Each team's lap time to P1 and to one place up.
    private func targets(for row: TeamRanking.Row, standings: TeamStandings, flights: [TeamRanking.Flight],
                         distanceKm: Double?) -> [(rank: Int, seconds: Double?)] {
        guard let distanceKm, includesToday, row.rank > 1 else { return [] }
        let ranks = [1] + (row.rank - 1 > 1 ? [row.rank - 1] : [])
        return ranks.map { target in
            (target, TeamRanking.lapSecondsToReach(standings, flights: flights, teamName: row.name, gliderID: row.name,
                                                  handicap: handicap(row.name), distanceKm: distanceKm, targetRank: target))
        }
    }

    var body: some View {
        List {
            if let competition, competition.startDate != nil {
                let today = Date()
                let standings = CompetitionScoring.standingsBeforeToday(competition, today: today, results: results, handicap: handicap)
                let todayResults = results[CompetitionScoring.key(today)]
                let todayCourse = CompetitionScoring.dayCourse(competition, date: today, results: todayResults) ?? selectedCourse?.name
                let courseIsSet = competition.days[CompetitionScoring.key(today)]?.course != nil
                let flights = CompetitionScoring.flights(todayResults, course: todayCourse, handicap: handicap)
                let distanceKm = CompetitionTaskCourseData.courses.first { $0.name == todayCourse }?.distanceKm
                let rows = TeamRanking.rows(standings, flights: flights, includeToday: includesToday)
                Section {
                    if rows.isEmpty {
                        Text("期間中にまだ周回・旋回点の通過がありません。")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(rows) { row in
                        rowView(row, targets: targets(for: row, standings: standings, flights: flights, distanceKm: distanceKm))
                    }
                } header: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(competition.name.isEmpty ? "競技会" : competition.name)
                            .font(.headline)
                            .foregroundStyle(.primary)
                        let dayCount = competition.dates(until: today).count
                        Text("\(competition.startDate ?? "")〜\(competition.endDate ?? "")" +
                             (includesToday ? "(第\(dayCount)日・本日の暫定を含む)" : "(期間外: 本日分は含みません)"))
                        if competition.finalizedAt != nil {
                            Text("最終日の3日後に、全日の得点を自動で確定しました。")
                        }
                        if includesToday {
                            Text("本日のコース: " + (todayCourse ?? "まだ周回がありません") +
                                 (courseIsSet || todayCourse == nil ? "" : "(周回の多さから自動判定)"))
                        }
                    }
                    .textCase(nil)
                } footer: {
                    Text("期間中に\(viewModel.dataSource == .ogn ? "FLARM" : "LoRa")で飛んだ機体が自動でチームとして並びます。各日の得点は、トラックサーバーが計時した周回の参考得点のうち機体ごとの上位3本(全国選出点は最高点)で、端末を閉じていた日も含みます。ペナルティなどは「設定」で修正できます。公式成績とは異なることがあります。")
                }
            } else {
                ContentUnavailableView {
                    Label("競技会が設定されていません", systemImage: "trophy")
                } description: {
                    Text(errorMessage ?? "右上の「設定」から大会期間を設定してください。期間中に飛んだ機体が自動でチームとして並びます。")
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
                CompetitionEditorView(server: server, initial: competition, results: results, viewModel: viewModel) { saved in
                    competition = saved
                    Task { await refresh() }
                }
                .environmentObject(handicapStore)
            }
        }
        // Fetched on opening and every 30 s while open: the server re-times today's laps every minute.
        .task(id: viewModel.dataSource) {
            while !Task.isCancelled {
                await refresh()
                try? await Task.sleep(nanoseconds: 30_000_000_000)
            }
        }
    }

    private func refresh() async {
        guard let server else {
            errorMessage = "トラックサーバーが設定されていません"
            return
        }
        do {
            let fetched = try await api.fetchCompetition(server: server)
            let dates = fetched?.dates(until: Date()) ?? []
            var fetchedResults: [String: DayResults] = [:]
            if let first = dates.first, let last = dates.last {
                fetchedResults = try await api.fetchResults(server: server, source: viewModel.dataSource,
                                                            from: CompetitionScoring.key(first), to: CompetitionScoring.key(last))
            }
            competition = fetched
            results = fetchedResults
            errorMessage = nil
            if let fetched {
                await viewModel.resolveNames(CompetitionScoring.gliders(fetched, today: Date(), results: fetchedResults))
            }
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
                    Text(viewModel.standingsName(row.name)).font(.headline)
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

/// Where the competition is set up - name and period - and, per day, the course flown (when the
/// automatic pick is wrong) and any corrected glider scores (penalties, or where they differ from the
/// official results), plus shared handicaps; saving shares it with every app. The gliders themselves
/// come from the track server's results for the current source.
struct CompetitionEditorView: View {
    let server: TrackServerConfig?
    let initial: Competition?
    let results: [String: DayResults]
    @ObservedObject var viewModel: GliderTrackerViewModel
    let onSaved: (Competition) -> Void
    @EnvironmentObject private var handicapStore: GliderHandicapStore

    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var startDate = Calendar.current.startOfDay(for: Date())
    @State private var endDate = Calendar.current.date(byAdding: .day, value: 4, to: Calendar.current.startOfDay(for: Date())) ?? Date()
    /// Date key → course name, or "" for the automatic pick.
    @State private var courses: [String: String] = [:]
    /// Date key → glider → (団体, 全国選出点) as typed; blank = the server results' score.
    @State private var corrections: [String: [String: (total: String, national: String)]] = [:]
    /// Glider → shared handicap as typed; blank = each device's own.
    @State private var handicaps: [String: String] = [:]
    @State private var selectedDay: Date?
    @State private var message: String?
    @State private var saving = false
    @State private var loaded = false
    private let api = TrackServerAPI()

    private static func field(_ value: Double?) -> String {
        guard let value else { return "" }
        return value == value.rounded() ? String(Int(value)) : String(value)
    }

    private var draft: Competition {
        var days: [String: Competition.Day] = [:]
        for date in Set(courses.keys).union(corrections.keys) {
            var scores: [String: Competition.DayScore] = [:]
            for (glider, value) in corrections[date] ?? [:] {
                let score = Competition.DayScore(total: Double(value.total.trimmingCharacters(in: .whitespaces)),
                                                 national: Double(value.national.trimmingCharacters(in: .whitespaces)))
                if score.total != nil || score.national != nil { scores[glider] = score }
            }
            let course = courses[date].flatMap { $0.isEmpty ? nil : $0 }
            if course != nil || !scores.isEmpty { days[date] = Competition.Day(course: course, scores: scores) }
        }
        var shared: [String: Double] = [:]
        for (glider, value) in handicaps {
            if let number = Double(value.trimmingCharacters(in: .whitespaces)) { shared[glider] = number }
        }
        return Competition(name: name.trimmingCharacters(in: .whitespaces),
                           startDate: Competition.dateFormatter.string(from: startDate),
                           endDate: Competition.dateFormatter.string(from: endDate),
                           days: days, handicaps: shared, finalizedAt: initial?.finalizedAt)
    }

    private func sharedHandicap(_ id: String) -> Double {
        Double((handicaps[id] ?? "").trimmingCharacters(in: .whitespaces)) ?? handicapStore.handicap(for: id)
    }

    private func binding(_ day: String, _ glider: String, national: Bool) -> Binding<String> {
        Binding(
            get: {
                let value = corrections[day]?[glider]
                return (national ? value?.national : value?.total) ?? ""
            },
            set: { newValue in
                var byGlider = corrections[day] ?? [:]
                var value = byGlider[glider] ?? (total: "", national: "")
                if national { value.national = newValue } else { value.total = newValue }
                byGlider[glider] = value
                corrections[day] = byGlider
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
                Text("期間中に飛んだ機体が自動でチームとして並び、トラックサーバーが計時した周回から毎日の得点を積み上げます(端末を閉じていた日も含みます)。")
            }
            daySection
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

    @ViewBuilder private var daySection: some View {
        let competition = draft
        let days = competition.dates(until: Date())
        let gliders = CompetitionScoring.gliders(competition, today: Date(), results: results)
        Section {
            if days.isEmpty {
                Text("期間を設定すると、日ごとのコースと得点を確認・修正できます。")
            } else {
                Picker("日付", selection: Binding(get: { selectedDay ?? days.last }, set: { selectedDay = $0 })) {
                    ForEach(days.indices, id: \.self) { index in
                        Text("第\(index + 1)日 \(CompetitionScoring.key(days[index]))").tag(Optional(days[index]))
                    }
                }
                if let day = selectedDay ?? days.last {
                    let key = CompetitionScoring.key(day)
                    let (autoCourse, auto) = automaticValues(competition, day: day)
                    Picker("コース", selection: Binding(get: { courses[key] ?? "" }, set: { courses[key] = $0 })) {
                        Text("自動(\(autoCourse.map { String($0.prefix(1)) } ?? "周回なし"))").tag("")
                        ForEach(CompetitionTaskCourseData.courses) { course in
                            Text(course.name).tag(course.name)
                        }
                    }
                    Button("この日の自動値で確定") {
                        var byGlider = corrections[key] ?? [:]
                        for glider in gliders {
                            let total = byGlider[glider]?.total ?? ""
                            let national = byGlider[glider]?.national ?? ""
                            byGlider[glider] = (total: total.isEmpty ? Self.field(auto[glider]?.total) : total,
                                                national: national.isEmpty ? Self.field(auto[glider]?.national) : national)
                        }
                        corrections[key] = byGlider
                    }
                    ForEach(gliders, id: \.self) { glider in
                        HStack {
                            Text(viewModel.standingsName(glider))
                                .frame(maxWidth: .infinity, alignment: .leading)
                            TextField(Self.field(auto[glider]?.total).isEmpty ? "0" : Self.field(auto[glider]?.total),
                                      text: binding(key, glider, national: false))
                                .keyboardType(.decimalPad)
                                .frame(width: 56)
                            TextField(Self.field(auto[glider]?.national).isEmpty ? "0" : Self.field(auto[glider]?.national),
                                      text: binding(key, glider, national: true))
                                .keyboardType(.decimalPad)
                                .frame(width: 56)
                            TextField(String(format: "%.2f", handicapStore.handicap(for: glider)),
                                      text: Binding(get: { handicaps[glider] ?? "" }, set: { handicaps[glider] = $0 }))
                                .keyboardType(.decimalPad)
                                .frame(width: 48)
                        }
                    }
                }
            }
        } header: {
            Text("日ごとのコースと修正(団体・全国・ハンデ)")
        } footer: {
            Text("得点の空欄はトラックサーバーの計時から計算した値(灰色)を使います。ペナルティなどで公式と違うときだけ入力してください。ハンデの空欄は各端末の設定値です。")
        }
    }

    /// For `day`: the course the results point to (ignoring a chosen one), and the gliders' scores on
    /// the day's course without corrections.
    private func automaticValues(_ competition: Competition, day: Date) -> (course: String?, scores: [String: Competition.DayScore]) {
        let key = CompetitionScoring.key(day)
        var automatic = competition
        automatic.days[key]?.course = nil
        var uncorrected = competition
        uncorrected.days[key]?.scores = [:]
        return (CompetitionScoring.dayCourse(automatic, date: day, results: results[key]),
                CompetitionScoring.dayScores(uncorrected, date: day, results: results[key], handicap: sharedHandicap))
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        guard let initial else { return }
        name = initial.name
        if let date = initial.startDate.flatMap(Competition.dateFormatter.date(from:)) { startDate = date }
        if let date = initial.endDate.flatMap(Competition.dateFormatter.date(from:)) { endDate = date }
        courses = initial.days.compactMapValues { $0.course }
        corrections = initial.days.mapValues { day in
            day.scores.mapValues { (total: Self.field($0.total), national: Self.field($0.national)) }
        }
        handicaps = initial.handicaps.mapValues { String($0) }
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
