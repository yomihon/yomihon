package eu.kanade.tachiyomi.ui.download

import android.view.MenuItem
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.ocr.OcrQueueAction
import eu.kanade.tachiyomi.data.ocr.OcrQueueActions
import eu.kanade.tachiyomi.data.ocr.OcrScanManager
import eu.kanade.tachiyomi.data.ocr.OcrScanQueueEntry
import eu.kanade.tachiyomi.data.ocr.OcrScanQueueState
import eu.kanade.tachiyomi.databinding.DownloadListBinding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.manga.interactor.GetManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class OcrQueueScreenModel(
    private val ocrScanManager: OcrScanManager = Injekt.get(),
    private val getChapter: GetChapter = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
) : ScreenModel {

    private val ocrQueueActions: OcrQueueActions = Injekt.get()

    private val _state = MutableStateFlow(OcrQueueUiState())
    internal val state = _state.asStateFlow()

    private val ocrChapterMetadataCache = mutableMapOf<Long, OcrQueueChapterMetadata>()

    lateinit var controllerBinding: DownloadListBinding
    internal var adapter: OcrAdapter? = null

    internal val listener = object : OcrAdapter.OcrItemListener {
        override fun onItemReleased(position: Int) {
            val adapter = adapter ?: return
            val items = adapter.currentItems.filterIsInstance<OcrItem>().map { it.ocrQueueItem.chapterId }
            screenModelScope.launch {
                ocrScanManager.reorderQueue(items)
            }
        }

        override fun onMenuItemClick(position: Int, menuItem: MenuItem) {
            val item = adapter?.getItem(position) ?: return
            if (item is OcrItem) {
                when (menuItem.itemId) {
                    R.id.move_to_top -> handleOcrAction(item.ocrQueueItem.chapterId, OcrQueueAction.MoveToTop)
                    R.id.move_to_bottom -> handleOcrAction(item.ocrQueueItem.chapterId, OcrQueueAction.MoveToBottom)
                    R.id.move_to_top_series -> handleOcrAction(
                        item.ocrQueueItem.chapterId,
                        OcrQueueAction.MoveSeriesToTop,
                    )
                    R.id.move_to_bottom_series -> handleOcrAction(
                        item.ocrQueueItem.chapterId,
                        OcrQueueAction.MoveSeriesToBottom,
                    )
                    R.id.cancel_download -> handleOcrAction(item.ocrQueueItem.chapterId, OcrQueueAction.Cancel)
                    R.id.cancel_series -> handleOcrAction(item.ocrQueueItem.chapterId, OcrQueueAction.CancelSeries)
                }
            }
        }
    }

    init {
        screenModelScope.launch {
            ocrScanManager.queueState
                .mapLatest(::buildOcrQueueUiState)
                .collectLatest { state -> _state.value = state }
        }
    }

    val isQueueRunning = ocrScanManager.isScannerRunning
        .stateIn(screenModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun resumeQueue() {
        screenModelScope.launch {
            ocrScanManager.resume()
        }
    }

    fun pauseQueue() {
        screenModelScope.launch {
            ocrScanManager.pause()
        }
    }

    fun clearQueue() {
        screenModelScope.launch {
            ocrScanManager.clearQueue()
        }
    }

    private suspend fun buildOcrQueueUiState(queueState: OcrScanQueueState): OcrQueueUiState {
        if (!queueState.isActive) {
            return OcrQueueUiState(isPaused = queueState.isPaused)
        }

        val items = queueState.entries.map { entry ->
            val progress = queueState.activeProgress?.takeIf { it.chapterId == entry.chapterId }
            val chapterMetadata = resolveOcrQueueChapter(entry.chapterId)
            OcrItem(
                OcrQueueChapterItem(
                    chapterId = entry.chapterId,
                    mangaId = chapterMetadata.mangaId,
                    mangaTitle = progress?.mangaTitle ?: chapterMetadata.mangaTitle,
                    chapterName = progress?.chapterName ?: chapterMetadata.chapterName,
                    chapterNumber = chapterMetadata.chapterNumber,
                    processedPages = progress?.processedPages ?: 0,
                    totalPages = progress?.totalPages,
                    state = entry.state.toUiState(),
                    lastError = entry.lastError,
                ),
            )
        }

        return OcrQueueUiState(
            items = items,
            isPaused = queueState.isPaused,
        )
    }

    private suspend fun resolveOcrQueueChapter(chapterId: Long): OcrQueueChapterMetadata {
        return ocrChapterMetadataCache[chapterId] ?: run {
            val chapter = getChapter.await(chapterId)
            val manga = chapter?.let { getManga.await(it.mangaId) }
            val mangaTitle = manga?.title.orEmpty()
            val chapterName = chapter
                ?.name
                ?.takeIf(String::isNotBlank)
                ?: chapterId.toString()

            OcrQueueChapterMetadata(
                mangaId = chapter?.mangaId,
                mangaTitle = mangaTitle,
                chapterName = chapterName,
                chapterNumber = chapter?.chapterNumber ?: -1.0,
            ).also {
                ocrChapterMetadataCache[chapterId] = it
            }
        }
    }

    private fun handleOcrAction(
        chapterId: Long,
        action: OcrQueueAction,
    ) {
        screenModelScope.launch {
            ocrQueueActions.await(chapterId, action)
        }
    }

    override fun onDispose() {
        ocrChapterMetadataCache.clear()
        adapter = null
    }
}

internal data class OcrQueueUiState(
    val items: List<OcrItem> = emptyList(),
    val isPaused: Boolean = false,
) {
    val totalCount: Int
        get() = items.size
}

internal data class OcrQueueChapterItem(
    val chapterId: Long,
    val mangaId: Long?,
    val mangaTitle: String,
    val chapterName: String,
    val chapterNumber: Double,
    val processedPages: Int,
    val totalPages: Int?,
    val state: OcrQueueItemState,
    val lastError: String?,
)

internal enum class OcrQueueItemState {
    Queued,
    Scanning,
    Error,
}

private data class OcrQueueChapterMetadata(
    val mangaId: Long?,
    val mangaTitle: String,
    val chapterName: String,
    val chapterNumber: Double,
)

private fun OcrScanQueueEntry.State.toUiState(): OcrQueueItemState {
    return when (this) {
        OcrScanQueueEntry.State.QUEUED -> OcrQueueItemState.Queued
        OcrScanQueueEntry.State.SCANNING -> OcrQueueItemState.Scanning
        OcrScanQueueEntry.State.ERROR -> OcrQueueItemState.Error
    }
}
