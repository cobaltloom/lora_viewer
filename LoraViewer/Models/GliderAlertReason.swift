/// One rule (custom alert or competition guideline) that a glider is
/// currently triggering, and how severe that particular rule considers it.
struct GliderAlertReason: Identifiable {
    let label: String
    let severity: AlertSeverity

    var id: String { label }
}

extension Array where Element == GliderAlertReason {
    /// The most severe reason present, or nil if there are none.
    var overallSeverity: AlertSeverity? {
        map(\.severity).max()
    }
}
