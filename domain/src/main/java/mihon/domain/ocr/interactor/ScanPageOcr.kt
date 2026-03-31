package mihon.domain.ocr.interactor

import mihon.domain.ocr.model.OcrImage
import mihon.domain.ocr.model.OcrPageResult
import mihon.domain.ocr.repository.OcrRepository

class ScanPageOcr(
    private val ocrRepository: OcrRepository,
) {
    suspend fun await(
        chapterId: Long,
        pageIndex: Int,
        image: OcrImage,
    ): OcrPageResult {
        return ocrRepository.scanPage(chapterId, pageIndex, image)
    }
}
