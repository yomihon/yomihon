package eu.kanade.tachiyomi.data.ocr

internal data class OcrScanQueueEntry(
    val chapterId: Long,
    val state: State,
    val lastError: String? = null,
) {
    enum class State {
        QUEUED,
        SCANNING,
        ERROR,
    }
}

internal data class OcrScanQueueState(
    val entries: List<OcrScanQueueEntry>,
    val activeProgress: OcrChapterScanProgress?,
    val isPaused: Boolean,
) {
    val isActive: Boolean
        get() = entries.isNotEmpty()

    val activeEntry: OcrScanQueueEntry?
        get() = entries.firstOrNull { it.state == OcrScanQueueEntry.State.SCANNING }

    val activeChapterId: Long?
        get() = activeEntry?.chapterId

    val hasQueuedEntries: Boolean
        get() = entries.any { it.state == OcrScanQueueEntry.State.QUEUED }

    val remainingChapterCount: Int
        get() = entries.count { it.state != OcrScanQueueEntry.State.ERROR }
}

internal data class OcrChapterScanCacheEvent(
    val chapterId: Long,
    val hasResults: Boolean,
)

internal data class OcrScanStoreSnapshot(
    val entries: List<OcrScanQueueEntry>,
    val isPaused: Boolean,
)

internal fun OcrScanStoreSnapshot.toQueueState(
    activeProgress: OcrChapterScanProgress? = null,
): OcrScanQueueState {
    return OcrScanQueueState(
        entries = entries,
        activeProgress = activeProgress?.takeIf { progress ->
            entries.any { entry ->
                entry.chapterId == progress.chapterId &&
                    entry.state == OcrScanQueueEntry.State.SCANNING
            }
        },
        isPaused = isPaused,
    )
}

internal fun OcrScanQueueState.toStoreSnapshot(): OcrScanStoreSnapshot {
    return OcrScanStoreSnapshot(
        entries = entries,
        isPaused = isPaused,
    )
}
