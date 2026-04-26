package mihon.domain.ocr.interactor

import mihon.domain.ocr.repository.OcrRepository

class WithOcrScanSession(
    private val ocrRepository: OcrRepository,
) {
    suspend fun <T> await(block: suspend () -> T): T {
        return ocrRepository.withScanSession(block)
    }
}
