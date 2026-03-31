package mihon.data.ocr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class PrioritizedTaskQueue(
    private val scope: CoroutineScope,
    private val onIdle: () -> Unit = {},
) {
    enum class Priority {
        HIGH,
        NORMAL,
    }

    private val mutex = Mutex()
    private val highPriorityTasks = ArrayDeque<suspend () -> Unit>()
    private val normalPriorityTasks = ArrayDeque<suspend () -> Unit>()

    private var activeTasks = 0
    private var workerJob: Job? = null

    suspend fun <T> submit(
        priority: Priority,
        block: suspend () -> T,
    ): T {
        val result = CompletableDeferred<T>()

        val task: suspend () -> Unit = {
            if (!result.isCancelled) {
                try {
                    result.complete(block())
                } catch (e: Throwable) {
                    result.completeExceptionally(e)
                }
            }
        }

        mutex.withLock {
            when (priority) {
                Priority.HIGH -> highPriorityTasks.addLast(task)
                Priority.NORMAL -> normalPriorityTasks.addLast(task)
            }
            if (workerJob?.isActive != true) {
                workerJob = scope.launch { processQueue() }
            }
        }

        return result.await()
    }

    suspend fun isIdle(): Boolean {
        return mutex.withLock {
            activeTasks == 0 && highPriorityTasks.isEmpty() && normalPriorityTasks.isEmpty()
        }
    }

    private suspend fun processQueue() {
        while (true) {
            val task = mutex.withLock {
                val nextTask = highPriorityTasks.removeFirstOrNull()
                    ?: normalPriorityTasks.removeFirstOrNull()

                if (nextTask == null) {
                    workerJob = null
                    null
                } else {
                    activeTasks++
                    nextTask
                }
            } ?: break

            try {
                task()
            } finally {
                val becameIdle = mutex.withLock {
                    activeTasks--
                    activeTasks == 0 && highPriorityTasks.isEmpty() && normalPriorityTasks.isEmpty()
                }
                if (becameIdle) {
                    onIdle()
                }
            }
        }
    }
}
