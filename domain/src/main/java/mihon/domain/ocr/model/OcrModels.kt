package mihon.domain.ocr.model

data class OcrImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    init {
        require(width > 0 && height > 0) { "OCR image dimensions must be positive" }
        require(pixels.size == width * height) {
            "OCR image pixels size must match width * height"
        }
    }
}

data class OcrBoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float
        get() = right - left

    val height: Float
        get() = bottom - top

    fun isValid(): Boolean {
        return left < right && top < bottom
    }
}

enum class OcrTextOrientation {
    Horizontal,
    Vertical,
}

data class OcrRegion(
    val order: Int,
    val text: String,
    val boundingBox: OcrBoundingBox,
    val textOrientation: OcrTextOrientation,
)

data class OcrPageResult(
    val chapterId: Long,
    val pageIndex: Int,
    val ocrModel: OcrModel,
    val imageWidth: Int,
    val imageHeight: Int,
    val regions: List<OcrRegion>,
) {
    val text: String
        get() = regions.joinToString(separator = " ") { flattenOcrTextForQuery(it.text) }.trim()
}

fun flattenOcrTextForQuery(text: String): String {
    if (text.isBlank()) return text.trim()
    return text
        .replace(Regex("\\s+"), " ")
        .trim()
}

fun normalizeOcrTextForDisplay(text: String): String {
    if (text.isEmpty()) return text

    return text
        .replace(Regex("[ ]*[.．・･…]{2,}")) { match ->
            dotRunToEllipses(match.value.trimStart(' '))
        }
        .replace(Regex("[!！]{2,}"), "‼")
        .replace(Regex("[?？]{2,}"), "⁇")
        .replace(Regex("[!！][?？]+"), "⁉")
        .replace(Regex("[?？][!！]+"), "⁈")
}

private fun dotRunToEllipses(dotRun: String): String {
    val dotCount = dotRun.sumOf {
        when (it) {
            '…' -> 3
            '.', '．', '・', '･' -> 1
            else -> 0
        }
    }
    val ellipsisCount = dotCount / 3
    val remainder = dotCount % 3

    return buildString(ellipsisCount + if (remainder >= 2) 1 else remainder) {
        repeat(ellipsisCount) { append('…') }
        when (remainder) {
            1 -> append('.')
            2 -> append('…')
        }
    }
}
