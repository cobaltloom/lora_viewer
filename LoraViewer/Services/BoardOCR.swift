import Vision
import UIKit

/// Extracts name-like text from a photo of the club's whiteboard roster.
///
/// Earlier versions of this tried to also guess which board position (1-15)
/// each name belonged to from its position in the photo, but a tilted photo
/// throws off simple top-to-bottom ordering enough to make that unreliable.
/// So this just returns the recognized candidates and leaves matching them
/// to a position up to the person reviewing the results.
enum BoardOCR {
    static func recognizeNames(in image: UIImage) throws -> [String] {
        guard let cgImage = image.cgImage else { return [] }

        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = true
        request.recognitionLanguages = ["ja-JP", "en-US"]

        let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
        try handler.perform([request])

        return (request.results ?? []).compactMap { observation in
            guard let candidate = observation.topCandidates(1).first else { return nil }
            let text = candidate.string.trimmingCharacters(in: .whitespaces)
            guard !text.isEmpty, looksLikeName(text) else { return nil }
            return text
        }
    }

    /// The board's printed position badges (circled numbers) often get OCR'd
    /// as short, punctuation-heavy fragments sitting right next to the
    /// handwritten name, which would otherwise show up as a bogus candidate.
    /// A real name always has either a Japanese character or a short English
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
}
