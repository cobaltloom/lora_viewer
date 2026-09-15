import SwiftUI

struct GliderDetailCard: View {
    let glider: GliderPosition
    let baseName: String
    let alertReasons: [GliderAlertReason]

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
                FavoriteButton(imei: glider.imei)
                EditableGliderName(imei: glider.imei, baseName: baseName)
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
            Text(String(format: "%.5f, %.5f", glider.lat, glider.lon))
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
        .shadow(radius: 4)
    }
}
