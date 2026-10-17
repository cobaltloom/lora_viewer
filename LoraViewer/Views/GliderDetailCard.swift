import SwiftUI

struct GliderDetailCard: View {
    let glider: GliderPosition
    let baseName: String
    let alertReasons: [GliderAlertReason]
    /// One line of task lap progress, when a task course is selected and the glider is being timed.
    var lapSummary: String? = nil
    /// "To beat" for a lap in progress: what it needs to top today's best score.
    var toBeat: ToBeat? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let severity = alertReasons.overallSeverity {
                let labels = alertReasons.map { "\($0.severity.label): \($0.label)" }.joined(separator: "・")
                Label("高度アラート(\(labels))", systemImage: "exclamationmark.triangle.fill")
                    .font(.caption.bold())
                    .foregroundStyle(.white)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(severity == .warning ? .red : .orange, in: RoundedRectangle(cornerRadius: 8))
            }
            HStack {
                if !glider.isAnonymous {
                    FavoriteButton(imei: glider.imei)
                }
                EditableGliderName(imei: glider.imei, baseName: baseName, isEditable: !glider.isAnonymous)
                    .font(.headline)
                Spacer()
                Text(glider.source.label)
                    .font(.caption)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 2)
                    .background(.thinMaterial, in: Capsule())
            }
            HStack(spacing: 16) {
                Label {
                    if let alt = glider.alt {
                        Text("\(Int(alt)) m")
                    } else {
                        Text("---")
                    }
                } icon: {
                    Image(systemName: "arrow.up.to.line")
                }
                if let date = glider.positionDateTimeUTC {
                    Label {
                        Text(date, style: .time)
                    } icon: {
                        Image(systemName: "clock")
                    }
                }
                if glider.isDisconnected {
                    Label("切断", systemImage: "wifi.slash")
                        .foregroundStyle(.red)
                }
            }
            .font(.subheadline)
            if let lapSummary {
                Text(lapSummary)
                    .font(.subheadline)
            }
            if let toBeat {
                ToBeatBadge(toBeat: toBeat)
            }
            Text(String(format: "%.5f, %.5f", glider.lat, glider.lon))
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
        .shadow(radius: 4)
    }
}

/// A lap's "to beat" target - the lap time that would top today's best reference score (see
/// `LapScore.lapSecondsToBeat`) - and how far into the lap the glider is.
struct ToBeat {
    /// `.infinity` when any completed lap would top the day.
    let targetSeconds: Double
    let elapsedSeconds: Double

    var remainingSeconds: Double { targetSeconds - elapsedSeconds }

    /// "21:00", as on motorsport timing screens.
    static func clock(_ seconds: Double) -> String {
        let total = max(0, Int(seconds.rounded(.down)))
        return String(format: "%d:%02d", total / 60, total % 60)
    }

    /// F1-style delta to the target: "Δ −11:02" with time in hand, "Δ +0:45" once over.
    var delta: String {
        remainingSeconds > 0 ? "Δ −\(Self.clock(remainingSeconds))" : "Δ +\(Self.clock(-remainingSeconds))"
    }
}

extension Color {
    /// The timing tags' and target times' colour.
    static let toBeatAccent = Color(red: 0.78, green: 1.0, blue: 0.0)
    /// F1 timing's "purple": on pace for the fastest of all.
    static let timingPurple = Color(red: 0.69, green: 0.32, blue: 0.95)
    /// F1 timing's yellow.
    static let timingYellow = Color(red: 1.0, green: 0.84, blue: 0.0)
    static let toBeatPanel = LinearGradient(
        colors: [Color(red: 0.04, green: 0.12, blue: 0.23), Color(red: 0.07, green: 0.21, blue: 0.36)],
        startPoint: .topLeading, endPoint: .bottomTrailing
    )
}

/// A timing-screen tag ("TIME TO BEAT", "TARGET"), as in motorsport broadcasts.
struct ToBeatTag: View {
    var text = "TIME TO BEAT"

    var body: some View {
        Text(text)
            .font(.caption2.weight(.heavy))
            .tracking(1.5)
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(Color.toBeatAccent, in: RoundedRectangle(cornerRadius: 4))
            .foregroundStyle(.black)
    }
}

/// The detail card's target strip, F1 style: the lap time that would take P1, the delta to it, and
/// a bar of the lap so far - purple while on pace for P1, yellow in the last minute, red once over.
struct ToBeatBadge: View {
    let toBeat: ToBeat

    private var barColor: Color {
        if toBeat.remainingSeconds <= 0 { return .red }
        return toBeat.remainingSeconds < 60 ? .timingYellow : .timingPurple
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                ToBeatTag(text: "TARGET")
                if toBeat.targetSeconds.isInfinite {
                    Text("周回でP1")
                        .font(.headline)
                } else {
                    Text(ToBeat.clock(toBeat.targetSeconds))
                        .font(.system(.title2, design: .rounded).weight(.bold).monospacedDigit())
                        .foregroundStyle(Color.toBeatAccent)
                    Spacer()
                    Text(toBeat.delta)
                        .font(.subheadline.weight(.semibold).monospacedDigit())
                        .foregroundStyle(barColor == .timingPurple ? Color.white : barColor)
                }
            }
            if !toBeat.targetSeconds.isInfinite {
                GeometryReader { geometry in
                    ZStack(alignment: .leading) {
                        Capsule().fill(.white.opacity(0.2))
                        Capsule().fill(barColor)
                            .frame(width: geometry.size.width * min(1, max(0, toBeat.elapsedSeconds / toBeat.targetSeconds)))
                    }
                }
                .frame(height: 6)
            }
        }
        .padding(10)
        .background(Color.toBeatPanel, in: RoundedRectangle(cornerRadius: 10))
        .foregroundStyle(.white)
    }
}
