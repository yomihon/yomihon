package mihon.data.ocr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrioritizedTaskQueueTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun highPriorityTaskRunsBeforeQueuedNormalTask() = runTest {
        val events = mutableListOf<String>()
        val holdFirstTask = CompletableDeferred<Unit>()
        val queue = PrioritizedTaskQueue(backgroundScope)

        val first = async {
            queue.submit(PrioritizedTaskQueue.Priority.NORMAL) {
                events += "normal-1-start"
                holdFirstTask.await()
                events += "normal-1-end"
            }
        }
        advanceUntilIdle()

        val second = async {
            queue.submit(PrioritizedTaskQueue.Priority.NORMAL) {
                events += "normal-2"
            }
        }
        val highPriority = async {
            queue.submit(PrioritizedTaskQueue.Priority.HIGH) {
                events += "high"
            }
        }

        holdFirstTask.complete(Unit)

        first.await()
        highPriority.await()
        second.await()

        assertEquals(
            listOf("normal-1-start", "normal-1-end", "high", "normal-2"),
            events,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun highPriorityTaskRunsBeforeLaterPageScanChunks() = runTest {
        val events = mutableListOf<String>()
        val holdFirstChunk = CompletableDeferred<Unit>()
        val queue = PrioritizedTaskQueue(backgroundScope)

        val pageScan = async {
            queue.submit(PrioritizedTaskQueue.Priority.NORMAL) {
                events += "region-1-start"
                holdFirstChunk.await()
                events += "region-1-end"
            }

            queue.submit(PrioritizedTaskQueue.Priority.NORMAL) {
                events += "region-2"
            }
        }

        advanceUntilIdle()

        val recognizeText = async {
            queue.submit(PrioritizedTaskQueue.Priority.HIGH) {
                events += "recognize-text"
            }
        }

        holdFirstChunk.complete(Unit)

        pageScan.await()
        recognizeText.await()

        assertTrue(events.indexOf("recognize-text") < events.indexOf("region-2"))
    }
}
