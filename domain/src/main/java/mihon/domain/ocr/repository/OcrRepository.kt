package mihon.domain.ocr.repository

import mihon.domain.ocr.model.OcrImage
import mihon.domain.ocr.model.OcrPageResult

interface OcrRepository {
    suspend fun recognizeText(image: OcrImage): String

    suspend fun scanPage(
        chapterId: Long,
        pageIndex: Int,
        image: OcrImage,
    ): OcrPageResult

    suspend fun getCachedPage(
        chapterId: Long,
        pageIndex: Int,
    ): OcrPageResult?

    suspend fun getCachedChapterIds(chapterIds: Collection<Long>): Set<Long>

    suspend fun clearCachedChapter(chapterId: Long)

    suspend fun clearCache()

    suspend fun getCacheSizeBytes(): Long

    suspend fun <T> withScanSession(block: suspend () -> T): T

    /**
     * Cleanup and release all OCR resources, which can take up lots of RAM.
     * Used for memory management when system is under pressure.
     */
    fun cleanup()
}
