import SwiftUI

struct GliderMarkerView: View {
    let glider: GliderPosition
    let isSelected: Bool
    let isFavorite: Bool
    let alertSeverity: AlertSeverity?
    /// Whether the custom low-altitude rule is on, this glider is currently
    /// flying, and it isn't triggering any alert right now — shown as a
    /// green ring so a comfortable margin is visible before it ever gets
    /// close to the alert thresholds, not just once it crosses them.
    let isReturnGlideSafe: Bool
    /// Task lap progress ("旋回 1/2" / "ゴール 29:25"), shown beside the altitude when timing.
    var lapBadge: String? = nil

    var body: some View {
        VStack(spacing: 2) {
            ZStack {
                Circle()
                    .fill(colorForSource)
                    .frame(width: isSelected ? 34 : 28, height: isSelected ? 34 : 28)
                    .overlay(Circle().stroke(ringColor, lineWidth: alertSeverity != nil || isFavorite ? 3 : 2))
                Text(glider.index)
                    .font(.caption.bold())
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .padding(.horizontal, 2)
            }
            .overlay(alignment: .topTrailing) {
                if isFavorite {
                    Image(systemName: "star.fill")
                        .font(.system(size: 10))
                        .foregroundStyle(.yellow)
                        .padding(2)
                        .background(Circle().fill(.black.opacity(0.7)))
                        .offset(x: 4, y: -4)
                }
            }
            .overlay(alignment: .bottomTrailing) {
                if alertSeverity != nil {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 10))
                        .foregroundStyle(ringColor)
                        .padding(2)
                        .background(Circle().fill(.white))
                        .offset(x: 4, y: 4)
                }
            }
            // Side by side rather than stacked, so the marker's height doesn't change when timing starts.
            HStack(spacing: 2) {
                if let alt = glider.alt {
                    Text("\(Int(alt))m")
                        .font(.caption2.bold())
                        .padding(.horizontal, 4)
                        .padding(.vertical, 1)
                        .background(altitudeBadgeColor.opacity(0.75), in: Capsule())
                        .foregroundStyle(.white)
                }
                if let lapBadge {
                    Text(lapBadge)
                        .font(.caption2.bold())
                        .padding(.horizontal, 4)
                        .padding(.vertical, 1)
                        .background((lapBadge.hasPrefix("ゴール") ? Color.green : Color.indigo).opacity(0.85), in: Capsule())
                        .foregroundStyle(.white)
                }
            }
        }
        .opacity(glider.isDisconnected ? 0.4 : 1.0)
        .shadow(radius: 2)
    }

    private var ringColor: Color {
        switch alertSeverity {
        case .warning: return .red
        case .caution: return .orange
        case nil:
            if isFavorite { return .yellow }
            return isReturnGlideSafe ? .green : .white
        }
    }

    private var altitudeBadgeColor: Color {
        switch alertSeverity {
        case .warning: return .red
        case .caution: return .orange
        case nil: return .black
        }
    }

    private var colorForSource: Color {
        if glider.isDisconnected { return .gray }
        switch glider.source {
        case .gps: return .blue
        case .cell: return .orange
        case .ogn: return .teal
        case .unknown: return .gray
        }
    }
}
