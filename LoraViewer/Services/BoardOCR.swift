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
            guard !text.isEmpty else { return nil }
            // The board's printed position labels ("1", "2", ...) are short and
            // purely numeric; drop them so they aren't mistaken for a name.
            if text.count <= 2, text.allSatisfy(\.isNumber) { return nil }

            let box = observation.boundingBox // normalized, origin at bottom-left
            return Item(text: text, x: box.midX, y: 1 - box.midY) // flip to top-left origin
        }

        let leftColumn = items.filter { $0.x < 0.5 }.sorted { $0.y < $1.y }
        let rightColumn = items.filter { $0.x >= 0.5 }.sorted { $0.y < $1.y }

        var result: [Int: String] = [:]
        for (offset, item) in leftColumn.prefix(8).enumerated() {
            result[offset + 1] = item.text
        }
        for (offset, item) in rightColumn.prefix(7).enumerated() {
            result[offset + 9] = item.text
        }
        return result
    }
}
