package com.cobaltloom.loraviewer.data.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * Reads names off a photo of the club's whiteboard roster and guesses which board position
 * (1...15) each one belongs to.
 *
 * The physical board this club uses lists positions 1-8 in a left column and 9-15 in a right
 * column, each read top to bottom, so that's the layout this heuristic assumes. Results are a
 * best-effort starting point - the caller is expected to let the user review and correct them
 * before saving.
 */
object BoardOcrAnalyzer {
    suspend fun recognizeNames(context: Context, imageUri: Uri): Map<Int, String> {
        val image = InputImage.fromFilePath(context, imageUri)
        val recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        val result = recognizer.process(image).await()

        data class Item(val text: String, val x: Float, val y: Float)

        val items = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            val text = correctCommonMisreads(line.text.trim())
            if (text.isEmpty() || !looksLikeName(text)) return@mapNotNull null
            Item(text, box.exactCenterX() / image.width, box.exactCenterY() / image.height)
        }

        val threshold = columnSplitThreshold(items.map { it.x })
        val leftColumn = items.filter { it.x < threshold }.sortedBy { it.y }
        val rightColumn = items.filter { it.x >= threshold }.sortedBy { it.y }

        val recognized = mutableMapOf<Int, String>()
        leftColumn.take(8).forEachIndexed { offset, item -> recognized[offset + 1] = item.text }
        rightColumn.take(7).forEachIndexed { offset, item -> recognized[offset + 9] = item.text }
        return recognized
    }

    /**
     * Fixes an OCR mistake seen consistently enough on this club's roster to correct blindly: a
     * handwritten "1" right after another digit keeps getting read as a slash (e.g. "青山21" ->
     * "青山2/"), so treat any "/" touching a digit as a misread "1".
     */
    private fun correctCommonMisreads(text: String): String {
        val chars = text.toCharArray()
        for (i in chars.indices) {
            if (chars[i] == '/') {
                val precededByDigit = i > 0 && chars[i - 1].isDigit()
                val followedByDigit = i < chars.size - 1 && chars[i + 1].isDigit()
                if (precededByDigit || followedByDigit) chars[i] = '1'
            }
        }
        return String(chars)
    }

    /**
     * The board's printed position badges (circled numbers) often get OCR'd as short,
     * punctuation-heavy fragments, which corrupts row ordering if treated as a name. A real name
     * always has either a Japanese character or a short English word in it.
     */
    private fun looksLikeName(text: String): Boolean {
        val hasJapanese = text.any { c -> c.code in 0x3040..0x30FF || c.code in 0x4E00..0x9FFF }
        val hasLatinWord = Regex("[A-Za-z]{2,}").containsMatchIn(text)
        return hasJapanese || hasLatinWord
    }

    /**
     * Splits x-positions into two columns at their widest gap, so a single filled-in column isn't
     * cut in half at a fixed midpoint. Falls back to "everything is one column" when there's no
     * clear gap.
     */
    private fun columnSplitThreshold(xs: List<Float>): Float {
        val sorted = xs.sorted()
        if (sorted.size <= 1) return 1.1f

        var widestGap = 0f
        var splitAt = sorted.size
        for (i in 1 until sorted.size) {
            val gap = sorted[i] - sorted[i - 1]
            if (gap > widestGap) {
                widestGap = gap
                splitAt = i
            }
        }

        if (widestGap <= 0.15f) return 1.1f // no clear two-column gap
        return (sorted[splitAt - 1] + sorted[splitAt]) / 2
    }
}
