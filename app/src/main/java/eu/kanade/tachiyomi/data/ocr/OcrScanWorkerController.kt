package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import kotlinx.coroutines.flow.Flow

internal interface OcrScanWorkerController {
    fun start()
    fun restart()
    fun stop()
    fun isRunningFlow(): Flow<Boolean>
}

internal class WorkManagerOcrScanWorkerController(
    private val context: Context,
) : OcrScanWorkerController {
    override fun start() {
        OcrScanJob.start(context)
    }

    override fun restart() {
        OcrScanJob.restart(context)
    }

    override fun stop() {
        OcrScanJob.stop(context)
    }

    override fun isRunningFlow(): Flow<Boolean> {
        return OcrScanJob.isRunningFlow(context)
    }
}
