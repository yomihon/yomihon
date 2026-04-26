package eu.kanade.tachiyomi.util.ocr

import android.graphics.Bitmap
import mihon.domain.ocr.model.OcrImage

internal fun Bitmap.toOcrImage(): OcrImage {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    return OcrImage(
        width = width,
        height = height,
        pixels = pixels,
    )
}
