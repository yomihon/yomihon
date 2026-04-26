package mihon.domain.ocr.interactor

import mihon.domain.ocr.model.OcrPageResult
import mihon.domain.ocr.repository.OcrRepository

class GetCachedPageOcr(
    private val ocrRepository: OcrRepository,
) {
    suspend fun await(
        chapterId: Long,
        pageIndex: Int,
    ): OcrPageResult? {
        return ocrRepository.getCachedPage(chapterId, pageIndex)
    }
}
