package mihon.domain.ocr.interactor

import mihon.domain.ocr.repository.OcrRepository

class GetOcrCacheSize(
    private val ocrRepository: OcrRepository,
) {
    suspend fun await(): Long {
        return ocrRepository.getCacheSizeBytes()
    }
}
