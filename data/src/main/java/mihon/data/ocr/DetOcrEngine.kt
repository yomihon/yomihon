package mihon.data.ocr

import android.graphics.Bitmap
import mihon.domain.ocr.exception.OcrException
import mihon.domain.ocr.model.OcrBoundingBox

internal interface DetOcrEngine {
    suspend fun detectTextRegions(image: Bitmap): List<OcrBoundingBox>

    fun close()
}

internal class UnavailableDetOcrEngine : DetOcrEngine {
    override suspend fun detectTextRegions(image: Bitmap): List<OcrBoundingBox> {
        throw OcrException.DetectionUnavailable()
    }

    override fun close() = Unit
}
