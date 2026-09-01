import Vision
import UIKit

/// Reads names off a photo of the club's whiteboard roster and guesses which
/// board position (1...15) each one belongs to.
///
/// The physical board this club uses lists positions 1-8 in a left column and
/// 9-15 in a right column, each read top to bottom, so that's the layout this
/// heuristic assumes. Results are a best-effort starting point — the caller
/// is expected to let the user review and correct them before saving.
enum BoardOCR {
    static func recognizeNames(in image: UIImage) throws -> [Int: String] {
        guard let cgImage = image.cgImage else { return [:] }

        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = true
        request.recognitionLanguages = ["ja-JP", "en-US"]

        let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
        try handler.perform([request])

        struct Item {
            let text: String
            let x: CGFloat
            let y: CGFloat
        }

        let items: [Item] = (request.results ?? []).compactMap { observation in
            guard let candidate = observation.topCandidates(1).first else { return nil }
            let text = candidate.string.trimmingCharacters(in: .whitespaces)
            guard !text.isEmpty, looksLikeName(text) else { return nil }

            let box = observation.boundingBox // normalized, origin at bottom-left
            return Item(text: text, x: box.midX, y: 1 - box.midY) // flip to top-left origin
        }

        let threshold = columnSplitThreshold(items.map(\.x))
        let leftColumn = items.filter { $0.x < threshold }.sorted { $0.y < $1.y }
        let rightColumn = items.filter { $0.x >= threshold }.sorted { $0.y < $1.y }

        var result: [Int: String] = [:]
        for (offset, item) in leftColumn.prefix(8).enumerated() {
            result[offset + 1] = item.text
        }
        for (offset, item) in rightColumn.prefix(7).enumerated() {
            result[offset + 9] = item.text
        }
        return result
    }

    /// The board's printed position badges (circled numbers) often get OCR'd
    /// as short, punctuation-heavy fragments sitting right next to the
    /// handwritten name, which corrupts row ordering if treated as a name. A
    /// real name always has either a Japanese character or a short English
    /// word in it, so require one of those instead of trying to blocklist
    /// every possible badge misread.
    private static func looksLikeName(_ text: String) -> Bool {
        let hasJapanese = text.unicodeScalars.contains { scalar in
            (0x3040...0x30FF).contains(scalar.value) || // hiragana / katakana
            (0x4E00...0x9FFF).contains(scalar.value)    // CJK unified ideographs
        }
        let hasLatinWord = text.range(of: "[A-Za-z]{2,}", options: .regularExpression) != nil
        return hasJapanese || hasLatinWord
    }

    /// Splits x-positions into two columns at their widest gap, so a single
    /// filled-in column (this photo's case) isn't cut in half at a fixed
    /// midpoint. Falls back to "everything is one column" when there's no
    /// clear gap.
    private static func columnSplitThreshold(_ xs: [CGFloat]) -> CGFloat {
        let sorted = xs.sorted()
        guard sorted.count > 1 else { return 1.1 }

        var widestGap: CGFloat = 0
        var splitAt = sorted.count
        for i in 1..<sorted.count {
            let gap = sorted[i] - sorted[i - 1]
            if gap > widestGap {
                widestGap = gap
                splitAt = i
            }
        }

        guard widestGap > 0.15 else { return 1.1 } // no clear two-column gap
        return (sorted[splitAt - 1] + sorted[splitAt]) / 2
    }
}
