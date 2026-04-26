package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.AndroidPreferenceStore

internal class OcrScanStore(
    context: Context,
    private val json: Json,
) {
    private val mutex = Mutex()
    private val preferenceStore = AndroidPreferenceStore(context)
    private val queuePreference = preferenceStore.getString(QUEUE_KEY, "")
    private val pausedPreference = preferenceStore.getBoolean(PAUSED_KEY, false)

    fun snapshot(): OcrScanStoreSnapshot {
        return OcrScanStoreSnapshot(
            entries = OcrScanStoreSerializer.restore(
                json = json,
                encodedEntries = queuePreference.get(),
            ),
            isPaused = pausedPreference.get(),
        )
    }

    suspend fun save(snapshot: OcrScanStoreSnapshot) {
        mutex.withLock {
            queuePreference.set(
                OcrScanStoreSerializer.serialize(
                    json = json,
                    entries = snapshot.entries,
                ),
            )
            pausedPreference.set(snapshot.isPaused)
        }
    }

    companion object {
        internal const val QUEUE_KEY = "ocr_preprocess_queue"
        internal const val PAUSED_KEY = "ocr_preprocess_paused"
    }
}

internal object OcrScanStoreSerializer {
    fun restore(
        json: Json,
        encodedEntries: String,
    ): List<OcrScanQueueEntry> {
        return decodeEntries(json, encodedEntries).map { entry ->
            entry.copy(
                state = if (entry.state == OcrScanQueueEntry.State.SCANNING) {
                    OcrScanQueueEntry.State.QUEUED
                } else {
                    entry.state
                },
                lastError = null,
            )
        }
    }

    fun serialize(
        json: Json,
        entries: List<OcrScanQueueEntry>,
    ): String {
        val persistedEntries = entries
            .distinctBy(OcrScanQueueEntry::chapterId)
            .map { entry ->
                PersistedOcrScanQueueEntry(
                    chapterId = entry.chapterId,
                    state = entry.state,
                )
            }
        return json.encodeToString(persistedEntries)
    }

    private fun decodeEntries(
        json: Json,
        encodedEntries: String,
    ): List<OcrScanQueueEntry> {
        if (encodedEntries.isBlank()) {
            return emptyList()
        }

        val decodedEntries = runCatching {
            json.decodeFromString<List<PersistedOcrScanQueueEntry>>(encodedEntries)
        }.getOrElse {
            return emptyList()
        }

        return decodedEntries
            .distinctBy(PersistedOcrScanQueueEntry::chapterId)
            .map { entry ->
                OcrScanQueueEntry(
                    chapterId = entry.chapterId,
                    state = entry.state,
                )
            }
    }
}

@Serializable
private data class PersistedOcrScanQueueEntry(
    val chapterId: Long,
    val state: OcrScanQueueEntry.State,
)
