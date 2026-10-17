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

    /// "21:00", like a ski jump broadcast's to-beat line.
    static func clock(_ seconds: Double) -> String {
        let total = max(0, Int(seconds.rounded(.down)))
        return String(format: "%d:%02d", total / 60, total % 60)
    }
}

extension Color {
    /// The to-beat line's colour.
    static let toBeatAccent = Color(red: 0.78, green: 1.0, blue: 0.0)
    static let toBeatPanel = LinearGradient(
        colors: [Color(red: 0.04, green: 0.12, blue: 0.23), Color(red: 0.07, green: 0.21, blue: 0.36)],
        startPoint: .topLeading, endPoint: .bottomTrailing
    )
}

/// The "TO BEAT" tag, as on a ski jump broadcast.
struct ToBeatTag: View {
    var body: some View {
        Text("TO BEAT")
            .font(.caption2.weight(.heavy))
            .tracking(1.5)
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(Color.toBeatAccent, in: RoundedRectangle(cornerRadius: 4))
            .foregroundStyle(.black)
    }
}

/// The detail card's to-beat strip: the target lap time, the time left, and a bar of the lap so
/// far that turns orange in the last minute and red once the target has passed.
struct ToBeatBadge: View {
    let toBeat: ToBeat

    private var barColor: Color {
        if toBeat.remainingSeconds <= 0 { return .red }
        return toBeat.remainingSeconds < 60 ? .orange : .toBeatAccent
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                ToBeatTag()
                if toBeat.targetSeconds.isInfinite {
                    Text("周回で1位")
                        .font(.headline)
                } else {
                    Text(ToBeat.clock(toBeat.targetSeconds))
                        .font(.system(.title2, design: .rounded).weight(.bold).monospacedDigit())
                        .foregroundStyle(Color.toBeatAccent)
                    Spacer()
                    Text(toBeat.remainingSeconds > 0 ? "あと \(ToBeat.clock(toBeat.remainingSeconds))" : "超過")
                        .font(.subheadline.weight(.semibold).monospacedDigit())
                        .foregroundStyle(toBeat.remainingSeconds > 0 ? Color.white : Color.red)
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
