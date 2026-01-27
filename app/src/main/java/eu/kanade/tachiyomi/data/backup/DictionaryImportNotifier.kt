package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

class DictionaryImportNotifier(private val context: Context) {

    private val progressNotificationBuilder = context.notificationBuilder(
        Notifications.CHANNEL_DICTIONARY_PROGRESS,
    ) {
        setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
        setSmallIcon(R.drawable.ic_mihon)
        setAutoCancel(false)
        setOngoing(true)
        setOnlyAlertOnce(true)
    }

    private val completeNotificationBuilder = context.notificationBuilder(
        Notifications.CHANNEL_DICTIONARY_COMPLETE,
    ) {
        setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
        setSmallIcon(R.drawable.ic_mihon)
        setAutoCancel(false)
    }

    private fun NotificationCompat.Builder.show(id: Int) {
        context.notify(id, build())
    }

    fun showDownloadingNotification(progress: Int?) {
        with(progressNotificationBuilder) {
            if (progress != null) {
                setContentTitle(context.stringResource(MR.strings.dictionary_import_download_progress, "$progress%"))
                setProgress(100, progress, false)
            } else {
                setContentTitle(context.stringResource(MR.strings.dictionary_import_downloading))
                setProgress(0, 0, true)
            }

            setOnlyAlertOnce(true)

            clearActions()
            addAction(
                R.drawable.ic_close_24dp,
                context.stringResource(MR.strings.action_cancel),
                NotificationReceiver.cancelDictionaryImportPendingBroadcast(
                    context,
                    Notifications.ID_DICTIONARY_IMPORT_PROGRESS,
                ),
            )

            show(Notifications.ID_DICTIONARY_IMPORT_PROGRESS)
        }
    }

    fun showParsingNotification(entriesImported: Int) {
        with(progressNotificationBuilder) {
            setContentTitle(context.stringResource(MR.strings.importing_dictionary))
            setContentText(context.stringResource(MR.strings.dictionary_import_progress, entriesImported))
            setProgress(0, 0, true)
            setOnlyAlertOnce(true)

            clearActions()
            addAction(
                R.drawable.ic_close_24dp,
                context.stringResource(MR.strings.action_cancel),
                NotificationReceiver.cancelDictionaryImportPendingBroadcast(
                    context,
                    Notifications.ID_DICTIONARY_IMPORT_PROGRESS,
                ),
            )

            show(Notifications.ID_DICTIONARY_IMPORT_PROGRESS)
        }
    }

    fun showCompleteNotification(dictionaryTitle: String) {
        context.cancelNotification(Notifications.ID_DICTIONARY_IMPORT_PROGRESS)

        with(completeNotificationBuilder) {
            setContentTitle(context.stringResource(MR.strings.dictionary_import_success))
            setContentText(dictionaryTitle)

            show(Notifications.ID_DICTIONARY_IMPORT_COMPLETE)
        }
    }

    fun showErrorNotification(error: String?) {
        context.cancelNotification(Notifications.ID_DICTIONARY_IMPORT_PROGRESS)

        with(completeNotificationBuilder) {
            setContentTitle(context.stringResource(MR.strings.dictionary_import_fail))
            setContentText(error)

            show(Notifications.ID_DICTIONARY_IMPORT_COMPLETE)
        }
    }
}
