package eu.kanade.tachiyomi.data.ocr

import tachiyomi.domain.chapter.interactor.GetChapter

internal class OcrQueueActions(
    private val ocrScanManager: OcrScanManager,
    private val getChapter: GetChapter,
) {

    suspend fun await(
        chapterId: Long,
        action: OcrQueueAction,
    ) {
        val chapterIds = ocrScanManager.queueState.value.entries.map(OcrScanQueueEntry::chapterId)
        if (chapterId !in chapterIds) {
            return
        }

        val seriesIds = resolveSeriesIds(chapterIds, chapterId)
        val seriesIdSet = seriesIds.toSet()

        when (action) {
            OcrQueueAction.MoveToTop -> {
                val reordered = listOf(chapterId) + chapterIds.filterNot { it == chapterId }
                ocrScanManager.reorderQueue(reordered)
            }
            OcrQueueAction.MoveSeriesToTop -> {
                val reordered = seriesIds + chapterIds.filterNot { it in seriesIdSet }
                ocrScanManager.reorderQueue(reordered)
            }
            OcrQueueAction.MoveToBottom -> {
                val reordered = chapterIds.filterNot { it == chapterId } + chapterId
                ocrScanManager.reorderQueue(reordered)
            }
            OcrQueueAction.MoveSeriesToBottom -> {
                val reordered = chapterIds.filterNot { it in seriesIdSet } + seriesIds
                ocrScanManager.reorderQueue(reordered)
            }
            OcrQueueAction.Cancel -> {
                ocrScanManager.cancelQueuedChapters(listOf(chapterId))
            }
            OcrQueueAction.CancelSeries -> {
                ocrScanManager.cancelQueuedChapters(seriesIds)
            }
        }
    }

    private suspend fun resolveSeriesIds(
        chapterIds: List<Long>,
        chapterId: Long,
    ): List<Long> {
        val targetMangaId = getChapter.await(chapterId)?.mangaId ?: return listOf(chapterId)

        val seriesIds = chapterIds.filter { id ->
            id == chapterId || getChapter.await(id)?.mangaId == targetMangaId
        }

        return seriesIds.ifEmpty { listOf(chapterId) }
    }
}

internal enum class OcrQueueAction {
    MoveToTop,
    MoveSeriesToTop,
    MoveToBottom,
    MoveSeriesToBottom,
    Cancel,
    CancelSeries,
}
