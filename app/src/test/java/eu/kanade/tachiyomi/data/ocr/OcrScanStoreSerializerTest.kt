package eu.kanade.tachiyomi.data.ocr

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OcrScanStoreSerializerTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun restoreNormalizesScanningEntries() {
        val encodedEntries = OcrScanStoreSerializer.serialize(
            json = json,
            entries = listOf(
                OcrScanQueueEntry(chapterId = 1L, state = OcrScanQueueEntry.State.SCANNING, lastError = "ignored"),
                OcrScanQueueEntry(chapterId = 2L, state = OcrScanQueueEntry.State.ERROR, lastError = "ignored"),
            ),
        )

        val restored = OcrScanStoreSerializer.restore(
            json = json,
            encodedEntries = encodedEntries,
        )

        assertEquals(
            listOf(
                OcrScanQueueEntry(chapterId = 1L, state = OcrScanQueueEntry.State.QUEUED),
                OcrScanQueueEntry(chapterId = 2L, state = OcrScanQueueEntry.State.ERROR),
            ),
            restored,
        )
    }
}
