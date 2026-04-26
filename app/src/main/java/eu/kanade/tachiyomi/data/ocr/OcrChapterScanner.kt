package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.ocr.toOcrImage
import eu.kanade.tachiyomi.util.system.activeNetworkState
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import mihon.domain.ocr.interactor.ClearCachedChapterOcr
import mihon.domain.ocr.interactor.ScanPageOcr
import mihon.domain.ocr.interactor.WithOcrScanSession
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.GetManga

internal class OcrChapterScanner(
    private val context: Context,
    private val getChapter: GetChapter,
    private val getManga: GetManga,
    private val clearCachedChapterOcr: ClearCachedChapterOcr,
    private val withOcrScanSession: WithOcrScanSession,
    private val scanPageOcr: ScanPageOcr,
    private val pageSourceResolver: OcrPageSourceResolver,
    private val downloadPreferences: DownloadPreferences,
) {
    suspend fun scanChapter(
        chapterId: Long,
        onProgress: (OcrChapterScanProgress) -> Unit,
        onComplete: (OcrChapterScanProgress) -> Unit,
        onError: (OcrChapterScanError) -> Unit,
        onCacheStateChanged: (chapterId: Long, hasResults: Boolean) -> Unit = { _, _ -> },
    ): Boolean {
        val chapter = getChapter.await(chapterId)
        if (chapter == null) {
            onError(
                OcrChapterScanError(
                    mangaId = null,
                    mangaTitle = null,
                    chapterId = chapterId,
                    chapterName = chapterId.toString(),
                    failure = OcrScanFailure.ChapterNotFound,
                ),
            )
            return false
        }

        val manga = getManga.await(chapter.mangaId)
        if (manga == null) {
            onError(
                OcrChapterScanError(
                    mangaId = chapter.mangaId,
                    mangaTitle = null,
                    chapterId = chapterId,
                    chapterName = chapter.name,
                    failure = OcrScanFailure.MangaNotFound,
                ),
            )
            return false
        }

        return try {
            withOcrScanSession.await {
                clearCachedChapterOcr.await(chapterId)
                onCacheStateChanged(chapterId, false)

                val resolvedPages = pageSourceResolver.resolve(manga, chapter)
                resolvedPages.use pageScope@{ pages ->
                    if (pages.pages.isEmpty()) {
                        onError(
                            OcrChapterScanError(
                                mangaId = manga.id,
                                mangaTitle = manga.title,
                                chapterId = chapterId,
                                chapterName = chapter.name,
                                failure = OcrScanFailure.NoPages,
                            ),
                        )
                        false
                    } else {
                        val totalPages = pages.pages.size
                        var lastProgress = OcrChapterScanProgress(
                            mangaId = manga.id,
                            mangaTitle = manga.title,
                            chapterId = chapterId,
                            chapterName = chapter.name,
                            processedPages = 0,
                            totalPages = totalPages,
                        )

                        onProgress(lastProgress)

                        try {
                            var chapterHasCachedResults = false
                            for ((index, page) in pages.pages.withIndex()) {
                                val networkError = checkNetworkState()
                                if (networkError != null) {
                                    clearCachedChapterOcr.await(chapterId)
                                    onCacheStateChanged(chapterId, false)
                                    onError(
                                        OcrChapterScanError(
                                            mangaId = manga.id,
                                            mangaTitle = manga.title,
                                            chapterId = chapterId,
                                            chapterName = chapter.name,
                                            failure = OcrScanFailure.Unexpected(networkError),
                                        ),
                                    )
                                    return@pageScope false
                                }

                                val bitmap = page.openBitmap() ?: error("Unable to decode page ${page.pageIndex + 1}")
                                try {
                                    scanPageOcr.await(chapterId, page.pageIndex, bitmap.toOcrImage())
                                } finally {
                                    if (!bitmap.isRecycled) {
                                        bitmap.recycle()
                                    }
                                }

                                if (!chapterHasCachedResults) {
                                    chapterHasCachedResults = true
                                    onCacheStateChanged(chapterId, true)
                                }

                                lastProgress = lastProgress.copy(processedPages = index + 1)
                                onProgress(lastProgress)
                            }

                            onComplete(lastProgress)
                            true
                        } catch (e: Throwable) {
                            handleUnexpectedFailure(
                                chapterId = chapterId,
                                chapterName = chapter.name,
                                mangaId = manga.id,
                                mangaTitle = manga.title,
                                throwable = e,
                                logMessage = "Failed to scan OCR",
                                onError = onError,
                                onCacheStateChanged = onCacheStateChanged,
                            )
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            handleUnexpectedFailure(
                chapterId = chapterId,
                chapterName = chapter.name,
                mangaId = manga.id,
                mangaTitle = manga.title,
                throwable = e,
                logMessage = "Failed to start OCR scan",
                onError = onError,
                onCacheStateChanged = onCacheStateChanged,
            )
        }
    }

    private suspend fun handleUnexpectedFailure(
        chapterId: Long,
        chapterName: String,
        mangaId: Long,
        mangaTitle: String,
        throwable: Throwable,
        logMessage: String,
        onError: (OcrChapterScanError) -> Unit,
        onCacheStateChanged: (chapterId: Long, hasResults: Boolean) -> Unit,
    ): Boolean {
        if (throwable is CancellationException) {
            throw throwable
        }

        logcat(LogPriority.ERROR, throwable) { "$logMessage for chapterId=$chapterId" }
        clearCachedChapterOcr.await(chapterId)
        onCacheStateChanged(chapterId, false)
        onError(
            OcrChapterScanError(
                mangaId = mangaId,
                mangaTitle = mangaTitle,
                chapterId = chapterId,
                chapterName = chapterName,
                failure = OcrScanFailure.Unexpected(throwable.message),
            ),
        )
        return false
    }

    private fun checkNetworkState(): String? {
        val state = context.activeNetworkState()
        return if (state.isOnline) {
            val requireWifi = downloadPreferences.downloadOnlyOverWifi().get()
            if (requireWifi && !state.isWifi) {
                context.getString(R.string.download_notifier_text_only_wifi)
            } else {
                null
            }
        } else {
            context.getString(R.string.download_notifier_no_network)
        }
    }
}

internal data class OcrChapterScanProgress(
    val mangaId: Long,
    val mangaTitle: String,
    val chapterId: Long,
    val chapterName: String,
    val processedPages: Int,
    val totalPages: Int,
)

internal data class OcrChapterScanError(
    val mangaId: Long?,
    val mangaTitle: String?,
    val chapterId: Long,
    val chapterName: String,
    val failure: OcrScanFailure,
)

internal sealed interface OcrScanFailure {
    data object ChapterNotFound : OcrScanFailure

    data object MangaNotFound : OcrScanFailure

    data object NoPages : OcrScanFailure

    data class Unexpected(val message: String?) : OcrScanFailure
}
