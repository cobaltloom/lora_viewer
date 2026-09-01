import SwiftUI

struct GliderMarkerView: View {
    let glider: GliderPosition
    let isSelected: Bool

    var body: some View {
        VStack(spacing: 2) {
            ZStack {
                Circle()
                    .fill(colorForSource)
                    .frame(width: isSelected ? 34 : 28, height: isSelected ? 34 : 28)
                    .overlay(Circle().stroke(.white, lineWidth: 2))
                Text(glider.index)
                    .font(.caption.bold())
                    .foregroundStyle(.white)
            }
            if let alt = glider.alt {
                Text("\(Int(alt))m")
                    .font(.caption2.bold())
                    .padding(.horizontal, 4)
                    .padding(.vertical, 1)
                    .background(.black.opacity(0.65), in: Capsule())
                    .foregroundStyle(.white)
            }
        }
        .opacity(glider.isDisconnected ? 0.4 : 1.0)
        .shadow(radius: 2)
    }

    private var colorForSource: Color {
        if glider.isDisconnected { return .gray }
        switch glider.source {
        case .gps: return .blue
        case .cell: return .orange
        case .unknown: return .gray
        }
    }
}
