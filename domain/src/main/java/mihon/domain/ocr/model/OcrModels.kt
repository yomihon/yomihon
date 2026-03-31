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

data class OcrRegion(
    val order: Int,
    val text: String,
    val boundingBox: OcrBoundingBox,
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
        get() = regions.joinToString(separator = " ") { it.text }.trim()
}
