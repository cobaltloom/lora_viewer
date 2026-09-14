/// One rule (custom alert or competition guideline) that a glider is
/// currently triggering, and how severe that particular rule considers it.
struct GliderAlertReason: Identifiable {
    let label: String
    let severity: AlertSeverity
    /// Set only for proximity reasons, identifying the glider pair (order-
    /// independent) this reason came from — the same pair produces one
    /// reason on each glider, and this lets the banner collapse them into a
    /// single mention instead of reporting the pair from both directions.
    var pairKey: String? = nil

    var id: String { label }
}

extension Array where Element == GliderAlertReason {
    /// The most severe reason present, or nil if there are none.
    var overallSeverity: AlertSeverity? {
        map(\.severity).max()
    }
}
