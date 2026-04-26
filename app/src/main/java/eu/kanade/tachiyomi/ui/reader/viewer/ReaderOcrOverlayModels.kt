package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.RectF
import mihon.domain.ocr.model.OcrBoundingBox
import mihon.domain.ocr.model.OcrTextOrientation
import mihon.domain.ocr.model.flattenOcrTextForQuery

data class ReaderOcrPageIdentity(
    val chapterId: Long,
    val pageIndex: Int,
)

data class ReaderOcrRegionSelection(
    val page: ReaderOcrPageIdentity,
    val regionOrder: Int,
    val displayText: String,
    val queryText: String,
    val boundingBox: OcrBoundingBox,
    val textOrientation: OcrTextOrientation,
    val anchorRectOnScreen: RectF?,
    val initialSelectionOffset: Int,
)

data class ReaderPageOcrRegionTap(
    val regionOrder: Int,
    val displayText: String,
    val queryText: String,
    val boundingBox: OcrBoundingBox,
    val textOrientation: OcrTextOrientation,
    val anchorRectOnScreen: RectF?,
    val initialSelectionOffset: Int,
)

data class ReaderActiveOcrOverlay(
    val page: ReaderOcrPageIdentity,
    val regionOrder: Int,
    val displayText: String,
    val queryText: String,
    val boundingBox: OcrBoundingBox,
    val textOrientation: OcrTextOrientation,
    val highlightRange: Pair<Int, Int>? = null,
)

sealed interface ReaderActiveOcrTapResult {
    data class SelectWord(val offset: Int) : ReaderActiveOcrTapResult

    data object BubbleTap : ReaderActiveOcrTapResult
}

internal fun searchTextForOffset(
    text: String,
    offset: Int,
): String {
    if (text.isBlank()) return text
    return text.substring(offset.coerceIn(0, text.lastIndex))
}

internal fun queryOffsetToDisplayOffset(
    displayText: String,
    queryOffset: Int,
): Int {
    if (displayText.isEmpty()) return 0

    var currentQueryOffset = 0
    var index = 0
    while (index < displayText.length) {
        if (currentQueryOffset >= queryOffset) {
            return index
        }

        val char = displayText[index]
        if (char.isWhitespace()) {
            while (index < displayText.length && displayText[index].isWhitespace()) {
                index++
            }
            currentQueryOffset++
            continue
        }

        currentQueryOffset++
        index++
    }

    return displayText.length
}

internal fun displayOffsetToQueryOffset(
    displayText: String,
    displayOffset: Int,
): Int {
    if (displayText.isEmpty()) return 0

    val clampedOffset = displayOffset.coerceIn(0, displayText.length)
    var queryOffset = 0
    var index = 0
    while (index < clampedOffset) {
        if (displayText[index].isWhitespace()) {
            while (index < clampedOffset && displayText[index].isWhitespace()) {
                index++
            }
            if (index > 0 && index < displayText.length) {
                queryOffset++
            }
            continue
        }

        queryOffset++
        index++
    }

    return queryOffset.coerceIn(0, flattenOcrTextForQuery(displayText).length)
}

internal fun queryRangeToDisplayRange(
    displayText: String,
    queryRange: Pair<Int, Int>?,
): Pair<Int, Int>? {
    if (queryRange == null || queryRange.first >= queryRange.second) return null
    val start = queryOffsetToDisplayOffset(displayText, queryRange.first)
    val end = queryOffsetToDisplayOffset(displayText, queryRange.second)
    if (start >= end) return null
    return start to end
}
