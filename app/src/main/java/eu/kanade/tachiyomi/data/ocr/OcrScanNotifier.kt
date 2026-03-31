package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

internal class OcrScanNotifier(
    private val context: Context,
) {
    private val progressNotificationBuilder by lazy {
        context.notificationBuilder(Notifications.CHANNEL_OCR_PROGRESS) {
            setOnlyAlertOnce(true)
            setAutoCancel(false)
        }
    }

    private val errorNotificationBuilder by lazy {
        context.notificationBuilder(Notifications.CHANNEL_OCR_ERROR) {
            setAutoCancel(true)
        }
    }

    private fun NotificationCompat.Builder.show(id: Int) {
        context.notify(id, build())
    }

    fun onProgress(
        progress: OcrChapterScanProgress,
        remainingChapters: Int,
    ) {
        with(progressNotificationBuilder) {
            setSmallIcon(android.R.drawable.stat_sys_download)
            setAutoCancel(false)
            setContentTitle("${progress.mangaTitle} - ${progress.chapterName}")
            setContentText(
                context.stringResource(
                    MR.strings.ocr_preprocess_progress,
                    progress.chapterName,
                    progress.processedPages,
                    progress.totalPages,
                ),
            )
            setSubText(
                context.pluralStringResource(
                    MR.plurals.download_queue_summary,
                    count = remainingChapters,
                    remainingChapters,
                ),
            )
            setProgress(progress.totalPages, progress.processedPages, false)
            setOngoing(true)
            setContentIntent(NotificationReceiver.openEntryPendingActivity(context, progress.mangaId))
            show(Notifications.ID_OCR_PROGRESS)
        }
    }

    fun onComplete(progress: OcrChapterScanProgress) {
        with(progressNotificationBuilder) {
            setSmallIcon(R.drawable.ic_done_24dp)
            setAutoCancel(true)
            setContentTitle(context.stringResource(MR.strings.ocr_preprocess_title))
            setContentText(context.stringResource(MR.strings.ocr_preprocess_completed, progress.chapterName))
            setSubText(progress.mangaTitle)
            setProgress(0, 0, false)
            setOngoing(false)
            setContentIntent(NotificationReceiver.openEntryPendingActivity(context, progress.mangaId))
            show(Notifications.ID_OCR_PROGRESS)
        }
    }

    fun onError(
        error: OcrChapterScanError,
    ): String {
        val message = error.failure.toMessage()
        with(errorNotificationBuilder) {
            setSmallIcon(R.drawable.ic_warning_white_24dp)
            setContentTitle(context.stringResource(MR.strings.ocr_preprocess_failed, error.chapterName))
            setContentText(message)
            setSubText(error.mangaTitle)
            setProgress(0, 0, false)
            error.mangaId?.let { setContentIntent(NotificationReceiver.openEntryPendingActivity(context, it)) }
            show(Notifications.ID_OCR_ERROR)
        }
        return message
    }

    fun dismissProgress() {
        context.cancelNotification(Notifications.ID_OCR_PROGRESS)
    }

    private fun OcrScanFailure.toMessage(): String {
        return when (this) {
            OcrScanFailure.ChapterNotFound -> context.stringResource(MR.strings.chapter_not_found)
            OcrScanFailure.MangaNotFound -> context.stringResource(MR.strings.ocr_scan_manga_not_found)
            OcrScanFailure.NoPages -> context.stringResource(MR.strings.page_list_empty_error)
            is OcrScanFailure.Unexpected ->
                message
                    ?: context.stringResource(MR.strings.download_notifier_unknown_error)
        }
    }
}
