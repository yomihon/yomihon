package eu.kanade.tachiyomi.data.dictionary

import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

class DictionaryMigrationNotifier(private val context: Context) {

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

    fun showMigrationProgressNotification(
        dictionaryTitle: String,
        stage: String,
        completed: Int,
        total: Int,
    ) {
        with(progressNotificationBuilder) {
            setContentTitle(context.stringResource(MR.strings.dictionary_migration_in_progress))
            setContentText("$dictionaryTitle • $stage ($completed/$total)")
            setProgress(total.coerceAtLeast(1), completed.coerceAtMost(total), false)
            setOnlyAlertOnce(true)
            clearActions()
            show(Notifications.ID_DICTIONARY_MIGRATION_PROGRESS)
        }
    }

    fun showMigrationCompleteNotification() {
        context.cancelNotification(Notifications.ID_DICTIONARY_MIGRATION_PROGRESS)

        with(completeNotificationBuilder) {
            setContentTitle(context.stringResource(MR.strings.dictionary_migration_complete))
            setContentText(context.stringResource(MR.strings.dictionary_migration_complete_summary))

            show(Notifications.ID_DICTIONARY_MIGRATION_COMPLETE)
        }
    }
}
