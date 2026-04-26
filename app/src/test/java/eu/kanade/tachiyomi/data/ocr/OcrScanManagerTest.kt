package eu.kanade.tachiyomi.data.ocr

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OcrScanManagerTest {

    @BeforeEach
    fun setUp() {
        mockkObject(OcrScanJob.Companion)
        every { OcrScanJob.start(any()) } returns Unit
        every { OcrScanJob.stop(any()) } returns Unit
        every { OcrScanJob.restart(any()) } returns Unit
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(OcrScanJob.Companion)
    }

    @Test
    fun enqueueDedupesAndStartsWorker() = runTest {
        val fixture = createFixture()

        fixture.manager.enqueue(listOf(1L, 2L, 1L))

        assertEquals(
            listOf(
                entry(1L, OcrScanQueueEntry.State.QUEUED),
                entry(2L, OcrScanQueueEntry.State.QUEUED),
            ),
            fixture.manager.queueState.value.entries,
        )
    }

    @Test
    fun pauseAndResumeRequeueActiveChapterAndRetryErrors() = runTest {
        val fixture = createFixture(
            initialState = OcrScanStoreSnapshot(
                entries = listOf(
                    entry(1L, OcrScanQueueEntry.State.SCANNING),
                    entry(2L, OcrScanQueueEntry.State.ERROR, lastError = "failed"),
                    entry(3L, OcrScanQueueEntry.State.QUEUED),
                ),
                isPaused = false,
            ),
        )

        fixture.manager.pause()

        assertTrue(fixture.manager.queueState.value.isPaused)
        assertEquals(
            listOf(
                entry(1L, OcrScanQueueEntry.State.QUEUED),
                entry(2L, OcrScanQueueEntry.State.ERROR, lastError = "failed"),
                entry(3L, OcrScanQueueEntry.State.QUEUED),
            ),
            fixture.manager.queueState.value.entries,
        )

        fixture.manager.resume()

        assertFalse(fixture.manager.queueState.value.isPaused)
        assertEquals(
            listOf(
                entry(1L, OcrScanQueueEntry.State.QUEUED),
                entry(2L, OcrScanQueueEntry.State.QUEUED),
                entry(3L, OcrScanQueueEntry.State.QUEUED),
            ),
            fixture.manager.queueState.value.entries,
        )
    }

    @Test
    fun clearQueueResetsPausedFlag() = runTest {
        val fixture = createFixture(
            initialState = OcrScanStoreSnapshot(
                entries = listOf(entry(1L, OcrScanQueueEntry.State.ERROR)),
                isPaused = true,
            ),
        )

        fixture.manager.clearQueue()

        assertEquals(emptyList<OcrScanQueueEntry>(), fixture.manager.queueState.value.entries)
        assertFalse(fixture.manager.queueState.value.isPaused)
    }

    @Test
    fun reorderQueueKeepsActiveChapterFirst() = runTest {
        val fixture = createFixture(
            initialState = OcrScanStoreSnapshot(
                entries = listOf(
                    entry(1L, OcrScanQueueEntry.State.SCANNING),
                    entry(2L, OcrScanQueueEntry.State.QUEUED),
                    entry(3L, OcrScanQueueEntry.State.ERROR),
                    entry(4L, OcrScanQueueEntry.State.QUEUED),
                ),
                isPaused = false,
            ),
        )

        fixture.manager.reorderQueue(listOf(4L, 1L, 3L, 2L))

        assertEquals(
            listOf(
                entry(1L, OcrScanQueueEntry.State.SCANNING),
                entry(4L, OcrScanQueueEntry.State.QUEUED),
                entry(3L, OcrScanQueueEntry.State.ERROR),
                entry(2L, OcrScanQueueEntry.State.QUEUED),
            ),
            fixture.manager.queueState.value.entries,
        )
    }

    @Test
    fun cancelQueuedAndActiveChaptersUsesStableQueueSemantics() = runTest {
        val fixture = createFixture(
            initialState = OcrScanStoreSnapshot(
                entries = listOf(
                    entry(1L, OcrScanQueueEntry.State.SCANNING),
                    entry(2L, OcrScanQueueEntry.State.QUEUED),
                    entry(3L, OcrScanQueueEntry.State.ERROR),
                ),
                isPaused = false,
            ),
        )

        fixture.manager.cancelQueuedChapters(listOf(3L))
        assertEquals(
            listOf(
                entry(1L, OcrScanQueueEntry.State.SCANNING),
                entry(2L, OcrScanQueueEntry.State.QUEUED),
            ),
            fixture.manager.queueState.value.entries,
        )

        fixture.manager.cancelQueuedChapters(listOf(1L))

        assertEquals(
            listOf(entry(2L, OcrScanQueueEntry.State.QUEUED)),
            fixture.manager.queueState.value.entries,
        )
        assertNull(fixture.manager.queueState.value.activeProgress)
    }

    @Test
    fun runPendingQueueLeavesFailuresQueuedAsErrorAndContinues() = runTest {
        val fixture = createFixture(
            initialState = OcrScanStoreSnapshot(
                entries = listOf(
                    entry(1L, OcrScanQueueEntry.State.QUEUED),
                    entry(2L, OcrScanQueueEntry.State.QUEUED),
                ),
                isPaused = false,
            ),
        )
        coEvery { fixture.scanner.scanChapter(any(), any(), any(), any(), any()) } answers {
            val chapterId = args[0] as Long
            val onError = args[3] as (OcrChapterScanError) -> Unit
            if (chapterId == 1L) {
                onError(
                    OcrChapterScanError(
                        mangaId = 10L,
                        mangaTitle = "Manga",
                        chapterId = 1L,
                        chapterName = "Chapter 1",
                        failure = OcrScanFailure.Unexpected("boom"),
                    ),
                )
                false
            } else {
                true
            }
        }

        val processedAny = fixture.manager.runPendingQueue()

        assertTrue(processedAny)
        assertEquals(
            listOf(entry(1L, OcrScanQueueEntry.State.ERROR, lastError = "boom")),
            fixture.manager.queueState.value.entries,
        )
        coVerify(exactly = 1) { fixture.scanner.scanChapter(eq(1L), any(), any(), any(), any()) }
        coVerify(exactly = 1) { fixture.scanner.scanChapter(eq(2L), any(), any(), any(), any()) }
    }

    @Test
    fun runPendingQueueDoesNotAutoRetryFailures() = runTest {
        val fixture = createFixture(
            initialState = OcrScanStoreSnapshot(
                entries = listOf(entry(7L, OcrScanQueueEntry.State.QUEUED)),
                isPaused = false,
            ),
        )
        coEvery { fixture.scanner.scanChapter(any(), any(), any(), any(), any()) } returns false

        fixture.manager.runPendingQueue()

        assertEquals(
            listOf(entry(7L, OcrScanQueueEntry.State.ERROR)),
            fixture.manager.queueState.value.entries,
        )
        coVerify(exactly = 1) { fixture.scanner.scanChapter(eq(7L), any(), any(), any(), any()) }
    }

    private fun createFixture(
        initialState: OcrScanStoreSnapshot = OcrScanStoreSnapshot(
            entries = emptyList(),
            isPaused = false,
        ),
    ): Fixture {
        var persistedState = initialState
        val store = mockk<OcrScanStore>()
        every { store.snapshot() } answers { persistedState }
        coEvery { store.save(any()) } answers {
            persistedState = args[0] as OcrScanStoreSnapshot
        }

        val scanner = mockk<OcrChapterScanner>(relaxed = true)
        val notifier = mockk<OcrScanNotifier>(relaxed = true)
        every { notifier.onError(any()) } answers {
            val scanError = args[0] as OcrChapterScanError
            val failure = scanError.failure
            if (failure is OcrScanFailure.Unexpected) {
                failure.message.orEmpty()
            } else {
                scanError.chapterName
            }
        }

        return Fixture(
            manager = OcrScanManager(
                context = mockk(relaxed = true),
                store = store,
                scanner = scanner,
                notifier = notifier,
            ),
            scanner = scanner,
        )
    }

    private data class Fixture(
        val manager: OcrScanManager,
        val scanner: OcrChapterScanner,
    )

    private fun entry(
        chapterId: Long,
        state: OcrScanQueueEntry.State,
        lastError: String? = null,
    ): OcrScanQueueEntry {
        return OcrScanQueueEntry(
            chapterId = chapterId,
            state = state,
            lastError = lastError,
        )
    }
}
