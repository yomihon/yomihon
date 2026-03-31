package mihon.domain.ocr.interactor

import mihon.domain.ocr.repository.OcrRepository

class ClearOcrCache(
    private val ocrRepository: OcrRepository,
) {
    suspend fun await() {
        ocrRepository.clearCache()
    }
}
