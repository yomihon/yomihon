package eu.kanade.tachiyomi.ui.reader.viewer

import mihon.domain.ocr.model.OcrBoundingBox
import mihon.domain.ocr.model.OcrPageResult
import mihon.domain.ocr.model.OcrRegion

fun OcrBoundingBox.contains(
    sourceX: Float,
    sourceY: Float,
    imageWidth: Int,
    imageHeight: Int,
): Boolean {
    if (imageWidth <= 0 || imageHeight <= 0) return false

    val left = left * imageWidth
    val top = top * imageHeight
    val right = right * imageWidth
    val bottom = bottom * imageHeight
    return sourceX in left..right && sourceY in top..bottom
}

fun OcrPageResult.findRegionAt(
    sourceX: Float,
    sourceY: Float,
): OcrRegion? {
    return regions.firstOrNull { region ->
        region.boundingBox.contains(
            sourceX = sourceX,
            sourceY = sourceY,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
        )
    }
}
