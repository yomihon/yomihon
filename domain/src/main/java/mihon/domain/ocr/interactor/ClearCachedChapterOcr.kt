package mihon.domain.ocr.interactor

import mihon.domain.ocr.repository.OcrRepository

class ClearCachedChapterOcr(
    private val ocrRepository: OcrRepository,
) {
    suspend fun await(chapterId: Long) {
        ocrRepository.clearCachedChapter(chapterId)
    }
}
