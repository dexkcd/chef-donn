package ph.chefdonn.receipts

import kotlin.math.abs

/** One recognized text line and its box in image pixels. */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerY: Int get() = (top + bottom) / 2
    val height: Int get() = bottom - top
}

/**
 * OCR engines return a receipt's label column and amount column as separate blocks,
 * so "TOTAL" and "1,678.75" arrive far apart. This rebuilds the printed rows by
 * vertical position, giving [ReceiptTextParser] "TOTAL  1,678.75" back.
 */
object OcrLayout {

    fun rows(lines: List<OcrLine>): List<String> {
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (line in lines.filter { it.text.isNotBlank() }.sortedBy { it.centerY }) {
            val anchor = rows.lastOrNull()?.first()
            // Same row when the centers are within half a line height of each other.
            if (anchor != null && abs(line.centerY - anchor.centerY) * 2 <= maxOf(anchor.height, line.height)) {
                rows.last() += line
            } else {
                rows += mutableListOf(line)
            }
        }
        return rows.map { row -> row.sortedBy { it.left }.joinToString("  ") { it.text.trim() } }
    }
}
