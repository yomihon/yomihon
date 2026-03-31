package mihon.domain.ocr.interactor

import mihon.domain.ocr.model.OcrImage
import mihon.domain.ocr.repository.OcrRepository

class OcrProcessor(
    private val ocrRepository: OcrRepository,
) {
    suspend fun getText(image: OcrImage): String {
        return ocrRepository.recognizeText(image)
    }
}
