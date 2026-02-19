package mihon.domain.ocr.interactor

import android.graphics.Bitmap
import mihon.domain.ocr.repository.OcrRepository

class OcrProcessor(
    private val ocrRepository: OcrRepository,
) {
    suspend fun getText(image: Bitmap): String {
        return ocrRepository.recognizeText(image)
    }
}
