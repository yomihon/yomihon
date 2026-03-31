package eu.kanade.tachiyomi.data.ocr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

class OcrScanManager internal constructor(
    private val store: OcrScanStore,
    private val scanner: OcrChapterScanner,
    private val notifier: OcrScanNotifier,
    private val workerController: OcrScanWorkerController,
) {
    private val mutex = Mutex()
    private val mutableQueueState = MutableStateFlow(store.snapshot().toQueueState())
    private val cacheEventsFlow = MutableSharedFlow<OcrChapterScanCacheEvent>(extraBufferCapacity = 32)

    internal val queueState = mutableQueueState.asStateFlow()
    internal val cacheEvents = cacheEventsFlow.asSharedFlow()
    internal val isScannerRunning: Flow<Boolean>
        get() = workerController.isRunningFlow()

    val status: Flow<OcrQueueStatus>
        get() = queueState
            .map { state ->
                OcrQueueStatus(
                    pending = state.entries.size,
                    isPaused = state.isPaused,
                )
            }
            .distinctUntilChanged()

    fun startIfPending() {
        if (!queueState.value.isPaused && queueState.value.hasQueuedEntries) {
            startWorkerIfNeeded()
        }
    }

    suspend fun enqueue(chapterIds: Collection<Long>) {
        val changed = updateQueueState { state ->
            val existingIds = state.entries.map(OcrScanQueueEntry::chapterId).toSet()
            val newEntries = chapterIds
                .filter { it !in existingIds }
                .distinct()
                .map { chapterId ->
                    OcrScanQueueEntry(
                        chapterId = chapterId,
                        state = OcrScanQueueEntry.State.QUEUED,
                    )
                }

            if (newEntries.isEmpty()) {
                state
            } else {
                state.copy(entries = state.entries + newEntries)
            }
        }

        if (changed && !queueState.value.isPaused) {
            startWorkerIfNeeded()
        }
    }

    suspend fun pause() {
        updateQueueState { state ->
            state.copy(
                entries = state.entries.requeueActiveEntryToFront(),
                activeProgress = null,
                isPaused = true,
            )
        }

        notifier.dismissProgress()
        workerController.stop()
    }

    suspend fun resume() {
        updateQueueState { state ->
            val resumedEntries = state.entries.map { entry ->
                if (entry.state == OcrScanQueueEntry.State.ERROR) {
                    entry.copy(
                        state = OcrScanQueueEntry.State.QUEUED,
                        lastError = null,
                    )
                } else {
                    entry
                }
            }

            state.copy(
                entries = resumedEntries,
                activeProgress = null,
                isPaused = false,
            )
        }

        if (queueState.value.hasQueuedEntries) {
            startWorkerIfNeeded()
        }
    }

    suspend fun clearQueue() {
        updateQueueState {
            OcrScanQueueState(
                entries = emptyList(),
                activeProgress = null,
                isPaused = false,
            )
        }

        notifier.dismissProgress()
        workerController.stop()
    }

    suspend fun reorderQueue(chapterIds: List<Long>) {
        updateQueueState { state ->
            val activeEntry = state.activeEntry
            val reorderableEntries = state.entries.filterNot { entry ->
                entry.state == OcrScanQueueEntry.State.SCANNING
            }
            val reorderableIds = reorderableEntries.map(OcrScanQueueEntry::chapterId).toSet()
            val requestedIds = chapterIds
                .filter { it in reorderableIds }
                .distinct()

            val requestedIdSet = requestedIds.toSet()
            val reorderableByChapterId = reorderableEntries.associateBy(OcrScanQueueEntry::chapterId)
            val reorderedEntries = buildList {
                requestedIds.forEach { chapterId ->
                    reorderableByChapterId[chapterId]?.let(::add)
                }
                reorderableEntries.forEach { entry ->
                    if (entry.chapterId !in requestedIdSet) {
                        add(entry)
                    }
                }
            }

            state.copy(
                entries = listOfNotNull(activeEntry) + reorderedEntries,
            )
        }
    }

    suspend fun cancelQueuedChapters(chapterIds: Collection<Long>) {
        val idsToCancel = chapterIds.toSet()
        val result = mutex.withLock {
            val currentState = mutableQueueState.value
            val activeEntry = currentState.activeEntry
            val cancelledActive = activeEntry?.chapterId in idsToCancel
            val nextEntries = currentState.entries.filterNot { entry -> entry.chapterId in idsToCancel }
            val nextState = currentState.copy(
                entries = nextEntries,
                activeProgress = if (cancelledActive) {
                    null
                } else {
                    currentState.activeProgress
                },
            )

            persistQueueState(nextState)

            CancelResult(
                cancelledActive = cancelledActive,
                hasQueuedEntries = nextState.hasQueuedEntries,
                isPaused = nextState.isPaused,
                isEmpty = nextState.entries.isEmpty(),
            )
        }

        if (result.cancelledActive) {
            notifier.dismissProgress()
            workerController.stop()
            if (!result.isPaused && result.hasQueuedEntries) {
                workerController.restart()
            }
            return
        }

        if (result.isEmpty) {
            notifier.dismissProgress()
        }

        if (!result.isPaused && result.hasQueuedEntries) {
            startWorkerIfNeeded()
        }
    }

    suspend fun runPendingQueue(): Boolean {
        if (queueState.value.isPaused) {
            notifier.dismissProgress()
            return false
        }

        var processedAny = false
        while (true) {
            currentCoroutineContext().ensureActive()

            val chapterId = markNextQueuedChapterScanning() ?: break
            processedAny = true

            var lastError: String? = null
            try {
                val scanCompleted = scanner.scanChapter(
                    chapterId = chapterId,
                    onProgress = { progress ->
                        lastError = null
                        updateActiveProgress(chapterId, progress)
                        notifier.onProgress(
                            progress = progress,
                            remainingChapters = queueState.value.remainingChapterCount,
                        )
                    },
                    onComplete = { progress ->
                        updateActiveProgress(chapterId, progress)
                        notifier.onComplete(progress)
                    },
                    onError = { error ->
                        lastError = notifier.onError(error)
                    },
                    onCacheStateChanged = { changedChapterId, hasResults ->
                        cacheEventsFlow.tryEmit(
                            OcrChapterScanCacheEvent(
                                chapterId = changedChapterId,
                                hasResults = hasResults,
                            ),
                        )
                    },
                )

                if (scanCompleted) {
                    removeChapterEntry(chapterId)
                } else {
                    markChapterFailed(
                        chapterId = chapterId,
                        lastError = lastError,
                    )
                }
            } catch (e: CancellationException) {
                restoreScanningChapter(chapterId)
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) {
                    "Unexpected OCR queue failure while scanning chapterId=$chapterId"
                }
                val scanError = OcrChapterScanError(
                    mangaId = null,
                    mangaTitle = null,
                    chapterId = chapterId,
                    chapterName = chapterId.toString(),
                    failure = OcrScanFailure.Unexpected(e.message),
                )
                lastError = notifier.onError(scanError)
                markChapterFailed(
                    chapterId = chapterId,
                    lastError = lastError,
                )
            }
        }

        if (queueState.value.entries.isNotEmpty() && !queueState.value.hasQueuedEntries) {
            notifier.dismissProgress()
        }

        return processedAny
    }

    private suspend fun markNextQueuedChapterScanning(): Long? {
        return mutex.withLock {
            val currentState = mutableQueueState.value
            if (currentState.isPaused) {
                return@withLock null
            }

            val nextQueuedEntry = currentState.entries.firstOrNull { entry ->
                entry.state == OcrScanQueueEntry.State.QUEUED
            } ?: return@withLock null

            val nextEntries = buildList {
                add(
                    nextQueuedEntry.copy(
                        state = OcrScanQueueEntry.State.SCANNING,
                        lastError = null,
                    ),
                )
                addAll(
                    currentState.entries
                        .filterNot { entry -> entry.chapterId == nextQueuedEntry.chapterId }
                        .map { entry ->
                            if (entry.state == OcrScanQueueEntry.State.SCANNING) {
                                entry.copy(
                                    state = OcrScanQueueEntry.State.QUEUED,
                                    lastError = null,
                                )
                            } else {
                                entry
                            }
                        },
                )
            }

            persistQueueState(
                currentState.copy(
                    entries = nextEntries,
                    activeProgress = null,
                ),
            )

            nextQueuedEntry.chapterId
        }
    }

    private fun updateActiveProgress(
        chapterId: Long,
        progress: OcrChapterScanProgress,
    ) {
        mutableQueueState.update { state ->
            if (state.activeChapterId != chapterId) {
                state
            } else {
                state.copy(activeProgress = progress)
            }
        }
    }

    private suspend fun removeChapterEntry(chapterId: Long) {
        updateQueueState { state ->
            state.copy(
                entries = state.entries.filterNot { entry -> entry.chapterId == chapterId },
                activeProgress = state.activeProgress.takeUnless { it?.chapterId == chapterId },
            )
        }
    }

    private suspend fun markChapterFailed(
        chapterId: Long,
        lastError: String?,
    ) {
        updateQueueState { state ->
            val failedEntry = state.entries.firstOrNull { entry -> entry.chapterId == chapterId }
                ?: return@updateQueueState state

            val nextEntries = buildList {
                add(
                    failedEntry.copy(
                        state = OcrScanQueueEntry.State.ERROR,
                        lastError = lastError,
                    ),
                )
                addAll(state.entries.filterNot { entry -> entry.chapterId == chapterId })
            }

            state.copy(
                entries = nextEntries,
                activeProgress = null,
            )
        }
    }

    private suspend fun restoreScanningChapter(chapterId: Long) {
        updateQueueState { state ->
            val activeEntry = state.entries.firstOrNull { entry ->
                entry.chapterId == chapterId && entry.state == OcrScanQueueEntry.State.SCANNING
            } ?: return@updateQueueState state

            val restoredEntries = buildList {
                add(
                    activeEntry.copy(
                        state = OcrScanQueueEntry.State.QUEUED,
                        lastError = null,
                    ),
                )
                addAll(state.entries.filterNot { entry -> entry.chapterId == chapterId })
            }

            state.copy(
                entries = restoredEntries,
                activeProgress = null,
            )
        }
    }

    private suspend fun updateQueueState(
        transform: (OcrScanQueueState) -> OcrScanQueueState,
    ): Boolean {
        return mutex.withLock {
            val currentState = mutableQueueState.value
            val nextState = transform(currentState)
            if (nextState == currentState) {
                return@withLock false
            }

            persistQueueState(nextState)
            true
        }
    }

    private suspend fun persistQueueState(
        queueState: OcrScanQueueState,
    ) {
        mutableQueueState.value = queueState
        store.save(queueState.toStoreSnapshot())
    }

    private fun startWorkerIfNeeded() {
        workerController.start()
    }

    private data class CancelResult(
        val cancelledActive: Boolean,
        val hasQueuedEntries: Boolean,
        val isPaused: Boolean,
        val isEmpty: Boolean,
    )
}

data class OcrQueueStatus(
    val pending: Int,
    val isPaused: Boolean,
)

private fun List<OcrScanQueueEntry>.requeueActiveEntryToFront(): List<OcrScanQueueEntry> {
    val activeEntry = firstOrNull { entry -> entry.state == OcrScanQueueEntry.State.SCANNING }
        ?: return this

    return buildList {
        add(
            activeEntry.copy(
                state = OcrScanQueueEntry.State.QUEUED,
                lastError = null,
            ),
        )
        addAll(this@requeueActiveEntryToFront.filterNot { entry -> entry.chapterId == activeEntry.chapterId })
    }
}
