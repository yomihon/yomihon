package mihon.data.ocr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OcrEngineLocksTest {

    @Test
    fun differentTextEnginesCanRunInParallel() = runTest {
        val locks = OcrEngineLocks()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = async {
            locks.withTextEngineLock(OcrRepositoryImpl.EngineType.LEGACY) {
                started.complete(Unit)
                release.await()
            }
        }

        started.await()

        val second = async {
            locks.withTextEngineLock(OcrRepositoryImpl.EngineType.FAST) {
                secondEntered.complete(Unit)
            }
        }

        secondEntered.await()
        assertTrue(second.isCompleted)

        release.complete(Unit)
        awaitAll(first, second)
    }

    @Test
    fun sameTextEngineStillRunsSequentially() = runTest {
        val locks = OcrEngineLocks()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = async {
            locks.withTextEngineLock(OcrRepositoryImpl.EngineType.GLENS) {
                started.complete(Unit)
                release.await()
            }
        }

        started.await()

        val second = async {
            locks.withTextEngineLock(OcrRepositoryImpl.EngineType.GLENS) {
                secondEntered.complete(Unit)
            }
        }

        testScheduler.runCurrent()
        assertFalse(secondEntered.isCompleted)
        assertFalse(second.isCompleted)

        release.complete(Unit)
        awaitAll(first, second)
        assertTrue(secondEntered.isCompleted)
    }

    @Test
    fun withAllLocksWaitsForBusyEngineLocks() = runTest {
        val locks = OcrEngineLocks()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cleanupOrder = mutableListOf<String>()

        val activeWork = async {
            locks.withTextEngineLock(OcrRepositoryImpl.EngineType.FAST) {
                cleanupOrder += "work-start"
                started.complete(Unit)
                release.await()
                cleanupOrder += "work-end"
            }
        }

        started.await()

        val cleanup = async {
            locks.withAllLocks {
                cleanupOrder += "cleanup"
            }
        }

        testScheduler.runCurrent()
        assertFalse(cleanup.isCompleted)

        release.complete(Unit)
        awaitAll(activeWork, cleanup)
        assertEquals(listOf("work-start", "work-end", "cleanup"), cleanupOrder)
    }
}
