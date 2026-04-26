package mihon.domain.ocr.interactor

import mihon.domain.ocr.repository.OcrRepository

class GetCachedChapterIdsOcr(
    private val ocrRepository: OcrRepository,
) {
    suspend fun await(chapterIds: Collection<Long>): Set<Long> {
        return ocrRepository.getCachedChapterIds(chapterIds)
    }
}
