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
        request.customWords = knownVocabulary

        let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
        try handler.perform([request])

        struct Item {
            let text: String
            let x: CGFloat
            let y: CGFloat
        }

        let items: [Item] = (request.results ?? []).compactMap { observation in
            guard let candidate = observation.topCandidates(1).first else { return nil }
            let text = correctCommonMisreads(candidate.string.trimmingCharacters(in: .whitespaces))
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

    /// Words and abbreviations that keep showing up on this club's board —
    /// glider model/class names and the university abbreviations used as
    /// team names — fed to Vision as recognition hints (only used when
    /// `usesLanguageCorrection` is on) so handwriting isn't only matched
    /// against a general-purpose dictionary. Send over more vocabulary as
    /// it turns up and this list can grow.
    private static let knownVocabulary: [String] = [
        // Glider model / class names
        "Discus", "Discus Jr", "Discus LS", "Jr", "LS",
        // University team-name abbreviations seen so far
        "日大", "青山", "法政",
        // Serial-number suffixes seen so far
        "21", "23"
    ]

    /// Fixes OCR mistakes seen consistently enough on this club's roster to
    /// correct blindly. A handwritten "1" right after another digit keeps
    /// getting read as a slash (e.g. "青山21" -> "青山2/"), so treat any "/"
    /// touching a digit as a misread "1". Add more rules here as they turn
    /// up — this is meant to grow with real examples, not guess in general.
    private static func correctCommonMisreads(_ text: String) -> String {
        var chars = Array(text)
        for i in chars.indices where chars[i] == "/" {
            let precededByDigit = i > 0 && chars[i - 1].isNumber
            let followedByDigit = i < chars.count - 1 && chars[i + 1].isNumber
            if precededByDigit || followedByDigit {
                chars[i] = "1"
            }
        }
        return String(chars)
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
